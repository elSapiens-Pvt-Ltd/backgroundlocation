package com.elsapiens.backgroundlocation;

/**
 * Persists the desired tracking state across process death.
 *
 * The services and restart receivers consult this store to decide whether a restart is
 * wanted and with which parameters. Without it, a system-initiated restart used to run
 * with a bogus "default_reference"/"auto_restart" reference, silently corrupting the
 * recorded route. Single responsibility: state persistence only — no Android service
 * logic lives here.
 */
public class TrackingStateStore {

    // Task tracking keys
    private static final String KEY_TASK_ACTIVE = "task_active";
    private static final String KEY_TASK_REFERENCE = "task_reference";
    private static final String KEY_TASK_INTERVAL = "task_interval";
    private static final String KEY_TASK_MIN_DISTANCE = "task_min_distance";
    private static final String KEY_TASK_HIGH_ACCURACY = "task_high_accuracy";
    private static final String KEY_TASK_MAX_ACCURACY = "task_max_accuracy";
    private static final String KEY_NOTIFICATION_TITLE = "notification_title";
    private static final String KEY_NOTIFICATION_TEXT = "notification_text";

    // Work hour tracking keys
    private static final String KEY_WORK_HOUR_ACTIVE = "work_hour_active";
    private static final String KEY_WORK_HOUR_ENGINEER_ID = "work_hour_engineer_id";
    private static final String KEY_WORK_HOUR_UPLOAD_INTERVAL = "work_hour_upload_interval";
    private static final String KEY_WORK_HOUR_SERVER_URL = "work_hour_server_url";
    private static final String KEY_WORK_HOUR_AUTH_TOKEN = "work_hour_auth_token";
    private static final String KEY_WORK_HOUR_OFFLINE_QUEUE = "work_hour_offline_queue";
    private static final String KEY_WORK_HOUR_MIN_DISTANCE = "work_hour_min_distance";

    public static final long DEFAULT_INTERVAL_MS = 3000L;
    public static final float DEFAULT_MIN_DISTANCE_METERS = 10.0f;
    public static final float DEFAULT_MAX_ACCURACY_METERS = 30.0f;
    public static final long DEFAULT_UPLOAD_INTERVAL_MS = 300000L;
    public static final float DEFAULT_WORK_HOUR_MIN_DISTANCE_METERS = 50.0f;

    private final KeyValueStore store;

    public TrackingStateStore(KeyValueStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------
    // Task tracking state
    // ------------------------------------------------------------------

    public void saveTaskTracking(TaskTrackingState state) {
        store.putBoolean(KEY_TASK_ACTIVE, true);
        store.putString(KEY_TASK_REFERENCE, state.reference);
        store.putLong(KEY_TASK_INTERVAL, state.interval);
        store.putFloat(KEY_TASK_MIN_DISTANCE, state.minDistance);
        store.putBoolean(KEY_TASK_HIGH_ACCURACY, state.highAccuracy);
        store.putFloat(KEY_TASK_MAX_ACCURACY, state.maxAccuracy);
        store.putString(KEY_NOTIFICATION_TITLE, state.notificationTitle);
        store.putString(KEY_NOTIFICATION_TEXT, state.notificationText);
    }

    public void clearTaskTracking() {
        store.putBoolean(KEY_TASK_ACTIVE, false);
        store.remove(KEY_TASK_REFERENCE);
    }

    public boolean isTaskTrackingActive() {
        return store.getBoolean(KEY_TASK_ACTIVE, false);
    }

    /** Returns the persisted task state, or null when tracking is not active. */
    public TaskTrackingState getTaskTracking() {
        if (!isTaskTrackingActive()) {
            return null;
        }
        String reference = store.getString(KEY_TASK_REFERENCE, null);
        if (reference == null || reference.trim().isEmpty()) {
            return null;
        }
        return new TaskTrackingState(
            reference,
            store.getLong(KEY_TASK_INTERVAL, DEFAULT_INTERVAL_MS),
            store.getFloat(KEY_TASK_MIN_DISTANCE, DEFAULT_MIN_DISTANCE_METERS),
            store.getBoolean(KEY_TASK_HIGH_ACCURACY, true),
            store.getFloat(KEY_TASK_MAX_ACCURACY, DEFAULT_MAX_ACCURACY_METERS),
            store.getString(KEY_NOTIFICATION_TITLE, null),
            store.getString(KEY_NOTIFICATION_TEXT, null)
        );
    }

    // ------------------------------------------------------------------
    // Work hour tracking state
    // ------------------------------------------------------------------

    public void saveWorkHourTracking(WorkHourState state) {
        store.putBoolean(KEY_WORK_HOUR_ACTIVE, true);
        store.putString(KEY_WORK_HOUR_ENGINEER_ID, state.engineerId);
        store.putLong(KEY_WORK_HOUR_UPLOAD_INTERVAL, state.uploadInterval);
        store.putString(KEY_WORK_HOUR_SERVER_URL, state.serverUrl);
        store.putString(KEY_WORK_HOUR_AUTH_TOKEN, state.authToken);
        store.putBoolean(KEY_WORK_HOUR_OFFLINE_QUEUE, state.enableOfflineQueue);
        store.putFloat(KEY_WORK_HOUR_MIN_DISTANCE, state.minDistance);
    }

    public void clearWorkHourTracking() {
        store.putBoolean(KEY_WORK_HOUR_ACTIVE, false);
        store.remove(KEY_WORK_HOUR_ENGINEER_ID);
        store.remove(KEY_WORK_HOUR_SERVER_URL);
        store.remove(KEY_WORK_HOUR_AUTH_TOKEN);
    }

    public boolean isWorkHourTrackingActive() {
        return store.getBoolean(KEY_WORK_HOUR_ACTIVE, false);
    }

    /** Returns the persisted work-hour state, or null when tracking is not active. */
    public WorkHourState getWorkHourTracking() {
        if (!isWorkHourTrackingActive()) {
            return null;
        }
        String engineerId = store.getString(KEY_WORK_HOUR_ENGINEER_ID, null);
        String serverUrl = store.getString(KEY_WORK_HOUR_SERVER_URL, null);
        if (engineerId == null || serverUrl == null) {
            return null;
        }
        return new WorkHourState(
            engineerId,
            store.getLong(KEY_WORK_HOUR_UPLOAD_INTERVAL, DEFAULT_UPLOAD_INTERVAL_MS),
            serverUrl,
            store.getString(KEY_WORK_HOUR_AUTH_TOKEN, null),
            store.getBoolean(KEY_WORK_HOUR_OFFLINE_QUEUE, true),
            // Sessions saved before minDistance existed keep the old fixed 50 m
            store.getFloat(KEY_WORK_HOUR_MIN_DISTANCE, DEFAULT_WORK_HOUR_MIN_DISTANCE_METERS)
        );
    }

    // ------------------------------------------------------------------
    // Value objects
    // ------------------------------------------------------------------

    public static class TaskTrackingState {
        public final String reference;
        public final long interval;
        public final float minDistance;
        public final boolean highAccuracy;
        public final float maxAccuracy;
        public final String notificationTitle;
        public final String notificationText;

        public TaskTrackingState(String reference, long interval, float minDistance, boolean highAccuracy,
                float maxAccuracy, String notificationTitle, String notificationText) {
            this.reference = reference;
            this.interval = interval;
            this.minDistance = minDistance;
            this.highAccuracy = highAccuracy;
            this.maxAccuracy = maxAccuracy;
            this.notificationTitle = notificationTitle;
            this.notificationText = notificationText;
        }
    }

    public static class WorkHourState {
        public final String engineerId;
        public final long uploadInterval;
        public final String serverUrl;
        public final String authToken;
        public final boolean enableOfflineQueue;
        /** Meters of movement between queued fixes; 0 queues one every interval (heartbeat). */
        public final float minDistance;

        public WorkHourState(String engineerId, long uploadInterval, String serverUrl, String authToken,
                boolean enableOfflineQueue) {
            this(engineerId, uploadInterval, serverUrl, authToken, enableOfflineQueue,
                DEFAULT_WORK_HOUR_MIN_DISTANCE_METERS);
        }

        public WorkHourState(String engineerId, long uploadInterval, String serverUrl, String authToken,
                boolean enableOfflineQueue, float minDistance) {
            this.engineerId = engineerId;
            this.uploadInterval = uploadInterval;
            this.serverUrl = serverUrl;
            this.authToken = authToken;
            this.enableOfflineQueue = enableOfflineQueue;
            this.minDistance = Math.max(0f, minDistance);
        }
    }
}
