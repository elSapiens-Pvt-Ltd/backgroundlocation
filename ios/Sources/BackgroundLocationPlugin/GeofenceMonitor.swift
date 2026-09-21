import CoreLocation
import Foundation
import UserNotifications

/// Watches circular regions through CoreLocation's region monitoring.
///
/// This is deliberately not built on `TaskLocationTracker` or any of the
/// sampling code. Region monitoring is a different kind of thing: the OS keeps
/// the watch, delivers the crossing to a *terminated* app by relaunching it,
/// and costs almost no battery because it rides on location hardware the device
/// is already running. Sampling can do none of that — a phone in a pocket with
/// the app closed is invisible to it, which is precisely the case this exists
/// to cover.
///
/// Two consequences shape the design:
///
/// 1. **Definitions must outlive the process.** When iOS relaunches the app for
///    a crossing, the only thing it hands back is a `CLCircularRegion` and its
///    identifier. Everything else the caller attached — notification text above
///    all — has to be read from persistent storage, so the definitions are
///    stored rather than held in memory.
/// 2. **The crossing must survive having nowhere to go.** JavaScript is usually
///    not running at that moment and may never run before the app is suspended
///    again. Crossings are therefore written to the same store and drained
///    later; the live event is the optimisation, not the mechanism.
final class GeofenceMonitor: NSObject {
    /// CoreLocation's hard limit. Exceeding it makes iOS silently drop regions,
    /// so it is enforced here where it can be reported.
    static let maxRegions = 20

    private static let definitionsKey = "geofence.definitions"
    private static let pendingKey = "geofence.pending"

    private let manager = CLLocationManager()
    private let store: KeyValueStore
    private let permissions: LocationPermissionManager
    /// Posts crossings to a server natively, for regions registered with a
    /// `report` — see GeofenceReporter for why this cannot wait for JS.
    let reporter: GeofenceReporter

    weak var eventSink: BackgroundLocationEventSink?

    /// Whether a JavaScript listener is attached right now. The plugin owns
    /// this, because only the Capacitor layer knows; when false, crossings are
    /// buffered instead of emitted.
    var hasLiveListener: () -> Bool = { false }

    init(permissions: LocationPermissionManager, store: KeyValueStore) {
        self.permissions = permissions
        self.store = store
        self.reporter = GeofenceReporter(store: store)
        super.init()
        manager.delegate = self
        manager.allowsBackgroundLocationUpdates = false
        reporter.onCredentialRevoked = { [weak self] regionID in
            // CLLocationManager belongs to the main thread; the reporter calls
            // back from URLSession's queue.
            DispatchQueue.main.async { self?.remove(id: regionID) }
        }
    }

    /// Sends anything still queued from earlier crossings. Called whenever the
    /// app gets a chance to run — plugin load, returning to the foreground —
    /// so a crossing that happened offline is delivered at the first moment it
    /// can be.
    func flushReports() {
        reporter.flush()
    }

    // MARK: - Registration

    func add(_ geofence: GeofenceDefinition) throws {
        guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else {
            throw GeofenceError.unavailable
        }
        guard permissions.hasBackgroundLocationPermission else {
            // Registering anyway would produce a watch that never fires once
            // the app is backgrounded — a silent no-op is worse than a refusal
            // the caller can act on.
            throw GeofenceError.backgroundPermissionDenied
        }

        var definitions = storedDefinitions()
        definitions.removeAll { $0.id == geofence.id }
        guard definitions.count < GeofenceMonitor.maxRegions else {
            throw GeofenceError.tooManyRegions
        }

        // A radius above the device's maximum is accepted by the API and then
        // quietly clamped by the system, so clamp it here where it is visible.
        let radius = min(geofence.radius, manager.maximumRegionMonitoringDistance)
        let region = CLCircularRegion(
            center: CLLocationCoordinate2D(latitude: geofence.latitude, longitude: geofence.longitude),
            radius: radius,
            identifier: geofence.id
        )
        region.notifyOnEntry = geofence.notifyOnEntry
        region.notifyOnExit = geofence.notifyOnExit

        stopMonitoring(id: geofence.id)
        manager.startMonitoring(for: region)

        definitions.append(geofence)
        save(definitions)

        // Regions only fire on a CROSSING. Someone already standing inside the
        // office when the watch is armed would never trigger it, which for an
        // arrival reminder is the one case that must not be missed — so ask
        // iOS to report the current state once.
        manager.requestState(for: region)
    }

    func remove(id: String) {
        stopMonitoring(id: id)
        var definitions = storedDefinitions()
        definitions.removeAll { $0.id == id }
        save(definitions)
    }

    func removeAll() {
        for region in manager.monitoredRegions {
            manager.stopMonitoring(for: region)
        }
        save([])
    }

    func list() -> [GeofenceDefinition] {
        storedDefinitions()
    }

    private func stopMonitoring(id: String) {
        for region in manager.monitoredRegions where region.identifier == id {
            manager.stopMonitoring(for: region)
        }
    }

    // MARK: - Crossings

    func pendingTransitions() -> [GeofenceTransition] {
        guard let raw = store.string(forKey: GeofenceMonitor.pendingKey),
              let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([GeofenceTransition].self, from: data)
        else { return [] }
        return decoded
    }

    func clearPendingTransitions() {
        store.removeObject(forKey: GeofenceMonitor.pendingKey)
    }

    private func handle(regionID: String, transition: GeofenceTransitionKind) {
        guard let definition = storedDefinitions().first(where: { $0.id == regionID }) else {
            // A region we no longer know about — stop it rather than leave the
            // OS waking us for something nothing will handle.
            stopMonitoring(id: regionID)
            return
        }

        let event = GeofenceTransition(
            id: regionID,
            transition: transition.rawValue,
            latitude: manager.location?.coordinate.latitude,
            longitude: manager.location?.coordinate.longitude,
            accuracy: manager.location?.horizontalAccuracy,
            timestamp: Date().timeIntervalSince1970 * 1000
        )

        // The notification goes out first and unconditionally. It is the only
        // part of this the user actually sees, and it must not depend on the
        // webview booting before iOS suspends the app again. A direction-
        // specific message wins over the general one: leaving and coming back
        // usually need saying differently.
        let directional = transition == .enter ? definition.enterNotification : definition.exitNotification
        if let notification = directional ?? definition.notification {
            post(notification, for: regionID)
        }

        // Then the server, natively and before anything JavaScript-bound: this
        // is the path that has to work with the app dead.
        if let report = definition.report {
            reporter.enqueue(regionID: regionID, report: report, transition: event)
            reporter.flush()
        }

        if hasLiveListener() {
            eventSink?.didCrossGeofence(event, buffered: false)
        } else {
            var pending = pendingTransitions()
            pending.append(event)
            // Bounded, oldest dropped first: an unbounded buffer behind an app
            // that never launches grows without limit.
            if pending.count > 50 { pending.removeFirst(pending.count - 50) }
            if let data = try? JSONEncoder().encode(pending) {
                store.set(String(data: data, encoding: .utf8), forKey: GeofenceMonitor.pendingKey)
            }
        }
    }

    private func post(_ notification: GeofenceNotificationSpec, for regionID: String) {
        let content = UNMutableNotificationContent()
        content.title = notification.title
        content.body = notification.body
        content.sound = .default
        content.userInfo = ["geofenceId": regionID]

        let request = UNNotificationRequest(
            identifier: "geofence.\(regionID).\(Int(Date().timeIntervalSince1970))",
            content: content,
            trigger: nil // deliver now
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                NSLog("[BackgroundLocation] geofence notification failed: \(error)")
            }
        }
    }

    // MARK: - Persistence

    private func storedDefinitions() -> [GeofenceDefinition] {
        guard let raw = store.string(forKey: GeofenceMonitor.definitionsKey),
              let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([GeofenceDefinition].self, from: data)
        else { return [] }
        return decoded
    }

    private func save(_ definitions: [GeofenceDefinition]) {
        guard let data = try? JSONEncoder().encode(definitions) else { return }
        store.set(String(data: data, encoding: .utf8), forKey: GeofenceMonitor.definitionsKey)
    }
}

extension GeofenceMonitor: CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        handle(regionID: region.identifier, transition: .enter)
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        handle(regionID: region.identifier, transition: .exit)
    }

    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        // Answers the requestState issued when the region was armed: treat
        // "already inside" as an arrival, since a crossing will never come.
        guard state == .inside,
              let definition = storedDefinitions().first(where: { $0.id == region.identifier }),
              definition.notifyOnEntry
        else { return }
        handle(regionID: region.identifier, transition: .enter)
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        eventSink?.didEmitError(
            code: .internalError,
            message: "region monitoring failed for \(region?.identifier ?? "unknown"): \(error.localizedDescription)",
            source: .geofencing,
            fatal: false
        )
    }
}

// MARK: - Models

struct GeofenceNotificationSpec: Codable {
    let title: String
    let body: String
}

struct GeofenceDefinition: Codable {
    let id: String
    let latitude: Double
    let longitude: Double
    let radius: Double
    let notifyOnEntry: Bool
    let notifyOnExit: Bool
    let notification: GeofenceNotificationSpec?
    // Added in 0.4.0. Optional, so definitions stored by an earlier version
    // still decode — a missing key reads as nil.
    var enterNotification: GeofenceNotificationSpec? = nil
    var exitNotification: GeofenceNotificationSpec? = nil
    var report: GeofenceReportSpec? = nil
}

enum GeofenceTransitionKind: String {
    case enter
    case exit
}

struct GeofenceTransition: Codable {
    let id: String
    let transition: String
    let latitude: Double?
    let longitude: Double?
    let accuracy: Double?
    let timestamp: Double
}

enum GeofenceError: Error {
    case unavailable
    case backgroundPermissionDenied
    case tooManyRegions
}
