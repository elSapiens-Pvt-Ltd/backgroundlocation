import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Posts region crossings straight to a server, from native code.
///
/// A crossing usually happens with the app dead. iOS relaunches it in the
/// background for a few seconds to deliver the region event, and the webview
/// may never boot in that window — so a crossing that only reaches JavaScript
/// is a crossing the server hears about whenever the user next opens the app,
/// if ever. For anything that has to act on the crossing (an automatic break,
/// an end-of-day punch-out), that is too late; the report has to leave from
/// here.
///
/// Every report is queued before it is sent, and the queue is persisted, so a
/// crossing in a basement with no signal still arrives once the phone is next
/// woken with a connection — by the next crossing, by the app opening, or by
/// the plugin loading. Sends are strictly in order: the server decides breaks
/// from the sequence of exits and entries, and delivering an entry before the
/// exit it answers would invert its meaning.
final class GeofenceReporter {
    private static let queueKey = "geofence.reportQueue"
    /// Bounded, oldest dropped first: a queue behind a server that never
    /// answers must not grow without limit.
    static let maxQueued = 100
    /// A report the server keeps rejecting for reasons other than auth is
    /// dropped after this many attempts rather than blocking everything behind
    /// it forever.
    static let maxAttempts = 20

    private let store: KeyValueStore
    private let lock = NSLock()
    private var isFlushing = false

    /// Called with a region id when the server refuses its credential (401 or
    /// 403): the token was revoked — typically the employee punched out — so
    /// the region has nothing left to report to and should stop being watched.
    var onCredentialRevoked: (String) -> Void = { _ in }

    init(store: KeyValueStore) {
        self.store = store
    }

    func enqueue(regionID: String, report: GeofenceReportSpec, transition: GeofenceTransition) {
        lock.lock()
        var queue = loadQueue()
        queue.append(QueuedGeofenceReport(
            regionId: regionID, url: report.url, authToken: report.authToken,
            transition: transition, attempts: 0))
        if queue.count > GeofenceReporter.maxQueued {
            queue.removeFirst(queue.count - GeofenceReporter.maxQueued)
        }
        saveQueue(queue)
        lock.unlock()
    }

    /// Sends queued reports in order until one fails for a reason worth
    /// retrying. Safe to call at any time and from any thread; a flush already
    /// in progress makes this a no-op.
    func flush() {
        lock.lock()
        if isFlushing || loadQueue().isEmpty {
            lock.unlock()
            return
        }
        isFlushing = true
        lock.unlock()

        let taskId = GeofenceReporter.beginBackgroundTask()
        sendNext {
            GeofenceReporter.endBackgroundTask(taskId)
        }
    }

    private func sendNext(done: @escaping () -> Void) {
        lock.lock()
        let queue = loadQueue()
        guard let next = queue.first else {
            isFlushing = false
            lock.unlock()
            done()
            return
        }
        lock.unlock()

        guard let url = URL(string: next.url) else {
            removeFirst(matching: next)
            sendNext(done: done)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("ElsapiensBackgroundLocation/1.0", forHTTPHeaderField: "User-Agent")
        if let token = next.authToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try? JSONEncoder().encode(next.transition)

        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            guard let self = self else { done(); return }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0

            if error == nil, (200..<300).contains(status) {
                self.removeFirst(matching: next)
                self.sendNext(done: done)
                return
            }
            if status == 401 || status == 403 {
                // The credential is gone and will not come back: nothing queued
                // for this region can ever be delivered, and watching it on
                // would only wake the app for crossings no one will receive.
                self.removeAll(forRegion: next.regionId)
                self.onCredentialRevoked(next.regionId)
                self.sendNext(done: done)
                return
            }

            // No network, a timeout, or a server error: keep it, in place, and
            // stop here — later reports must not overtake it.
            self.recordFailedAttempt(for: next)
            self.lock.lock()
            self.isFlushing = false
            self.lock.unlock()
            done()
        }.resume()
    }

    // MARK: - Queue

    private func removeFirst(matching entry: QueuedGeofenceReport) {
        lock.lock()
        var queue = loadQueue()
        if let index = queue.firstIndex(where: { $0.matches(entry) }) {
            queue.remove(at: index)
        }
        saveQueue(queue)
        lock.unlock()
    }

    private func removeAll(forRegion regionID: String) {
        lock.lock()
        saveQueue(loadQueue().filter { $0.regionId != regionID })
        lock.unlock()
    }

    private func recordFailedAttempt(for entry: QueuedGeofenceReport) {
        lock.lock()
        var queue = loadQueue()
        if let index = queue.firstIndex(where: { $0.matches(entry) }) {
            queue[index].attempts += 1
            if queue[index].attempts >= GeofenceReporter.maxAttempts {
                queue.remove(at: index)
            }
        }
        saveQueue(queue)
        lock.unlock()
    }

    private func loadQueue() -> [QueuedGeofenceReport] {
        guard let raw = store.string(forKey: GeofenceReporter.queueKey),
              let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([QueuedGeofenceReport].self, from: data)
        else { return [] }
        return decoded
    }

    private func saveQueue(_ queue: [QueuedGeofenceReport]) {
        guard let data = try? JSONEncoder().encode(queue) else { return }
        store.set(String(data: data, encoding: .utf8), forKey: GeofenceReporter.queueKey)
    }

    // MARK: - Background time

    #if canImport(UIKit)
    /// iOS gives an app relaunched for a region event only a few seconds;
    /// asking for background time keeps it alive long enough for the send.
    private static func beginBackgroundTask() -> UIBackgroundTaskIdentifier {
        var identifier: UIBackgroundTaskIdentifier = .invalid
        identifier = UIApplication.shared.beginBackgroundTask(withName: "GeofenceReport") {
            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }
        return identifier
    }

    private static func endBackgroundTask(_ identifier: UIBackgroundTaskIdentifier) {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
    }
    #else
    private static func beginBackgroundTask() -> Int { 0 }
    private static func endBackgroundTask(_ identifier: Int) {}
    #endif
}

/// Where a region's crossings are posted. The token is the caller's, sent as a
/// Bearer credential; the server is expected to answer 401/403 once it is no
/// longer valid, which stops the region.
struct GeofenceReportSpec: Codable {
    let url: String
    let authToken: String?
}

struct QueuedGeofenceReport: Codable {
    let regionId: String
    let url: String
    let authToken: String?
    let transition: GeofenceTransition
    var attempts: Int

    func matches(_ other: QueuedGeofenceReport) -> Bool {
        regionId == other.regionId
            && transition.timestamp == other.transition.timestamp
            && transition.transition == other.transition.transition
    }
}
