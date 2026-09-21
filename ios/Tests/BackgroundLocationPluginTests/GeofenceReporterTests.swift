import XCTest
@testable import BackgroundLocationPlugin

/// Thread-safe in-memory store: the reporter writes from URLSession's queue.
private final class LockedStore: KeyValueStore {
    private let lock = NSLock()
    private var strings: [String: String] = [:]
    private var bools: [String: Bool] = [:]
    private var doubles: [String: Double] = [:]

    func string(forKey key: String) -> String? { lock.lock(); defer { lock.unlock() }; return strings[key] }
    func set(_ value: String?, forKey key: String) { lock.lock(); strings[key] = value; lock.unlock() }
    func bool(forKey key: String) -> Bool { lock.lock(); defer { lock.unlock() }; return bools[key] ?? false }
    func set(_ value: Bool, forKey key: String) { lock.lock(); bools[key] = value; lock.unlock() }
    func double(forKey key: String) -> Double { lock.lock(); defer { lock.unlock() }; return doubles[key] ?? 0 }
    func set(_ value: Double, forKey key: String) { lock.lock(); doubles[key] = value; lock.unlock() }
    func removeObject(forKey key: String) {
        lock.lock()
        strings.removeValue(forKey: key); bools.removeValue(forKey: key); doubles.removeValue(forKey: key)
        lock.unlock()
    }
}

/// Answers every request with the next scripted status and records what was sent.
private final class StubProtocol: URLProtocol {
    static let lock = NSLock()
    static var statuses: [Int] = []
    static var received: [(auth: String?, body: [String: Any])] = []

    static func reset(_ scripted: [Int]) {
        lock.lock(); statuses = scripted; received = []; lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "reports.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body: [String: Any] = [:]
        if let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(buffer, maxLength: 4096)
                if n <= 0 { break }
                data.append(buffer, count: n)
            }
            buffer.deallocate()
            stream.close()
            body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        } else if let data = request.httpBody {
            body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        }

        StubProtocol.lock.lock()
        StubProtocol.received.append((request.value(forHTTPHeaderField: "Authorization"), body))
        let status = StubProtocol.statuses.isEmpty ? 200 : StubProtocol.statuses.removeFirst()
        StubProtocol.lock.unlock()

        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class GeofenceReporterTests: XCTestCase {
    private let report = GeofenceReportSpec(url: "https://reports.test/geofence", authToken: "device-token")

    override func setUp() {
        super.setUp()
        URLProtocol.registerClass(StubProtocol.self)
    }

    override func tearDown() {
        URLProtocol.unregisterClass(StubProtocol.self)
        super.tearDown()
    }

    private func crossing(_ kind: String, at ms: Double) -> GeofenceTransition {
        GeofenceTransition(id: "site", transition: kind, latitude: 9.9, longitude: 76.3, accuracy: 12, timestamp: ms)
    }

    private func queued(_ store: KeyValueStore) -> Int {
        guard let raw = store.string(forKey: "geofence.reportQueue"),
              let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return 0 }
        return array.count
    }

    /// Polls until `condition` holds; the reporter completes on URLSession's queue.
    private func wait(until condition: @escaping () -> Bool, timeout: TimeInterval = 5) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
    }

    func testDeliversCrossingsInOrderWithTheToken() {
        StubProtocol.reset([200, 200])
        let store = LockedStore()
        let reporter = GeofenceReporter(store: store)

        reporter.enqueue(regionID: "site", report: report, transition: crossing("exit", at: 1_000))
        reporter.enqueue(regionID: "site", report: report, transition: crossing("enter", at: 2_000))
        reporter.flush()
        wait { self.queued(store) == 0 }

        XCTAssertEqual(queued(store), 0, "delivered reports must leave the queue")
        StubProtocol.lock.lock(); let received = StubProtocol.received; StubProtocol.lock.unlock()
        XCTAssertEqual(received.map { $0.body["transition"] as? String }, ["exit", "enter"],
                       "an entry delivered before the exit it answers would invert its meaning")
        XCTAssertEqual(received.first?.auth, "Bearer device-token")
    }

    func testARevokedTokenStopsTheRegion() {
        StubProtocol.reset([401])
        let store = LockedStore()
        let reporter = GeofenceReporter(store: store)
        var revoked: String?
        reporter.onCredentialRevoked = { revoked = $0 }

        reporter.enqueue(regionID: "site", report: report, transition: crossing("exit", at: 1_000))
        reporter.enqueue(regionID: "site", report: report, transition: crossing("enter", at: 2_000))
        reporter.flush()
        wait { revoked != nil && self.queued(store) == 0 }

        XCTAssertEqual(revoked, "site", "a revoked credential must stop the region being watched")
        XCTAssertEqual(queued(store), 0, "nothing queued for a revoked region can ever be delivered")
    }

    func testAFailureStaysQueuedAndIsNotOvertaken() {
        StubProtocol.reset([503])
        let store = LockedStore()
        let reporter = GeofenceReporter(store: store)

        reporter.enqueue(regionID: "site", report: report, transition: crossing("exit", at: 1_000))
        reporter.enqueue(regionID: "site", report: report, transition: crossing("enter", at: 2_000))
        reporter.flush()
        wait { StubProtocol.lock.lock(); defer { StubProtocol.lock.unlock() }; return !StubProtocol.received.isEmpty }
        RunLoop.current.run(until: Date().addingTimeInterval(0.3))

        StubProtocol.lock.lock(); let sent = StubProtocol.received.count; StubProtocol.lock.unlock()
        XCTAssertEqual(sent, 1, "the entry must not be sent ahead of the exit that failed")
        XCTAssertEqual(queued(store), 2, "both must stay queued for the next attempt")
    }
}
