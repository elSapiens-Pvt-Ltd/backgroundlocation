package com.elsapiens.backgroundlocation;

import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ServiceCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Foreground service that samples location during work hours and uploads batches to
 * the configured server.
 *
 * Follows the same lifecycle contract as {@link BackgroundLocationService}: promote to
 * the foreground first, then validate — never stop without having called
 * startForeground, because that crashes the whole app.
 */
public class WorkHourLocationService extends Service {
    private static final String TAG = "WorkHourLocationService";
    private static final int NOTIFICATION_ID = 2001;

    public static final String EXTRA_ENGINEER_ID = "engineerId";
    public static final String EXTRA_UPLOAD_INTERVAL = "uploadInterval";
    public static final String EXTRA_SERVER_URL = "serverUrl";
    public static final String EXTRA_AUTH_TOKEN = "authToken";
    public static final String EXTRA_OFFLINE_QUEUE = "enableOfflineQueue";
    public static final String EXTRA_MIN_DISTANCE = "minDistance";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private WorkHourLocationUploader uploader;
    private TrackingStateStore stateStore;
    private NotificationFactory notifications;
    private LocationPermissionManager permissionManager;

    private String engineerId;
    private long uploadInterval = TrackingStateStore.DEFAULT_UPLOAD_INTERVAL_MS;
    private String serverUrl;
    private String authToken;
    private boolean enableOfflineQueue = true;
    private float minDistance = TrackingStateStore.DEFAULT_WORK_HOUR_MIN_DISTANCE_METERS;
    private boolean isForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        stateStore = new TrackingStateStore(new SharedPrefsKeyValueStore(this));
        notifications = new NotificationFactory(this);
        permissionManager = new LocationPermissionManager(this);
        promoteToForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!resolveParameters(intent)) {
            Log.w(TAG, "No work-hour parameters available; stopping service");
            stopGracefully();
            return START_NOT_STICKY;
        }

        promoteToForeground();
        if (!isForeground) {
            broadcastError(ErrorCodes.SERVICE_START_FAILED,
                "Could not promote the work-hour service to the foreground.", true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!permissionManager.hasForegroundLocationPermission()) {
            broadcastError(ErrorCodes.PERMISSION_DENIED,
                "Location permission is not granted. Request it before starting work-hour tracking.", true);
            stopGracefully();
            return START_NOT_STICKY;
        }

        if (!permissionManager.hasBackgroundLocationPermission()) {
            broadcastError(ErrorCodes.BACKGROUND_PERMISSION_DENIED,
                "Background location permission (\"Allow all the time\") is not granted. "
                    + "Work-hour tracking continues, but cannot recover if the system stops it in the background.",
                false);
        }

        setupUploader();
        if (!startLocationUpdates()) {
            stopGracefully();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private boolean resolveParameters(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_ENGINEER_ID)) {
            engineerId = intent.getStringExtra(EXTRA_ENGINEER_ID);
            uploadInterval = intent.getLongExtra(EXTRA_UPLOAD_INTERVAL, TrackingStateStore.DEFAULT_UPLOAD_INTERVAL_MS);
            serverUrl = intent.getStringExtra(EXTRA_SERVER_URL);
            authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN);
            enableOfflineQueue = intent.getBooleanExtra(EXTRA_OFFLINE_QUEUE, true);
            minDistance = intent.getFloatExtra(EXTRA_MIN_DISTANCE, TrackingStateStore.DEFAULT_WORK_HOUR_MIN_DISTANCE_METERS);
        } else {
            TrackingStateStore.WorkHourState saved = stateStore.getWorkHourTracking();
            if (saved == null) {
                return false;
            }
            engineerId = saved.engineerId;
            uploadInterval = saved.uploadInterval;
            serverUrl = saved.serverUrl;
            authToken = saved.authToken;
            enableOfflineQueue = saved.enableOfflineQueue;
            minDistance = saved.minDistance;
        }
        return engineerId != null && !engineerId.isEmpty() && serverUrl != null && !serverUrl.isEmpty();
    }

    private void promoteToForeground() {
        if (Build.VERSION.SDK_INT >= 34 && !isForeground
                && !permissionManager.hasForegroundLocationPermission()) {
            Log.e(TAG, "Cannot start foreground service without location permission on Android 14+");
            return;
        }
        try {
            notifications.createWorkHourChannel();
            startForeground(NOTIFICATION_ID, notifications.buildWorkHourNotification(null, null));
            isForeground = true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private void setupUploader() {
        if (uploader != null) {
            uploader.stop();
        }
        uploader = new WorkHourLocationUploader(engineerId, uploadInterval, serverUrl, authToken, enableOfflineQueue);
        uploader.start();
    }

    /**
     * @return true when updates were requested successfully
     */
    private boolean startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                uploadInterval)
            // 0 = a fix every interval even when stationary (a heartbeat for "last seen")
            .setMinUpdateDistanceMeters(Math.max(0f, minDistance))
            .setMaxUpdateAgeMillis(uploadInterval)
            .setWaitForAccurateLocation(false)
            .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    if (uploader != null) {
                        uploader.addLocationToQueue(location);
                    }
                }
            }
        };

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing when requesting updates", e);
            broadcastError(ErrorCodes.PERMISSION_DENIED,
                "Location permission was revoked while work-hour tracking.", true);
            return false;
        }
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private void broadcastError(String code, String message, boolean fatal) {
        Log.e(TAG, code + ": " + message);
        Intent intent = new Intent(BackgroundLocationService.ACTION_ERROR);
        intent.putExtra("code", code);
        intent.putExtra("message", message);
        intent.putExtra("source", ErrorCodes.SOURCE_WORK_HOUR_TRACKING);
        intent.putExtra("fatal", fatal);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void stopGracefully() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            isForeground = false;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        if (uploader != null) {
            uploader.stop();
            uploader = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
}
