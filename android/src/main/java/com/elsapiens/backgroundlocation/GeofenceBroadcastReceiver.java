package com.elsapiens.backgroundlocation;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Receives region crossings from Play Services.
 *
 * Declared in the manifest rather than registered at runtime, because the whole
 * point is to be reachable when the app is not running: Android starts a fresh
 * process for this broadcast, and a receiver registered by a live plugin would
 * not exist in it.
 *
 * That is also why the notification is posted here and unconditionally. It is
 * the only part the user actually sees, and it must not depend on the webview
 * booting — which, in a process started solely to deliver this broadcast, it
 * usually has not.
 */
public class GeofenceBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "GeofenceReceiver";
    private static final String CHANNEL_ID = "geofence_alerts";

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null) {
            return;
        }
        if (event.hasError()) {
            Log.w(TAG, "geofencing event error code " + event.getErrorCode());
            return;
        }

        String transition = transitionName(event.getGeofenceTransition());
        if (transition == null) {
            return;
        }

        List<Geofence> triggered = event.getTriggeringGeofences();
        if (triggered == null || triggered.isEmpty()) {
            return;
        }

        GeofenceManager manager = new GeofenceManager(context, new SharedPrefsKeyValueStore(context));
        Location fix = event.getTriggeringLocation();
        BackgroundLocationPlugin plugin = BackgroundLocationPlugin.getInstance();

        for (Geofence geofence : triggered) {
            String id = geofence.getRequestId();
            JSONObject definition = manager.definitionFor(id);
            if (definition == null) {
                // A region nothing knows about any more — drop it rather than
                // keep being woken for something no one will handle.
                manager.remove(id);
                continue;
            }

            JSONObject payload = buildTransition(id, transition, fix);
            if (payload == null) {
                continue;
            }

            // A direction-specific message wins over the general one.
            JSONObject notification = definition.optJSONObject(
                    "enter".equals(transition) ? "enterNotification" : "exitNotification");
            if (notification == null) {
                notification = definition.optJSONObject("notification");
            }
            if (notification != null) {
                post(context, id, notification);
            }

            // Then the server, natively: this is the path that has to work with
            // the app dead. WorkManager delivers it, retrying until online.
            JSONObject report = definition.optJSONObject("report");
            if (report != null) {
                GeofenceReporter.enqueue(context, id, report, payload);
            }

            if (plugin != null && plugin.hasGeofenceListener()) {
                plugin.pushGeofenceTransitionToCapacitor(payload, false);
            } else {
                manager.bufferTransition(payload);
            }
        }
    }

    private static String transitionName(int transition) {
        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER
                || transition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            return "enter";
        }
        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            return "exit";
        }
        return null;
    }

    private static JSONObject buildTransition(String id, String transition, Location fix) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("id", id);
            payload.put("transition", transition);
            payload.put("timestamp", System.currentTimeMillis());
            if (fix != null) {
                payload.put("latitude", fix.getLatitude());
                payload.put("longitude", fix.getLongitude());
                payload.put("accuracy", fix.getAccuracy());
            }
            return payload;
        } catch (JSONException e) {
            Log.w(TAG, "could not build transition payload", e);
            return null;
        }
    }

    private static void post(Context context, String id, JSONObject spec) {
        String title = spec.optString("title", null);
        String body = spec.optString("body", null);
        if (title == null || body == null) {
            return;
        }
        String channelId = spec.optString("channelId", CHANNEL_ID);

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && CHANNEL_ID.equals(channelId)
                && manager.getNotificationChannel(CHANNEL_ID) == null) {
            // Only the plugin's own channel is created here. A channelId the
            // host app passed is the host app's to create — creating it with
            // this plugin's defaults would override the importance and sound
            // the app chose.
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    CHANNEL_ID, "Location Reminders", NotificationManager.IMPORTANCE_HIGH));
        }

        Notification notification = new NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(launchIntent(context))
                .build();

        manager.notify(("geofence." + id).hashCode(), notification);
    }

    private static android.app.PendingIntent launchIntent(Context context) {
        Intent launch = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) {
            return null;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        }
        return android.app.PendingIntent.getActivity(context, 0, launch, flags);
    }
}
