import CoreLocation
import Foundation

/// Samples location at reduced accuracy/frequency during work hours and hands each
/// fix to a `WorkHourLocationUploader` for batched server upload.
///
/// Runs its own `CLLocationManager` independent of `TaskLocationTracker` so the two
/// tracking modes never interfere — mirroring the Android plugin's two independent
/// foreground services. Same background-mode crash guard as `TaskLocationTracker`
/// (see that class's doc comment for why it matters).
final class WorkHourLocationTracker: NSObject, CLLocationManagerDelegate {

    weak var eventSink: BackgroundLocationEventSink?

    private let manager = CLLocationManager()
    private let permissions: LocationPermissionManager
    private let stateStore: TrackingStateStore
    private let queue = WorkHourLocationQueue()

    private var engineerId: String?
    private var uploader: WorkHourLocationUploader?
    /// Mirrors the same "no native time-based update rate on CoreLocation" gap noted
    /// on `TaskLocationTracker`: without this, the 50m `distanceFilter` alone would
    /// queue far more samples than Android's request-interval-throttled sampling for
    /// the same `uploadInterval`, wasting battery and queue space.
    private var sampleIntervalSeconds: Double = TrackingStateStore.defaultUploadIntervalSeconds
    private var lastSampledAt: Date?
    private var minDistance: Double = TrackingStateStore.defaultWorkHourMinDistanceMeters
    private(set) var isActive = false

    init(permissions: LocationPermissionManager, stateStore: TrackingStateStore) {
        self.permissions = permissions
        self.stateStore = stateStore
        super.init()
        manager.delegate = self
    }

    func start(engineerId: String, uploadInterval: Double, serverUrl: String, authToken: String?,
               enableOfflineQueue: Bool,
               minDistance: Double = TrackingStateStore.defaultWorkHourMinDistanceMeters,
               completion: @escaping (TaskLocationTracker.StartResult) -> Void) {
        guard !engineerId.isEmpty else {
            completion(.failure(.missingParameter, "engineerId must not be empty"))
            return
        }
        guard !serverUrl.isEmpty else {
            completion(.failure(.missingParameter, "serverUrl must not be empty"))
            return
        }
        guard permissions.hasForegroundLocationPermission else {
            completion(.failure(.permissionDenied, "Location permission not granted. Call requestPermissions() first."))
            return
        }
        permissions.isLocationServicesEnabled { [weak self] enabled in
            guard let self else { return }
            guard enabled else {
                completion(.failure(.locationServicesDisabled, "Device location services are disabled."))
                return
            }
            guard let uploader = WorkHourLocationUploader(
                engineerId: engineerId, uploadInterval: uploadInterval, serverUrl: serverUrl,
                authToken: authToken, enableOfflineQueue: enableOfflineQueue,
                queue: self.queue, eventSink: self.eventSink) else {
                completion(.failure(.missingParameter, "serverUrl is not a valid URL"))
                return
            }

            self.stateStore.saveWorkHourTracking(TrackingStateStore.WorkHourState(
                engineerId: engineerId, uploadInterval: uploadInterval, serverUrl: serverUrl,
                authToken: authToken, enableOfflineQueue: enableOfflineQueue,
                minDistance: max(0, minDistance)))

            self.uploader?.stop()
            self.uploader = uploader
            self.engineerId = engineerId
            self.sampleIntervalSeconds = uploadInterval > 0 ? uploadInterval : TrackingStateStore.defaultUploadIntervalSeconds
            self.lastSampledAt = nil
            self.minDistance = max(0, minDistance)
            self.beginSampling()
            uploader.start()

            completion(.success(backgroundLocationGranted: self.permissions.hasBackgroundLocationPermission))
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
        if permissions.hostAppDeclaresBackgroundLocationMode {
            manager.allowsBackgroundLocationUpdates = false
        }
        uploader?.stop()
        uploader = nil
        engineerId = nil
        lastSampledAt = nil
        isActive = false
        stateStore.clearWorkHourTracking()
    }

    func resumeIfNeeded() {
        guard !isActive, let saved = stateStore.getWorkHourTracking() else { return }
        guard permissions.hasForegroundLocationPermission else { return }
        start(engineerId: saved.engineerId, uploadInterval: saved.uploadInterval, serverUrl: saved.serverUrl,
              authToken: saved.authToken, enableOfflineQueue: saved.enableOfflineQueue,
              minDistance: saved.minDistance) { _ in }
    }

    func queueSnapshot() -> [WorkHourLocationData] {
        uploader?.queueSnapshot() ?? queue.snapshot()
    }

    func clearQueue() {
        uploader?.clearQueue()
        queue.clear()
    }

    private func beginSampling() {
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        // 0 = no distance filter: the time gate alone paces fixes, so a stationary
        // device still reports once per interval (a heartbeat for "last seen")
        manager.distanceFilter = minDistance > 0 ? minDistance : kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .other

        if permissions.hasBackgroundLocationPermission && permissions.hostAppDeclaresBackgroundLocationMode {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
        } else if !permissions.hasBackgroundLocationPermission {
            eventSink?.didEmitError(code: .backgroundPermissionDenied,
                message: "Background location permission (\"Always\") is not granted. Work-hour tracking continues "
                    + "while the app is active, but cannot resume automatically if iOS suspends it.",
                source: .workHourTracking, fatal: false)
        }

        manager.startUpdatingLocation()
        isActive = true
    }

    // MARK: CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let uploader else { return }
        let now = Date()
        for location in locations {
            if let lastSampledAt, now.timeIntervalSince(lastSampledAt) < sampleIntervalSeconds {
                continue
            }
            lastSampledAt = now
            uploader.addLocationToQueue(
                latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                accuracy: location.horizontalAccuracy, timestamp: location.timestamp)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard let clError = error as? CLError, clError.code == .denied else { return }
        permissions.isLocationServicesEnabled { [weak self] enabled in
            guard let self else { return }
            if !enabled {
                self.eventSink?.didChangeLocationServicesEnabled(false)
                self.eventSink?.didEmitError(code: .locationServicesDisabled,
                    message: "Device location services were disabled during work-hour tracking.",
                    source: .workHourTracking, fatal: false)
            } else {
                self.eventSink?.didEmitError(code: .permissionDenied,
                    message: "Location permission was revoked during work-hour tracking.",
                    source: .workHourTracking, fatal: true)
                self.stop()
            }
        }
    }
}
