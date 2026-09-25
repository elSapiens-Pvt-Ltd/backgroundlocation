package com.elsapiens.backgroundlocation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class TrackingStateStoreTest {

    /** In-memory KeyValueStore so the persistence logic is testable off-device. */
    private static class InMemoryStore implements KeyValueStore {
        private final Map<String, Object> map = new HashMap<>();

        @Override
        public String getString(String key, String defaultValue) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : defaultValue;
        }

        @Override
        public void putString(String key, String value) {
            if (value == null) {
                map.remove(key);
            } else {
                map.put(key, value);
            }
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : defaultValue;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            map.put(key, value);
        }

        @Override
        public long getLong(String key, long defaultValue) {
            Object v = map.get(key);
            return v instanceof Long ? (Long) v : defaultValue;
        }

        @Override
        public void putLong(String key, long value) {
            map.put(key, value);
        }

        @Override
        public float getFloat(String key, float defaultValue) {
            Object v = map.get(key);
            return v instanceof Float ? (Float) v : defaultValue;
        }

        @Override
        public void putFloat(String key, float value) {
            map.put(key, value);
        }

        @Override
        public void remove(String key) {
            map.remove(key);
        }
    }

    private TrackingStateStore newStore() {
        return new TrackingStateStore(new InMemoryStore());
    }

    @Test
    public void inactiveByDefault() {
        TrackingStateStore store = newStore();
        assertFalse(store.isTaskTrackingActive());
        assertFalse(store.isWorkHourTrackingActive());
        assertNull(store.getTaskTracking());
        assertNull(store.getWorkHourTracking());
    }

    @Test
    public void savedTaskSessionRoundTrips() {
        TrackingStateStore store = newStore();
        store.saveTaskTracking(new TrackingStateStore.TaskTrackingState(
            "task_42", 5000L, 15f, false, 25f, "Tracking", "On the way"));

        assertTrue(store.isTaskTrackingActive());
        TrackingStateStore.TaskTrackingState state = store.getTaskTracking();
        assertEquals("task_42", state.reference);
        assertEquals(5000L, state.interval);
        assertEquals(15f, state.minDistance, 1e-6);
        assertFalse(state.highAccuracy);
        assertEquals(25f, state.maxAccuracy, 1e-6);
        assertEquals("Tracking", state.notificationTitle);
        assertEquals("On the way", state.notificationText);
    }

    @Test
    public void clearedTaskSessionYieldsNull() {
        TrackingStateStore store = newStore();
        store.saveTaskTracking(new TrackingStateStore.TaskTrackingState(
            "task_42", 5000L, 15f, true, 25f, null, null));
        store.clearTaskTracking();

        assertFalse(store.isTaskTrackingActive());
        assertNull(store.getTaskTracking());
    }

    @Test
    public void activeFlagWithoutReferenceYieldsNull() {
        // Guards the restart path: an active flag with no reference must not resume
        // tracking into a bogus session.
        InMemoryStore raw = new InMemoryStore();
        raw.putBoolean("task_active", true);
        TrackingStateStore store = new TrackingStateStore(raw);
        assertNull(store.getTaskTracking());
    }

    @Test
    public void workHourSessionRoundTrips() {
        TrackingStateStore store = newStore();
        store.saveWorkHourTracking(new TrackingStateStore.WorkHourState(
            "eng-7", 60000L, "https://api.example.com/loc", "token123", false));

        assertTrue(store.isWorkHourTrackingActive());
        TrackingStateStore.WorkHourState state = store.getWorkHourTracking();
        assertEquals("eng-7", state.engineerId);
        assertEquals(60000L, state.uploadInterval);
        assertEquals("https://api.example.com/loc", state.serverUrl);
        assertEquals("token123", state.authToken);
        assertFalse(state.enableOfflineQueue);
    }

    @Test
    public void workHourMinDistanceRoundTripsIncludingZero() {
        TrackingStateStore store = newStore();
        store.saveWorkHourTracking(new TrackingStateStore.WorkHourState(
            "eng-7", 60000L, "https://api.example.com/loc", null, true, 0f));

        assertEquals(0f, store.getWorkHourTracking().minDistance, 0.0001f);
    }

    @Test
    public void workHourMinDistanceDefaultsTo50Meters() {
        // Sessions persisted by earlier versions have no min-distance key
        TrackingStateStore store = newStore();
        store.saveWorkHourTracking(new TrackingStateStore.WorkHourState(
            "eng-7", 60000L, "https://api.example.com/loc", null, true));

        assertEquals(TrackingStateStore.DEFAULT_WORK_HOUR_MIN_DISTANCE_METERS,
            store.getWorkHourTracking().minDistance, 0.0001f);
    }

    @Test
    public void negativeWorkHourMinDistanceIsClampedToZero() {
        TrackingStateStore.WorkHourState state = new TrackingStateStore.WorkHourState(
            "eng-7", 60000L, "https://api.example.com/loc", null, true, -5f);
        assertEquals(0f, state.minDistance, 0.0001f);
    }

    @Test
    public void clearedWorkHourSessionYieldsNull() {
        TrackingStateStore store = newStore();
        store.saveWorkHourTracking(new TrackingStateStore.WorkHourState(
            "eng-7", 60000L, "https://api.example.com/loc", null, true));
        store.clearWorkHourTracking();

        assertFalse(store.isWorkHourTrackingActive());
        assertNull(store.getWorkHourTracking());
    }
}
