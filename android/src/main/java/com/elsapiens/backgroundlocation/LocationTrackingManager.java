package com.elsapiens.backgroundlocation;

import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.util.Log;

/**
 * Orchestrates the lifecycle of both tracking modes.
 *
 * Validation and state persistence happen here; the actual fix collection lives in the
 * two foreground services, which are the SINGLE pipeline for their mode. (Previously a
 * second, in-process pipeline wrote the same fixes to the same table concurrently,
 * duplicating rows, and work-hour tracking never started its service at all — it died
 * with the app process.)
 */
public class LocationTrackingManager {
    private static final String TAG = "LocationTrackingManager";

    private final Context context;
    private final LocationPermissionManager permissionManager;
    private final TrackingStateStore stateStore;

    public LocationTrackingManager(Context context, LocationPermissionManager permissionManager,
            TrackingStateStore stateStore) {
        this.context = context;
        this.permissionManager = permissionManager;
        this.stateStore = stateStore;
    }

    /**
     * Start task-based location tracking.
     *
     * Requires foreground location permission and enabled location services. Missing
     * background permission does NOT block the start — the service keeps collecting
     * fixes while it lives — but it is reported in the result so the app can prompt
     * the user for "Allow all the time".
     */
    public TrackingStartResult startTaskTracking(TrackingStateStore.TaskTrackingState state) {
        Log.d(TAG, "Starting task tracking for reference: " + state.reference);

        if (state.reference == null || state.reference.trim().isEmpty()) {
            return TrackingStartResult.failure(ErrorCodes.MISSING_PARAMETER, "reference must not be empty");
        }
        if (!permissionManager.hasForegroundLocationPermission()) {
            return TrackingStartResult.failure(ErrorCodes.PERMISSION_DENIED,
                "Location permission not granted. Call requestPermissions() first.");
        }
        if (!isLocationEnabled()) {
            return TrackingStartResult.failure(ErrorCodes.LOCATION_SERVICES_DISABLED,
                "Device location services are disabled. Ask the user to enable them "
                    + "(openLocationSettings() opens the settings screen).");
        }

        // Persist the session before starting so restart paths know what to resume.
        stateStore.saveTaskTracking(state);

        Intent serviceIntent = new Intent(context, BackgroundLocationService.class);
        serviceIntent.putExtra(BackgroundLocationService.EXTRA_REFERENCE, state.reference);
        serviceIntent.putExtra(BackgroundLocationService.EXTRA_INTERVAL, state.interval);
        serviceIntent.putExtra(BackgroundLocationService.EXTRA_MIN_DISTANCE, state.minDistance);
        serviceIntent.putExtra(BackgroundLocationService.EXTRA_HIGH_ACCURACY, state.highAccuracy);
        serviceIntent.putExtra(BackgroundLocationService.EXTRA_MAX_ACCURACY, state.maxAccuracy);

        try {
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException when started from background.
            stateStore.clearTaskTracking();
            Log.e(TAG, "Error starting task tracking service", e);
            return TrackingStartResult.failure(ErrorCodes.SERVICE_START_FAILED,
                "Could not start the tracking service: " + e.getMessage());
        }

        return TrackingStartResult.success(permissionManager.hasBackgroundLocationPermission());
    }

    /** Stop task tracking. Idempotent — stopping when inactive is not an error. */
    public void stopTaskTracking() {
        Log.d(TAG, "Stopping task tracking");
        stateStore.clearTaskTracking();
        try {
            context.stopService(new Intent(context, BackgroundLocationService.class));
        } catch (Exception e) {
            Log.w(TAG, "Error stopping task tracking service", e);
        }
    }

    /**
     * Start work-hour tracking. Same permission model as task tracking; the dedicated
     * foreground service samples and uploads even when the app is backgrounded.
     */
    public TrackingStartResult startWorkHourTracking(TrackingStateStore.WorkHourState state) {
        Log.d(TAG, "Starting work hour tracking for engineer: " + state.engineerId);

        if (state.engineerId == null || state.engineerId.isEmpty()) {
            return TrackingStartResult.failure(ErrorCodes.MISSING_PARAMETER, "engineerId must not be empty");
        }
        if (state.serverUrl == null || state.serverUrl.isEmpty()) {
            return TrackingStartResult.failure(ErrorCodes.MISSING_PARAMETER, "serverUrl must not be empty");
        }
        if (!permissionManager.hasForegroundLocationPermission()) {
            return TrackingStartResult.failure(ErrorCodes.PERMISSION_DENIED,
                "Location permission not granted. Call requestPermissions() first.");
        }
        if (!isLocationEnabled()) {
            return TrackingStartResult.failure(ErrorCodes.LOCATION_SERVICES_DISABLED,
                "Device location services are disabled.");
        }

        stateStore.saveWorkHourTracking(state);

        Intent serviceIntent = new Intent(context, WorkHourLocationService.class);
        serviceIntent.putExtra(WorkHourLocationService.EXTRA_ENGINEER_ID, state.engineerId);
        serviceIntent.putExtra(WorkHourLocationService.EXTRA_UPLOAD_INTERVAL, state.uploadInterval);
        serviceIntent.putExtra(WorkHourLocationService.EXTRA_SERVER_URL, state.serverUrl);
        serviceIntent.putExtra(WorkHourLocationService.EXTRA_AUTH_TOKEN, state.authToken);
        serviceIntent.putExtra(WorkHourLocationService.EXTRA_OFFLINE_QUEUE, state.enableOfflineQueue);
        serviceIntent.putExtra(WorkHourLocationService.EXTRA_MIN_DISTANCE, state.minDistance);

        try {
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            stateStore.clearWorkHourTracking();
            Log.e(TAG, "Error starting work hour tracking service", e);
            return TrackingStartResult.failure(ErrorCodes.SERVICE_START_FAILED,
                "Could not start the work-hour service: " + e.getMessage());
        }

        return TrackingStartResult.success(permissionManager.hasBackgroundLocationPermission());
    }

    /** Stop work-hour tracking. Idempotent. */
    public void stopWorkHourTracking() {
        Log.d(TAG, "Stopping work hour tracking");
        stateStore.clearWorkHourTracking();
        try {
            context.stopService(new Intent(context, WorkHourLocationService.class));
        } catch (Exception e) {
            Log.w(TAG, "Error stopping work hour tracking service", e);
        }
    }

    public boolean isTaskTrackingActive() {
        return stateStore.isTaskTrackingActive();
    }

    public boolean isWorkHourTrackingActive() {
        return stateStore.isWorkHourTrackingActive();
    }

    public String getCurrentTaskReference() {
        TrackingStateStore.TaskTrackingState state = stateStore.getTaskTracking();
        return state != null ? state.reference : null;
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        return locationManager != null
            && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    /** Result of starting a tracking operation, carrying a typed error code on failure. */
    public static class TrackingStartResult {
        public final boolean success;
        public final String code;
        public final String message;
        public final boolean backgroundLocationGranted;

        private TrackingStartResult(boolean success, String code, String message, boolean backgroundLocationGranted) {
            this.success = success;
            this.code = code;
            this.message = message;
            this.backgroundLocationGranted = backgroundLocationGranted;
        }

        public static TrackingStartResult success(boolean backgroundLocationGranted) {
            return new TrackingStartResult(true, null, "Tracking started", backgroundLocationGranted);
        }

        public static TrackingStartResult failure(String code, String message) {
            return new TrackingStartResult(false, code, message, false);
        }
    }
}
