import Foundation

/// Persists the desired tracking state across process death and relaunch.
///
/// iOS can terminate a suspended app at any time and later relaunch it in the
/// background (in response to a significant location change, for instance) with no
/// memory of prior state. This store is what lets `TaskLocationTracker` and
/// `WorkHourLocationTracker` resume the right session — with the right reference,
/// interval and accuracy — instead of starting a bogus one. Mirrors the Android
/// plugin's `TrackingStateStore` field-for-field. Single responsibility: persistence
/// only — no CoreLocation logic lives here.
final class TrackingStateStore {

    // Task tracking keys
    private static let keyTaskActive = "task_active"
    private static let keyTaskReference = "task_reference"
    private static let keyTaskInterval = "task_interval"
    private static let keyTaskMinDistance = "task_min_distance"
    private static let keyTaskHighAccuracy = "task_high_accuracy"
    private static let keyTaskMaxAccuracy = "task_max_accuracy"

    // Work hour tracking keys
    private static let keyWorkHourActive = "work_hour_active"
    private static let keyWorkHourEngineerId = "work_hour_engineer_id"
    private static let keyWorkHourUploadInterval = "work_hour_upload_interval"
    private static let keyWorkHourServerUrl = "work_hour_server_url"
    private static let keyWorkHourAuthToken = "work_hour_auth_token"
    private static let keyWorkHourOfflineQueue = "work_hour_offline_queue"
    // Stored as a string: KeyValueStore.double returns 0 for a missing key, and 0 is
    // a meaningful value here (heartbeat mode).
    private static let keyWorkHourMinDistance = "work_hour_min_distance"

    static let defaultIntervalSeconds: Double = 3.0
    static let defaultMinDistanceMeters: Double = 10.0
    static let defaultMaxAccuracyMeters: Double = 30.0
    static let defaultUploadIntervalSeconds: Double = 300.0
    static let defaultWorkHourMinDistanceMeters: Double = 50.0

    private let store: KeyValueStore

    init(store: KeyValueStore) {
        self.store = store
    }

    // MARK: Task tracking state

    struct TaskTrackingState {
        let reference: String
        let interval: Double
        let minDistance: Double
        let highAccuracy: Bool
        let maxAccuracy: Double
    }

    func saveTaskTracking(_ state: TaskTrackingState) {
        store.set(true, forKey: Self.keyTaskActive)
        store.set(state.reference, forKey: Self.keyTaskReference)
        store.set(state.interval, forKey: Self.keyTaskInterval)
        store.set(state.minDistance, forKey: Self.keyTaskMinDistance)
        store.set(state.highAccuracy, forKey: Self.keyTaskHighAccuracy)
        store.set(state.maxAccuracy, forKey: Self.keyTaskMaxAccuracy)
    }

    func clearTaskTracking() {
        store.set(false, forKey: Self.keyTaskActive)
        store.removeObject(forKey: Self.keyTaskReference)
    }

    var isTaskTrackingActive: Bool {
        store.bool(forKey: Self.keyTaskActive)
    }

    /// The persisted task session, or nil when tracking is not active or the
    /// reference is missing/blank (guards against resuming a bogus session).
    func getTaskTracking() -> TaskTrackingState? {
        guard isTaskTrackingActive,
              let reference = store.string(forKey: Self.keyTaskReference),
              !reference.trimmingCharacters(in: .whitespaces).isEmpty else {
            return nil
        }
        let interval = store.double(forKey: Self.keyTaskInterval)
        let minDistance = store.double(forKey: Self.keyTaskMinDistance)
        let maxAccuracy = store.double(forKey: Self.keyTaskMaxAccuracy)
        return TaskTrackingState(
            reference: reference,
            interval: interval > 0 ? interval : Self.defaultIntervalSeconds,
            minDistance: minDistance > 0 ? minDistance : Self.defaultMinDistanceMeters,
            highAccuracy: store.bool(forKey: Self.keyTaskHighAccuracy),
            maxAccuracy: maxAccuracy > 0 ? maxAccuracy : Self.defaultMaxAccuracyMeters
        )
    }

    // MARK: Work hour tracking state

    struct WorkHourState {
        let engineerId: String
        let uploadInterval: Double
        let serverUrl: String
        let authToken: String?
        let enableOfflineQueue: Bool
        /// Meters of movement between queued fixes; 0 queues one every interval (heartbeat).
        var minDistance: Double = TrackingStateStore.defaultWorkHourMinDistanceMeters
    }

    func saveWorkHourTracking(_ state: WorkHourState) {
        store.set(true, forKey: Self.keyWorkHourActive)
        store.set(state.engineerId, forKey: Self.keyWorkHourEngineerId)
        store.set(state.uploadInterval, forKey: Self.keyWorkHourUploadInterval)
        store.set(state.serverUrl, forKey: Self.keyWorkHourServerUrl)
        store.set(state.authToken, forKey: Self.keyWorkHourAuthToken)
        store.set(state.enableOfflineQueue, forKey: Self.keyWorkHourOfflineQueue)
        store.set(String(state.minDistance), forKey: Self.keyWorkHourMinDistance)
    }

    func clearWorkHourTracking() {
        store.set(false, forKey: Self.keyWorkHourActive)
        store.removeObject(forKey: Self.keyWorkHourEngineerId)
        store.removeObject(forKey: Self.keyWorkHourServerUrl)
        store.removeObject(forKey: Self.keyWorkHourAuthToken)
    }

    var isWorkHourTrackingActive: Bool {
        store.bool(forKey: Self.keyWorkHourActive)
    }

    /// The persisted work-hour session, or nil when inactive or missing required fields.
    func getWorkHourTracking() -> WorkHourState? {
        guard isWorkHourTrackingActive,
              let engineerId = store.string(forKey: Self.keyWorkHourEngineerId),
              let serverUrl = store.string(forKey: Self.keyWorkHourServerUrl) else {
            return nil
        }
        let uploadInterval = store.double(forKey: Self.keyWorkHourUploadInterval)
        return WorkHourState(
            engineerId: engineerId,
            uploadInterval: uploadInterval > 0 ? uploadInterval : Self.defaultUploadIntervalSeconds,
            serverUrl: serverUrl,
            authToken: store.string(forKey: Self.keyWorkHourAuthToken),
            enableOfflineQueue: store.bool(forKey: Self.keyWorkHourOfflineQueue),
            minDistance: store.string(forKey: Self.keyWorkHourMinDistance).flatMap(Double.init)
                ?? Self.defaultWorkHourMinDistanceMeters
        )
    }
}
