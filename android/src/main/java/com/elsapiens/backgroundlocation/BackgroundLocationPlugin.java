package com.elsapiens.backgroundlocation;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Capacitor plugin for background location tracking.
 *
 * Responsibilities are delegated to focused collaborators (SOLID): permission checks
 * live in {@link LocationPermissionManager}, service lifecycle in
 * {@link LocationTrackingManager}, session persistence in {@link TrackingStateStore},
 * progressive-accuracy decisions in {@link CurrentLocationWatcher}. This class only
 * translates between the Capacitor bridge and those collaborators.
 *
 * Error contract: every reject carries a code from {@link ErrorCodes}; asynchronous
 * failures (service died, permission revoked mid-tracking, GPS switched off) surface
 * through the "error" event. The plugin itself never crashes the app on a permission
 * problem — it reports and lets the app drive the user to the right settings screen.
 */
@CapacitorPlugin(name = "BackgroundLocation", permissions = {
        @Permission(alias = BackgroundLocationPlugin.ALIAS_FOREGROUND, strings = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }),
        @Permission(alias = BackgroundLocationPlugin.ALIAS_BACKGROUND, strings = {
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        })
})
public class BackgroundLocationPlugin extends Plugin {
    private static final String TAG = "BackgroundLocationPlugin";

    static final String ALIAS_FOREGROUND = "foregroundLocation";
    static final String ALIAS_BACKGROUND = "backgroundLocation";

    private static final long DEFAULT_CURRENT_LOCATION_TIMEOUT_MS = 30_000L;

    // Singleton instance for broadcast receiver access
    private static BackgroundLocationPlugin instance;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationPermissionManager permissionManager;
    private LocationTrackingManager trackingManager;
    private LocationDataManager dataManager;
    private TrackingStateStore stateStore;
    private GeofenceManager geofenceManager;
    private SQLiteDatabaseHelper database;

    private LocationBroadcastReceiver locationReceiver;
    private LocationStateReceiver locationStateReceiver;

    // Progressive-accuracy current-location watch state
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CurrentLocationWatcher currentWatcher;
    private LocationCallback currentWatchCallback;
    private PluginCall currentWatchCall;
    private Runnable currentWatchTimeout;

    // JS-visible mirror of queued work-hour fixes (authoritative queue lives in the service)
    private final WorkHourLocationQueue workHourMirror = new WorkHourLocationQueue();

    public static BackgroundLocationPlugin getInstance() {
        return instance;
    }

    /** @deprecated use {@link #getInstance()} */
    @Deprecated
    public static BackgroundLocationPlugin getCurrentInstance() {
        return instance;
    }

    @Override
    public void load() {
        super.load();
        instance = this;
        try {
            Context context = getContext();
            database = new SQLiteDatabaseHelper(context);
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
            permissionManager = new LocationPermissionManager(context);
            dataManager = new LocationDataManager(database);
            stateStore = new TrackingStateStore(new SharedPrefsKeyValueStore(context));
            trackingManager = new LocationTrackingManager(context, permissionManager, stateStore);
            geofenceManager = new GeofenceManager(context, new SharedPrefsKeyValueStore(context));
            // Crossings queued while offline go out as soon as there is a
            // network — WorkManager holds the retry if there is none now.
            GeofenceReporter.schedule(context);
            registerLocationReceiver(context);
            Log.d(TAG, "BackgroundLocationPlugin initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error during plugin initialization", e);
        }
    }

    private void registerLocationReceiver(Context context) {
        locationReceiver = new LocationBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BackgroundLocationService.ACTION_LOCATION_UPDATE);
        filter.addAction(BackgroundLocationService.ACTION_LOCATION_DISABLED);
        filter.addAction(BackgroundLocationService.ACTION_ERROR);
        ContextCompat.registerReceiver(context, locationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void handleOnDestroy() {
        try {
            cancelCurrentWatch(null);
            Context context = getContext();
            if (locationReceiver != null) {
                try {
                    context.unregisterReceiver(locationReceiver);
                } catch (IllegalArgumentException ignored) {
                }
                locationReceiver = null;
            }
            if (locationStateReceiver != null) {
                try {
                    context.unregisterReceiver(locationStateReceiver);
                } catch (IllegalArgumentException ignored) {
                }
                locationStateReceiver = null;
            }
            // Deliberately NOT stopping the tracking services here: the bridge dies when
            // the app is swiped away, but an active tracking session must survive that.
        } catch (Exception e) {
            Log.e(TAG, "Error during plugin cleanup", e);
        } finally {
            if (instance == this) {
                instance = null;
            }
        }
        super.handleOnDestroy();
    }

    // =================================================================================
    // PERMISSIONS
    // =================================================================================

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        try {
            call.resolve(buildPermissionStatus());
        } catch (Exception e) {
            Log.e(TAG, "Error checking permissions", e);
            call.reject("Error checking permissions: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    /**
     * Requests the missing permissions, foreground first, then background.
     *
     * Android requires the two tiers to be requested separately: the foreground
     * dialog offers "While using the app"; the background request then opens the
     * app's location settings where the user can pick "Allow all the time".
     * Pass {@code permissions: ["location"]} to request only the foreground tier.
     */
    @PluginMethod
    public void requestPermissions(PluginCall call) {
        try {
            boolean wantsForeground = wantsPermission(call, "location");
            boolean wantsBackground = wantsPermission(call, "backgroundLocation");

            if (wantsForeground && !permissionManager.hasForegroundLocationPermission()) {
                requestPermissionForAlias(ALIAS_FOREGROUND, call, "foregroundPermissionCallback");
                return;
            }
            if (wantsBackground && !permissionManager.hasBackgroundLocationPermission()) {
                if (!permissionManager.hasForegroundLocationPermission()) {
                    call.reject("Foreground location permission must be granted before requesting background location.",
                        ErrorCodes.PERMISSION_DENIED);
                    return;
                }
                requestPermissionForAlias(ALIAS_BACKGROUND, call, "backgroundPermissionCallback");
                return;
            }
            call.resolve(buildPermissionStatus());
        } catch (Exception e) {
            Log.e(TAG, "Error requesting permissions", e);
            call.reject("Error requesting permissions: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PermissionCallback
    private void foregroundPermissionCallback(PluginCall call) {
        boolean wantsBackground = wantsPermission(call, "backgroundLocation");
        if (wantsBackground
                && permissionManager.hasForegroundLocationPermission()
                && !permissionManager.hasBackgroundLocationPermission()) {
            requestPermissionForAlias(ALIAS_BACKGROUND, call, "backgroundPermissionCallback");
            return;
        }
        call.resolve(buildPermissionStatus());
    }

    @PermissionCallback
    private void backgroundPermissionCallback(PluginCall call) {
        call.resolve(buildPermissionStatus());
    }

    private boolean wantsPermission(PluginCall call, String name) {
        try {
            List<String> requested = call.getArray("permissions") != null
                ? call.getArray("permissions").toList()
                : null;
            return requested == null || requested.isEmpty() || requested.contains(name);
        } catch (Exception e) {
            return true;
        }
    }

    private JSObject buildPermissionStatus() {
        JSObject result = new JSObject();
        result.put("location", permissionManager.hasForegroundLocationPermission()
            ? "granted" : aliasState(ALIAS_FOREGROUND));
        result.put("backgroundLocation", permissionManager.hasBackgroundLocationPermission()
            ? "granted" : aliasState(ALIAS_BACKGROUND));
        result.put("accuracy", permissionManager.getGrantedAccuracy());
        result.put("foregroundService", permissionManager.hasForegroundServicePermission() ? "granted" : "denied");
        return result;
    }

    private String aliasState(String alias) {
        PermissionState state = getPermissionState(alias);
        return state != null ? state.toString() : "prompt";
    }

    @PluginMethod
    public void isLocationServiceEnabled(PluginCall call) {
        try {
            call.resolve(isLocationEnabled());
        } catch (Exception e) {
            Log.e(TAG, "Error checking location service status", e);
            call.reject("Error checking location service status: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    /**
     * Opens the app's system settings page — the screen where the user can change the
     * location permission to "Allow all the time" after a background-permission denial.
     */
    @PluginMethod
    public void openLocationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error opening app settings", e);
            call.reject("Error opening app settings: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    /**
     * Opens the device location-services screen — for the LOCATION_SERVICES_DISABLED
     * case where GPS itself is switched off.
     */
    @PluginMethod
    public void openDeviceLocationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error opening device location settings", e);
            call.reject("Error opening device location settings: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    // =================================================================================
    // TASK TRACKING
    // =================================================================================

    @PluginMethod
    public void startTracking(PluginCall call) {
        try {
            String reference = call.getString("reference");
            if (reference == null || reference.trim().isEmpty()) {
                call.reject("Missing required 'reference' parameter", ErrorCodes.MISSING_PARAMETER);
                return;
            }

            TrackingStateStore.TaskTrackingState state = new TrackingStateStore.TaskTrackingState(
                reference,
                call.getLong("interval", TrackingStateStore.DEFAULT_INTERVAL_MS),
                call.getFloat("minDistance", TrackingStateStore.DEFAULT_MIN_DISTANCE_METERS),
                Boolean.TRUE.equals(call.getBoolean("highAccuracy", true)),
                call.getFloat("maxAccuracy", TrackingStateStore.DEFAULT_MAX_ACCURACY_METERS),
                call.getString("notificationTitle"),
                call.getString("notificationText")
            );

            LocationTrackingManager.TrackingStartResult result = trackingManager.startTaskTracking(state);
            if (result.success) {
                JSObject data = new JSObject();
                data.put("backgroundLocationGranted", result.backgroundLocationGranted);
                data.put("accuracy", permissionManager.getGrantedAccuracy());
                call.resolve(data);
            } else {
                call.reject(result.message, result.code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting task tracking", e);
            call.reject("Error starting task tracking: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        try {
            trackingManager.stopTaskTracking();
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping task tracking", e);
            call.reject("Error stopping task tracking: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void getTrackingStatus(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("isTracking", trackingManager.isTaskTrackingActive());
            result.put("isWorkHourTracking", trackingManager.isWorkHourTrackingActive());
            result.put("reference", trackingManager.getCurrentTaskReference());
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Error reading tracking status: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    // =================================================================================
    // WORK HOUR TRACKING
    // =================================================================================

    @PluginMethod
    public void startWorkHourTracking(PluginCall call) {
        try {
            String engineerId = call.getString("engineerId");
            String serverUrl = call.getString("serverUrl");

            TrackingStateStore.WorkHourState state = new TrackingStateStore.WorkHourState(
                engineerId,
                call.getLong("uploadInterval", TrackingStateStore.DEFAULT_UPLOAD_INTERVAL_MS),
                serverUrl,
                call.getString("authToken"),
                Boolean.TRUE.equals(call.getBoolean("enableOfflineQueue", true))
            );

            LocationTrackingManager.TrackingStartResult result = trackingManager.startWorkHourTracking(state);
            if (result.success) {
                JSObject data = new JSObject();
                data.put("backgroundLocationGranted", result.backgroundLocationGranted);
                call.resolve(data);
            } else {
                call.reject(result.message, result.code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting work hour tracking", e);
            call.reject("Error starting work hour tracking: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void stopWorkHourTracking(PluginCall call) {
        try {
            trackingManager.stopWorkHourTracking();
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping work hour tracking", e);
            call.reject("Error stopping work hour tracking: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void isWorkHourTrackingActive(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("active", trackingManager.isWorkHourTrackingActive());
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Error checking work hour tracking status: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void getQueuedWorkHourLocations(PluginCall call) {
        try {
            com.getcapacitor.JSArray locations = new com.getcapacitor.JSArray();
            for (WorkHourLocationData location : workHourMirror.snapshot()) {
                JSObject locationObj = new JSObject();
                locationObj.put("latitude", location.latitude);
                locationObj.put("longitude", location.longitude);
                locationObj.put("accuracy", location.accuracy);
                locationObj.put("timestamp", location.timestamp);
                locationObj.put("engineerId", location.engineerId);
                locations.put(locationObj);
            }
            JSObject result = new JSObject();
            result.put("locations", locations);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Error getting queued work hour locations: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void clearQueuedWorkHourLocations(PluginCall call) {
        workHourMirror.clear();
        call.resolve();
    }

    // =================================================================================
    // CURRENT LOCATION (single-shot and progressive accuracy)
    // =================================================================================

    /**
     * Without {@code targetAccuracy}: resolves with the first fresh fix.
     *
     * With {@code targetAccuracy} (meters): streams every incoming fix as a
     * "currentLocation" event (so the UI can show the position refining live) until a
     * fix meets the target accuracy — that fix resolves the call. On timeout the best
     * fix seen so far is resolved with {@code timedOut: true}, or the call rejects
     * with LOCATION_UNAVAILABLE when nothing usable arrived at all.
     */
    @PluginMethod
    public void getCurrentLocation(PluginCall call) {
        if (!permissionManager.hasForegroundLocationPermission()) {
            call.reject("Location permission not granted", ErrorCodes.PERMISSION_DENIED);
            return;
        }
        try {
            if (!isLocationEnabled().getBoolean("enabled")) {
                call.reject("Device location services are disabled", ErrorCodes.LOCATION_SERVICES_DISABLED);
                return;
            }
        } catch (Exception ignored) {
        }

        Float targetAccuracy = call.getFloat("targetAccuracy");
        long timeout = call.getLong("timeout", DEFAULT_CURRENT_LOCATION_TIMEOUT_MS);

        if (targetAccuracy == null) {
            getSingleShotLocation(call, timeout);
        } else {
            startProgressiveWatch(call, targetAccuracy, timeout);
        }
    }

    /** Cancels an in-flight progressive getCurrentLocation() request, if any. */
    @PluginMethod
    public void cancelCurrentLocationRequest(PluginCall call) {
        cancelCurrentWatch("Cancelled by cancelCurrentLocationRequest()");
        call.resolve();
    }

    private void getSingleShotLocation(PluginCall call, long timeout) {
        try {
            CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setDurationMillis(timeout)
                .setMaxUpdateAgeMillis(5000)
                .build();
            fusedLocationClient.getCurrentLocation(request, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        call.resolve(locationToJSObject(location, false, false));
                    } else {
                        call.reject("Failed to get current location", ErrorCodes.LOCATION_UNAVAILABLE);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error getting current location", e);
                    call.reject("Error getting current location: " + e.getMessage(), ErrorCodes.LOCATION_UNAVAILABLE);
                });
        } catch (SecurityException e) {
            call.reject("Location permission not granted", ErrorCodes.PERMISSION_DENIED);
        } catch (Exception e) {
            Log.e(TAG, "Error in getCurrentLocation", e);
            call.reject("Error getting current location: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    private synchronized void startProgressiveWatch(PluginCall call, float targetAccuracy, long timeout) {
        cancelCurrentWatch("Superseded by a newer getCurrentLocation() request");

        CurrentLocationWatcher watcher =
            new CurrentLocationWatcher(targetAccuracy, timeout, System.currentTimeMillis());
        currentWatcher = watcher;
        currentWatchCall = call;

        currentWatchCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    handleWatchFix(watcher, location);
                }
            }
        };

        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateAgeMillis(2000)
            .build();

        try {
            fusedLocationClient.requestLocationUpdates(request, currentWatchCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            clearWatchState();
            call.reject("Location permission not granted", ErrorCodes.PERMISSION_DENIED);
            return;
        }

        currentWatchTimeout = () -> finishWatchOnTimeout(watcher);
        mainHandler.postDelayed(currentWatchTimeout, watcher.getTimeoutMs());
    }

    private synchronized void handleWatchFix(CurrentLocationWatcher watcher, Location location) {
        if (watcher != currentWatcher) {
            return; // A newer request replaced this watch.
        }
        CurrentLocationWatcher.Fix fix = new CurrentLocationWatcher.Fix(
            location.getLatitude(), location.getLongitude(), location.getAccuracy(),
            location.getAltitude(), location.getSpeed(), location.getBearing(), location.getTime());

        CurrentLocationWatcher.Decision decision = watcher.onFix(fix);
        if (decision == CurrentLocationWatcher.Decision.IGNORE) {
            return;
        }

        boolean isFinal = decision == CurrentLocationWatcher.Decision.COMPLETE;
        JSObject event = fixToJSObject(fix, isFinal, false);
        event.put("targetAccuracy", watcher.getTargetAccuracyMeters());
        notifyListeners("currentLocation", event);

        if (isFinal) {
            PluginCall call = currentWatchCall;
            stopWatchUpdates();
            clearWatchState();
            if (call != null) {
                call.resolve(fixToJSObject(fix, true, false));
            }
        }
    }

    private synchronized void finishWatchOnTimeout(CurrentLocationWatcher watcher) {
        if (watcher != currentWatcher) {
            return;
        }
        PluginCall call = currentWatchCall;
        CurrentLocationWatcher.Fix best = watcher.getBest();
        stopWatchUpdates();
        clearWatchState();
        if (call == null) {
            return;
        }
        if (best != null) {
            JSObject result = fixToJSObject(best, true, true);
            notifyListeners("currentLocation", result);
            call.resolve(result);
        } else {
            call.reject("Could not obtain a location fix within the timeout", ErrorCodes.LOCATION_UNAVAILABLE);
        }
    }

    private synchronized void cancelCurrentWatch(String reason) {
        if (currentWatcher == null) {
            return;
        }
        PluginCall pending = currentWatchCall;
        stopWatchUpdates();
        clearWatchState();
        if (pending != null && reason != null) {
            pending.reject(reason, ErrorCodes.CANCELLED);
        }
    }

    private void stopWatchUpdates() {
        if (currentWatchCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(currentWatchCallback);
        }
        if (currentWatchTimeout != null) {
            mainHandler.removeCallbacks(currentWatchTimeout);
        }
    }

    private void clearWatchState() {
        currentWatcher = null;
        currentWatchCall = null;
        currentWatchCallback = null;
        currentWatchTimeout = null;
    }

    private JSObject fixToJSObject(CurrentLocationWatcher.Fix fix, boolean isFinal, boolean timedOut) {
        JSObject data = new JSObject();
        data.put("latitude", fix.latitude);
        data.put("longitude", fix.longitude);
        data.put("accuracy", fix.accuracy);
        data.put("altitude", fix.altitude);
        data.put("speed", fix.speed);
        data.put("heading", fix.heading);
        data.put("timestamp", fix.timestamp);
        data.put("isFinal", isFinal);
        data.put("timedOut", timedOut);
        return data;
    }

    private JSObject locationToJSObject(Location location, boolean isFinal, boolean timedOut) {
        JSObject data = new JSObject();
        data.put("latitude", location.getLatitude());
        data.put("longitude", location.getLongitude());
        data.put("accuracy", location.getAccuracy());
        data.put("altitude", location.getAltitude());
        data.put("speed", location.getSpeed());
        data.put("heading", location.getBearing());
        data.put("timestamp", location.getTime());
        data.put("isFinal", isFinal);
        data.put("timedOut", timedOut);
        return data;
    }

    // =================================================================================
    // STORED LOCATION DATA
    // =================================================================================

    @PluginMethod
    public void getStoredLocations(PluginCall call) {
        try {
            String reference = call.getString("reference");
            if (reference == null || reference.trim().isEmpty()) {
                call.reject("Missing required 'reference' parameter", ErrorCodes.MISSING_PARAMETER);
                return;
            }
            JSObject result = new JSObject();
            result.put("locations", database.getLocationsForReference(reference));
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Error getting stored locations", e);
            call.reject("Error getting stored locations: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void clearStoredLocations(PluginCall call) {
        try {
            dataManager.clearStoredLocations(null);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing stored locations", e);
            call.reject("Error clearing stored locations: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void getLastLocation(PluginCall call) {
        try {
            String reference = call.getString("reference");
            if (reference == null || reference.trim().isEmpty()) {
                call.reject("Missing required 'reference' parameter", ErrorCodes.MISSING_PARAMETER);
                return;
            }
            LocationItem location = database.getLastLocation(reference);
            if (location != null) {
                JSObject result = dataManager.locationItemToJSObject(location);
                notifyListeners("locationUpdate", result);
                call.resolve(result);
            } else {
                call.reject("No location found for reference: " + reference, ErrorCodes.NOT_FOUND);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting last location", e);
            call.reject("Error getting last location: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    // =================================================================================
    // LOCATION SERVICES STATUS
    // =================================================================================

    /**
     * Starts monitoring the device location-services toggle. Reading provider state
     * needs no runtime permission, so this works before permissions are granted —
     * useful for showing an "enable location" banner on first launch.
     */
    @PluginMethod
    public void startLocationStatusTracking(PluginCall call) {
        try {
            if (locationStateReceiver == null) {
                locationStateReceiver = new LocationStateReceiver();
                IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
                getContext().registerReceiver(locationStateReceiver, filter);
            }
            try {
                pushLocationStateToCapacitor(isLocationEnabled().getBoolean("enabled"));
            } catch (Exception e) {
                pushLocationStateToCapacitor(false);
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error starting location status tracking", e);
            call.reject("Error starting location status tracking: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void stopLocationStatusTracking(PluginCall call) {
        try {
            if (locationStateReceiver != null) {
                getContext().unregisterReceiver(locationStateReceiver);
                locationStateReceiver = null;
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location status tracking", e);
            call.reject("Error stopping location status tracking: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    public JSObject isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        boolean enabled = locationManager != null
            && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        JSObject data = new JSObject();
        data.put("enabled", enabled);
        return data;
    }

    // =================================================================================
    // EVENT BRIDGES (called by receivers and the uploader)
    // =================================================================================

    public void pushLocationStateToCapacitor(boolean status) {
        JSObject data = new JSObject();
        data.put("enabled", status);
        notifyListeners("locationStatus", data);
    }

    public void pushUpdateToCapacitor(LocationItem locationItem) {
        JSObject result = new JSObject();
        result.put("reference", locationItem.reference);
        result.put("index", locationItem.index);
        result.put("latitude", locationItem.latitude);
        result.put("longitude", locationItem.longitude);
        result.put("altitude", locationItem.altitude);
        result.put("accuracy", locationItem.accuracy);
        result.put("speed", locationItem.speed);
        result.put("heading", locationItem.heading);
        result.put("altitudeAccuracy", locationItem.altitudeAccuracy);
        result.put("totalDistance", locationItem.totalDistance);
        result.put("timestamp", locationItem.timestamp);
        notifyListeners("locationUpdate", result);
    }

    /** Forward a typed error raised by a service to the JavaScript "error" event. */
    public void pushErrorToCapacitor(String code, String message, String source, boolean fatal) {
        JSObject data = new JSObject();
        data.put("code", code != null ? code : ErrorCodes.INTERNAL_ERROR);
        data.put("message", message != null ? message : "Unknown error");
        data.put("source", source != null ? source : ErrorCodes.SOURCE_TASK_TRACKING);
        data.put("fatal", fatal);
        notifyListeners("error", data);
    }

    /** Called by the uploader whenever a fix enters the work-hour queue. */
    public void notifyWorkHourLocationQueued(WorkHourLocationData location) {
        workHourMirror.add(location);
        JSObject data = new JSObject();
        data.put("latitude", location.latitude);
        data.put("longitude", location.longitude);
        data.put("accuracy", location.accuracy);
        data.put("timestamp", location.timestamp);
        data.put("engineerId", location.engineerId);
        notifyListeners("workHourLocationUpdate", data);
    }

    /** Called by the uploader after an upload attempt for a batch. */
    public void notifyWorkHourUploadResult(List<WorkHourLocationData> batch, boolean success, String error) {
        if (success) {
            workHourMirror.removeAll(new ArrayList<>(batch));
        }
        JSObject data = new JSObject();
        data.put("success", success);
        data.put("count", batch.size());
        if (error != null) {
            data.put("error", error);
        }
        notifyListeners("workHourLocationUploaded", data);
    }


    // =================================================================================
    // GEOFENCING
    // =================================================================================

    @PluginMethod
    public void addGeofence(PluginCall call) {
        String id = call.getString("id");
        Double latitude = call.getDouble("latitude");
        Double longitude = call.getDouble("longitude");
        Double radius = call.getDouble("radius");
        if (id == null || id.isEmpty() || latitude == null || longitude == null || radius == null) {
            call.reject("id, latitude, longitude and radius are required", ErrorCodes.MISSING_PARAMETER);
            return;
        }
        if (!permissionManager.hasBackgroundLocationPermission()) {
            // Registering anyway would produce a watch that never fires once the
            // app is backgrounded — a silent no-op is worse than a refusal the
            // caller can act on.
            call.reject("\"Allow all the time\" location permission is required to monitor a region",
                    ErrorCodes.BACKGROUND_PERMISSION_DENIED);
            return;
        }

        try {
            JSONObject definition = new JSONObject();
            definition.put("id", id);
            definition.put("latitude", latitude);
            definition.put("longitude", longitude);
            definition.put("radius", radius);
            definition.put("notifyOnEntry", call.getBoolean("notifyOnEntry", true));
            definition.put("notifyOnExit", call.getBoolean("notifyOnExit", false));
            // The general notification, then direction-specific ones that win
            // over it: leaving and coming back usually need saying differently.
            for (String key : new String[] {"notification", "enterNotification", "exitNotification"}) {
                JSObject spec = call.getObject(key);
                if (spec != null && spec.getString("title") != null && spec.getString("body") != null) {
                    definition.put(key, new JSONObject(spec.toString()));
                }
            }
            // Where crossings are posted natively — see GeofenceReporter.
            JSObject report = call.getObject("report");
            if (report != null && report.getString("url") != null && !report.getString("url").isEmpty()) {
                JSONObject reportSpec = new JSONObject();
                reportSpec.put("url", report.getString("url"));
                String token = report.getString("authToken");
                if (token != null) {
                    reportSpec.put("authToken", token);
                }
                definition.put("report", reportSpec);
            }
            geofenceManager.add(definition);
            call.resolve();
        } catch (IllegalStateException e) {
            call.reject(e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        } catch (Exception e) {
            Log.e(TAG, "addGeofence failed", e);
            call.reject("could not register the region: " + e.getMessage(), ErrorCodes.INTERNAL_ERROR);
        }
    }

    @PluginMethod
    public void removeGeofence(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isEmpty()) {
            call.reject("id is required", ErrorCodes.MISSING_PARAMETER);
            return;
        }
        geofenceManager.remove(id);
        call.resolve();
    }

    @PluginMethod
    public void removeAllGeofences(PluginCall call) {
        geofenceManager.removeAll();
        call.resolve();
    }

    @PluginMethod
    public void listGeofences(PluginCall call) {
        JSObject result = new JSObject();
        JSONArray geofences = new JSONArray();
        for (JSONObject definition : geofenceManager.definitions()) {
            geofences.put(withoutReportToken(definition));
        }
        result.put("geofences", geofences);
        call.resolve(result);
    }

    /**
     * The report URL only — never the token. Lets the app confirm a region is
     * still reporting without handing its credential back to JavaScript.
     */
    private static JSONObject withoutReportToken(JSONObject definition) {
        JSONObject report = definition.optJSONObject("report");
        if (report == null) {
            return definition;
        }
        try {
            JSONObject copy = new JSONObject(definition.toString());
            JSONObject safe = new JSONObject();
            safe.put("url", report.optString("url", ""));
            copy.put("report", safe);
            return copy;
        } catch (JSONException e) {
            return definition;
        }
    }

    @PluginMethod
    public void getPendingGeofenceTransitions(PluginCall call) {
        JSObject result = new JSObject();
        JSONArray transitions = new JSONArray();
        for (JSONObject transition : geofenceManager.pendingTransitions()) {
            try {
                transition.put("buffered", true);
            } catch (JSONException ignored) {
                // A payload we wrote ourselves; nothing useful to do but ship it.
            }
            transitions.put(transition);
        }
        result.put("transitions", transitions);
        call.resolve(result);
    }

    @PluginMethod
    public void clearPendingGeofenceTransitions(PluginCall call) {
        geofenceManager.clearPendingTransitions();
        call.resolve();
    }

    /** Whether JavaScript is listening right now — see GeofenceBroadcastReceiver. */
    public boolean hasGeofenceListener() {
        return hasListeners("geofenceTransition");
    }

    public void pushGeofenceTransitionToCapacitor(JSONObject transition, boolean buffered) {
        try {
            transition.put("buffered", buffered);
            notifyListeners("geofenceTransition", JSObject.fromJSONObject(transition));
        } catch (JSONException e) {
            // The payload is one we built ourselves a moment ago, so this
            // cannot happen in practice — but dropping the event is the only
            // safe response to a payload we cannot read, and re-buffering is
            // not a fallback: the crossing already posted its notification.
            Log.e(TAG, "could not deliver geofence transition", e);
        }
    }

    // =================================================================================
    // DATA CLASSES
    // =================================================================================

    /** Work hour location data container. */
    public static class WorkHourLocationData {
        public double latitude;
        public double longitude;
        public float accuracy;
        public long timestamp;
        public String engineerId;

        public WorkHourLocationData(double latitude, double longitude, float accuracy, long timestamp,
                String engineerId) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
            this.timestamp = timestamp;
            this.engineerId = engineerId;
        }
    }
}
