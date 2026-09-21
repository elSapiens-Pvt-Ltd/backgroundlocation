package com.elsapiens.backgroundlocation;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Posts region crossings straight to a server, from native code.
 *
 * The Android counterpart to iOS's {@code GeofenceReporter}. A crossing is
 * delivered to a process Android started solely for the broadcast; the webview
 * has usually not booted and may never boot before the process is killed, so a
 * crossing that only reaches JavaScript is one the server hears about whenever
 * the user next opens the app. Anything that must act on the crossing — an
 * automatic break, an end-of-day punch-out — needs it to leave from here.
 *
 * Every report is queued (persisted) before it is sent, and sending runs as a
 * WorkManager job with a network constraint: a crossing in a basement with no
 * signal is delivered once the phone reconnects, with the app never opened.
 * Sends are strictly in order, because the server decides breaks from the
 * sequence of exits and entries.
 */
public final class GeofenceReporter {

    private static final String TAG = "GeofenceReporter";
    static final String KEY_QUEUE = "geofence.reportQueue";
    static final int MAX_QUEUED = 100;
    /** A report rejected this many times for non-auth reasons is dropped. */
    static final int MAX_ATTEMPTS = 20;
    private static final String WORK_NAME = "geofence-report-flush";

    private static final Object LOCK = new Object();

    private GeofenceReporter() {}

    /** Queues a crossing and schedules delivery. */
    public static void enqueue(Context context, String regionId, JSONObject report, JSONObject transition) {
        String url = report.optString("url", "");
        if (url.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            KeyValueStore store = new SharedPrefsKeyValueStore(context);
            JSONArray queue = load(store);
            try {
                JSONObject entry = new JSONObject();
                entry.put("regionId", regionId);
                entry.put("url", url);
                entry.put("authToken", report.optString("authToken", ""));
                entry.put("transition", transition);
                entry.put("attempts", 0);
                queue.put(entry);
            } catch (JSONException e) {
                Log.w(TAG, "could not queue geofence report", e);
                return;
            }
            // Oldest dropped first: a queue behind a server that never answers
            // must not grow without limit.
            while (queue.length() > MAX_QUEUED) {
                queue.remove(0);
            }
            store.putString(KEY_QUEUE, queue.toString());
        }
        schedule(context);
    }

    /** Schedules a flush for as soon as there is a network. */
    public static void schedule(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(FlushWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        // APPEND_OR_REPLACE keeps one chain: a crossing arriving while a flush is
        // running queues behind it instead of racing it out of order.
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    /**
     * Sends queued reports in order. Returns true when the queue is empty,
     * false when a send failed for a reason worth retrying (the rest stay
     * queued behind it).
     */
    static boolean flush(Context context) {
        KeyValueStore store = new SharedPrefsKeyValueStore(context);
        while (true) {
            JSONObject next;
            synchronized (LOCK) {
                JSONArray queue = load(store);
                if (queue.length() == 0) {
                    return true;
                }
                next = queue.optJSONObject(0);
                if (next == null) {
                    queue.remove(0);
                    store.putString(KEY_QUEUE, queue.toString());
                    continue;
                }
            }

            int status = send(next);
            String regionId = next.optString("regionId", "");
            if (status >= 200 && status < 300) {
                removeFirst(store);
                continue;
            }
            if (status == 401 || status == 403) {
                // The credential is gone and will not come back — typically the
                // employee punched out. Nothing queued for this region can ever
                // be delivered, and watching it on would only wake the app for
                // crossings no one will receive.
                removeAllForRegion(store, regionId);
                new GeofenceManager(context, store).remove(regionId);
                continue;
            }
            // No network, a timeout, or a server error: keep it in place and
            // stop, so later reports cannot overtake it.
            recordFailedAttempt(store);
            return false;
        }
    }

    /** Returns the HTTP status, or 0 when the request never completed. */
    private static int send(JSONObject entry) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(entry.getString("url")).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "ElsapiensBackgroundLocation/1.0");
            String token = entry.optString("authToken", "");
            if (!token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            byte[] body = entry.getJSONObject("transition").toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            return connection.getResponseCode();
        } catch (Exception e) {
            Log.w(TAG, "geofence report send failed: " + e.getMessage());
            return 0;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ── Queue ────────────────────────────────────────────────────────────────

    private static JSONArray load(KeyValueStore store) {
        String raw = store.getString(KEY_QUEUE, null);
        if (raw == null || raw.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void removeFirst(KeyValueStore store) {
        synchronized (LOCK) {
            JSONArray queue = load(store);
            if (queue.length() > 0) {
                queue.remove(0);
            }
            store.putString(KEY_QUEUE, queue.toString());
        }
    }

    private static void removeAllForRegion(KeyValueStore store, String regionId) {
        synchronized (LOCK) {
            JSONArray queue = load(store);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject entry = queue.optJSONObject(i);
                if (entry != null && !regionId.equals(entry.optString("regionId"))) {
                    kept.put(entry);
                }
            }
            store.putString(KEY_QUEUE, kept.toString());
        }
    }

    private static void recordFailedAttempt(KeyValueStore store) {
        synchronized (LOCK) {
            JSONArray queue = load(store);
            JSONObject first = queue.optJSONObject(0);
            if (first == null) {
                return;
            }
            int attempts = first.optInt("attempts", 0) + 1;
            if (attempts >= MAX_ATTEMPTS) {
                queue.remove(0);
            } else {
                try {
                    first.put("attempts", attempts);
                } catch (JSONException ignored) {
                    // attempts is a plain int; put cannot fail here
                }
            }
            store.putString(KEY_QUEUE, queue.toString());
        }
    }

    /** Runs the flush off the main thread, retried with backoff by WorkManager. */
    public static class FlushWorker extends Worker {
        public FlushWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull
        @Override
        public Result doWork() {
            return flush(getApplicationContext()) ? Result.success() : Result.retry();
        }
    }
}
