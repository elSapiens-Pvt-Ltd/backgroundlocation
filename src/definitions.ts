import type { PluginListenerHandle } from "@capacitor/core";

/**
 * A circular region the operating system watches on the app's behalf.
 *
 * Region monitoring is not the tracking API with a distance check bolted on: the
 * OS does the watching, wakes a *terminated* app to deliver the crossing, and
 * costs effectively no battery because it rides on hardware the device is
 * already using. That is why this exists separately from `startTracking` —
 * polling cannot see someone whose phone is in their pocket with the app closed,
 * which is the only situation this feature is for.
 *
 * Platform limits worth knowing before you design around it:
 * - iOS monitors at most 20 regions per app, and clamps a radius larger than the
 *   device's maximum (typically ~1-2 km). Android allows 100.
 * - Both platforms need "Allow all the time" location permission. With only
 *   "While Using", regions are registered but never fire once the app is
 *   backgrounded.
 * - Delivery is best-effort and can lag by a minute or more; the OS trades
 *   promptness for power. Treat a crossing as "they have arrived", never as a
 *   precise timestamp.
 */
export interface Geofence {
  /** Caller-chosen identity. Re-adding the same id replaces the region. */
  id: string;
  latitude: number;
  longitude: number;
  /** Radius in metres. Values under ~100 m are unreliable in practice. */
  radius: number;
  /** Fire when the device enters the region. Defaults to true. */
  notifyOnEntry?: boolean;
  /** Fire when the device leaves the region. Defaults to false. */
  notifyOnExit?: boolean;
  /**
   * Post a local notification natively the moment the region fires.
   *
   * Strongly recommended. A crossing can relaunch a terminated app, but the
   * webview takes seconds to boot and may be killed again before it does — so a
   * reminder that only exists as a JavaScript event is a reminder the user may
   * never see. Posting it from native code makes it independent of whether JS
   * ever runs.
   */
  notification?: GeofenceNotification;
  /** Posted instead of `notification` when the device enters the region. */
  enterNotification?: GeofenceNotification;
  /** Posted instead of `notification` when the device leaves the region. */
  exitNotification?: GeofenceNotification;
  /**
   * Post every crossing of this region to a server, natively.
   *
   * For anything that has to ACT on a crossing (an automatic break, an
   * end-of-day punch-out). A crossing usually happens with the app dead, and a
   * JavaScript listener may not run until the user next opens the app — far
   * too late. With `report`, the native layer POSTs the transition itself
   * (the same JSON as the `geofenceTransition` event, minus `buffered`),
   * queued and retried in order until delivered, with the app never opened.
   *
   * The server should answer 401 or 403 once `authToken` is no longer valid;
   * the plugin then discards what is queued for the region and stops
   * watching it. `listGeofences` returns the URL but never the token.
   */
  report?: GeofenceReport;
}

/** Where a region's crossings are posted. */
export interface GeofenceReport {
  /** Absolute URL that receives a POST per crossing. */
  url: string;
  /** Sent as `Authorization: Bearer <authToken>`. */
  authToken?: string;
}

/** Local notification posted natively when a region fires. */
export interface GeofenceNotification {
  title: string;
  body: string;
  /**
   * Android notification channel id. Defaults to the plugin's own channel.
   * Pass the host app's channel to keep the user's notification settings in
   * one place.
   */
  channelId?: string;
}

export type GeofenceTransitionType = 'enter' | 'exit';

/** Payload of the `geofenceTransition` event. */
export interface GeofenceTransitionEvent {
  /** The `id` given to `addGeofence`. */
  id: string;
  transition: GeofenceTransitionType;
  latitude?: number;
  longitude?: number;
  accuracy?: number;
  /** Epoch milliseconds when the OS reported the crossing. */
  timestamp: number;
  /**
   * True when this crossing was recorded while no JavaScript listener existed
   * — the app was terminated or suspended — and is being read back from the
   * native buffer rather than delivered live.
   */
  buffered: boolean;
}

/**
 * Permission state for a single permission tier.
 *
 * - `granted` — the user granted the permission.
 * - `denied` — the user denied it; a new request will not show a dialog on Android,
 *   send the user to settings with `openLocationSettings()` instead.
 * - `prompt` — not asked yet; `requestPermissions()` will show the system dialog.
 * - `prompt-with-rationale` — denied once; explain why before asking again.
 */
export type PermissionState = 'granted' | 'denied' | 'prompt' | 'prompt-with-rationale';

/** Location accuracy tier the user granted (Android 12+ lets users pick "approximate"). */
export type LocationAccuracyLevel = 'fine' | 'coarse' | 'none';

/**
 * Machine-readable error codes.
 *
 * Every rejected call carries one of these in the `code` property of the error, and
 * every `error` event carries one in its `code` field — branch on the code, never on
 * the human-readable message.
 *
 * | Code | Meaning | Recommended app reaction |
 * |------|---------|--------------------------|
 * | `PERMISSION_DENIED` | Foreground location permission missing | Call `requestPermissions()`; if state is `denied`, call `openLocationSettings()` |
 * | `BACKGROUND_PERMISSION_DENIED` | "Allow all the time" missing | Explain why, then `requestPermissions({permissions: ['backgroundLocation']})` or `openLocationSettings()` |
 * | `LOCATION_SERVICES_DISABLED` | Device GPS toggle is off | Prompt user; `openDeviceLocationSettings()` |
 * | `MISSING_PARAMETER` | A required option was not provided | Fix the call site |
 * | `SERVICE_START_FAILED` | Android refused to start the foreground service | Retry from the foreground; check battery restrictions |
 * | `LOCATION_UNAVAILABLE` | No usable fix within the timeout | Retry, or ask the user to move to open sky |
 * | `NOT_FOUND` | No stored data for the given reference | Treat as empty |
 * | `CANCELLED` | Superseded/cancelled request | Usually ignorable |
 * | `INTERNAL_ERROR` | Unexpected native failure | Log and report |
 */
export type ErrorCode =
  | 'PERMISSION_DENIED'
  | 'BACKGROUND_PERMISSION_DENIED'
  | 'LOCATION_SERVICES_DISABLED'
  | 'MISSING_PARAMETER'
  | 'SERVICE_START_FAILED'
  | 'LOCATION_UNAVAILABLE'
  | 'NOT_FOUND'
  | 'CANCELLED'
  | 'INTERNAL_ERROR';

/** Which subsystem raised an asynchronous error event. */
export type ErrorSource = 'taskTracking' | 'workHourTracking' | 'currentLocation' | 'permissions';

/**
 * Payload of the `error` event.
 *
 * Asynchronous failures — the tracking service losing its permission, the user
 * switching GPS off mid-session, a failed service restart — cannot reject a promise,
 * so they surface here. Fatal errors mean tracking stopped; non-fatal ones are
 * warnings (e.g. background permission missing while tracking continues in the
 * foreground).
 */
export interface PluginError {
  code: ErrorCode;
  message: string;
  source: ErrorSource;
  /** true when tracking stopped because of this error. */
  fatal: boolean;
}

export interface PermissionStatus {
  /** Foreground ("while in use") location permission. Granted when fine OR coarse is granted. */
  location: PermissionState;
  /** Background ("allow all the time") location permission. */
  backgroundLocation: PermissionState;
  /** Accuracy tier the user granted. `coarse` means approximate location only. */
  accuracy: LocationAccuracyLevel;
  /**
   * Install-time FOREGROUND_SERVICE_LOCATION permission (Android 14+).
   * `denied` means the host app's AndroidManifest.xml is missing the declaration —
   * an integration error, not something the user can fix.
   */
  foregroundService: 'granted' | 'denied';
}

export interface RequestPermissionsOptions {
  /**
   * Which permission tiers to request. Defaults to both, requested in the required
   * order: foreground first (system dialog), then background (Android opens the
   * app's location settings where the user picks "Allow all the time").
   *
   * Best practice: request `['location']` when tracking starts, and request
   * `['backgroundLocation']` separately after explaining why you need it.
   */
  permissions?: ('location' | 'backgroundLocation')[];
}

export interface StartTrackingOptions {
  /** Identifier the recorded route is stored under (e.g. a task id). Required. */
  reference: string;
  /** Requested update interval in milliseconds. Default 3000. */
  interval?: number;
  /** Minimum movement in meters between recorded fixes. Default 10. */
  minDistance?: number;
  /** true (default) = GPS-grade accuracy; false = balanced power. */
  highAccuracy?: boolean;
  /**
   * Worst acceptable horizontal accuracy in meters; less accurate fixes are
   * discarded instead of polluting the recorded route. Default 30.
   */
  maxAccuracy?: number;
  /** Title of the persistent tracking notification. Defaults to an English string. */
  notificationTitle?: string;
  /** Body text of the persistent tracking notification. */
  notificationText?: string;
}

export interface StartTrackingResult {
  /**
   * false when tracking started with foreground permission only. Tracking works
   * while the service lives, but cannot recover from a background restart — ask the
   * user for "Allow all the time" (see `BACKGROUND_PERMISSION_DENIED`).
   */
  backgroundLocationGranted: boolean;
  /** Accuracy tier tracking runs at. Warn the user when it is `coarse`. */
  accuracy: LocationAccuracyLevel;
}

export interface TrackingStatus {
  /** true when a task tracking session is active (survives app restarts). */
  isTracking: boolean;
  /** true when work-hour tracking is active. */
  isWorkHourTracking: boolean;
  /** Reference of the active task session, or null. */
  reference: string | null;
}

export interface CurrentLocationOptions {
  /**
   * Desired horizontal accuracy in meters. When set, the plugin keeps requesting
   * fixes and emits each one as a `currentLocation` event (live, so the UI can show
   * the position refining) until a fix meets this accuracy — that fix resolves the
   * promise with `isFinal: true`. When omitted, the first fresh fix resolves.
   */
  targetAccuracy?: number;
  /**
   * Give-up time in milliseconds (default 30000). On timeout the most accurate fix
   * seen so far resolves with `timedOut: true`; if nothing usable arrived the call
   * rejects with `LOCATION_UNAVAILABLE`.
   */
  timeout?: number;
}

export interface CurrentLocation {
  latitude: number;
  longitude: number;
  /** Horizontal accuracy of this fix in meters. */
  accuracy: number;
  altitude?: number;
  speed?: number;
  heading?: number;
  timestamp: number;
  /** true when this fix ended the request (target met or timeout). */
  isFinal: boolean;
  /** true when the request timed out before reaching the target accuracy. */
  timedOut: boolean;
  /** Echo of the requested target accuracy (only on `currentLocation` events). */
  targetAccuracy?: number;
}

export interface LocationData {
  reference: string;
  index: number;
  latitude: number;
  longitude: number;
  altitude?: number;
  speed?: number;
  heading?: number;
  accuracy: number;
  altitudeAccuracy?: number;
  /** Total distance travelled in this session, in kilometers. */
  totalDistance?: number;
  timestamp: number;
}

export interface WorkHourLocationData {
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
  engineerId?: string;
}

export interface WorkHourTrackingOptions {
  /** Identifier sent with every upload. Required. */
  engineerId: string;
  /** Upload (and sampling) interval in milliseconds. Default 300000 (5 minutes). */
  uploadInterval?: number;
  /** Endpoint that receives `{engineerId, timestamp, locations: [...]}` POSTs. Required. */
  serverUrl: string;
  /** Sent as a Bearer token in the Authorization header. */
  authToken?: string;
  /** Keep fixes queued across failed uploads (default true). */
  enableOfflineQueue?: boolean;
  /**
   * Minimum movement in meters before a new fix is queued. Default 50.
   *
   * With the default, a device that stays put produces no fixes, so the server
   * cannot tell "has not moved" from "phone off or app killed". Set `0` to queue a
   * fix every `uploadInterval` regardless of movement: the upload then doubles as a
   * heartbeat, which is what a live "last seen" view needs.
   */
  minDistance?: number;
}

export interface BatteryOptimizationStatus {
  /**
   * true when the OS exempts this app from battery optimization (Android "Don't
   * optimize" / "Unrestricted"). Always true on iOS and web, which have no such
   * setting.
   */
  ignoring: boolean;
}

export interface WorkHourUploadResult {
  success: boolean;
  /** Number of fixes in the uploaded (or dropped) batch. */
  count: number;
  error?: string;
}

/**
 * Background location tracking for Capacitor.
 *
 * ## Permission flow (Android)
 *
 * ```typescript
 * const status = await BackgroundLocation.checkPermissions();
 * if (status.location !== 'granted') {
 *   const after = await BackgroundLocation.requestPermissions({ permissions: ['location'] });
 *   if (after.location !== 'granted') {
 *     // Denied — explain, then send the user to settings:
 *     await BackgroundLocation.openLocationSettings();
 *     return;
 *   }
 * }
 * const start = await BackgroundLocation.startTracking({ reference: 'task_1' });
 * if (!start.backgroundLocationGranted) {
 *   // Tracking runs, but ask for "Allow all the time" so it survives backgrounding:
 *   await BackgroundLocation.requestPermissions({ permissions: ['backgroundLocation'] });
 * }
 * ```
 *
 * Every rejection carries an {@link ErrorCode} in `error.code`; asynchronous
 * failures arrive through the `error` event. Nothing in this plugin crashes the app
 * on a permission problem.
 */
export interface BackgroundLocationPlugin {

  /**
   * Current state of all location permission tiers, including the accuracy tier
   * (Android 12+ users may grant approximate location only).
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Request location permissions. Foreground and background are requested in
   * sequence as Android requires; see {@link RequestPermissionsOptions} for
   * requesting a single tier (recommended UX).
   *
   * Once a tier reports `denied`, Android will not show the dialog again — use
   * `openLocationSettings()` and let the user grant it manually.
   */
  requestPermissions(options?: RequestPermissionsOptions): Promise<PermissionStatus>;

  /** Whether device location services (the GPS toggle) are enabled. */
  isLocationServiceEnabled(): Promise<{ enabled: boolean }>;

  /**
   * Open this app's system settings page — where the user grants a previously
   * denied permission or upgrades to "Allow all the time".
   */
  openLocationSettings(): Promise<void>;

  /** Open the device location-services settings — for the GPS-off case. */
  openDeviceLocationSettings(): Promise<void>;

  /**
   * Whether the app is exempt from battery optimization. On Android, an
   * optimized app's tracking service can be delayed by Doze and killed outright by
   * vendor battery managers (Xiaomi, Oppo, Vivo, Samsung), so work-hour uploads
   * stop for hours. Check this before relying on all-day tracking.
   */
  isIgnoringBatteryOptimizations(): Promise<BatteryOptimizationStatus>;

  /**
   * Open the system screen where the user exempts the app from battery
   * optimization (falls back to the app's settings page). The plugin never shows
   * the one-tap `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog: that permission is
   * restricted by Google Play policy, and declaring it would leak into every
   * consuming app. No-op on iOS and web.
   */
  openBatteryOptimizationSettings(): Promise<void>;

  /**
   * Start recording a route under `reference`.
   *
   * Requires foreground location permission and enabled location services
   * (rejects with `PERMISSION_DENIED` / `LOCATION_SERVICES_DISABLED` otherwise).
   * Missing background permission does NOT reject: tracking starts and the result's
   * `backgroundLocationGranted: false` (plus a non-fatal `error` event) tells you to
   * ask the user for "Allow all the time".
   *
   * The session is persisted natively and survives app and device restarts until
   * `stopTracking()` is called.
   */
  startTracking(options: StartTrackingOptions): Promise<StartTrackingResult>;

  /** Stop the active tracking session. Idempotent — never rejects when inactive. */
  stopTracking(): Promise<void>;

  /** Current native tracking state — useful to resync UI after an app restart. */
  getTrackingStatus(): Promise<TrackingStatus>;

  /**
   * Get the device's current position.
   *
   * With `targetAccuracy` set, fixes stream as `currentLocation` events until one
   * meets the target (see {@link CurrentLocationOptions}) — use this to show a
   * live "improving accuracy" indicator while waiting for a precise fix.
   */
  getCurrentLocation(options?: CurrentLocationOptions): Promise<CurrentLocation>;

  /**
   * Cancel an in-flight progressive `getCurrentLocation()` request; its promise
   * rejects with `CANCELLED`.
   */
  cancelCurrentLocationRequest(): Promise<void>;

  /** All recorded fixes for a reference, oldest first. */
  getStoredLocations(options: { reference: string }): Promise<{ locations: LocationData[] }>;

  /** Delete all stored fixes. */
  clearStoredLocations(): Promise<void>;

  /**
   * Latest stored fix for a reference. Also re-emits it as a `locationUpdate`
   * event. Rejects with `NOT_FOUND` when nothing is stored yet.
   */
  getLastLocation(options: { reference: string }): Promise<LocationData>;

  /**
   * Start emitting `locationStatus` events when the user toggles device location
   * services. Needs no permission — safe to call on app start.
   */
  startLocationStatusTracking(): Promise<void>;

  /** Stop emitting `locationStatus` events. */
  stopLocationStatusTracking(): Promise<void>;

  /**
   * Start periodic location uploads to `serverUrl` (persists across app kills as a
   * foreground service). Same permission model as `startTracking()`.
   */
  startWorkHourTracking(options: WorkHourTrackingOptions): Promise<StartTrackingResult>;

  /** Stop work-hour tracking. Idempotent. */
  stopWorkHourTracking(): Promise<void>;

  isWorkHourTrackingActive(): Promise<{ active: boolean }>;

  /** Fixes queued for upload, as visible to this app process. */
  getQueuedWorkHourLocations(): Promise<{ locations: WorkHourLocationData[] }>;

  clearQueuedWorkHourLocations(): Promise<void>;

  /**
   * Start monitoring a circular region. Replaces any region with the same id.
   *
   * Rejects with `BACKGROUND_PERMISSION_DENIED` when "Allow all the time" has
   * not been granted — registering the region anyway would produce a watch that
   * silently never fires, which is worse than a clear failure.
   */
  addGeofence(options: Geofence): Promise<void>;

  /** Stop monitoring one region. Succeeds whether or not it was registered. */
  removeGeofence(options: { id: string }): Promise<void>;

  /** Stop monitoring every region this plugin registered. */
  removeAllGeofences(): Promise<void>;

  /** The regions currently being monitored. */
  listGeofences(): Promise<{ geofences: Geofence[] }>;

  /**
   * Crossings that fired while no JavaScript listener was attached, oldest
   * first. Does not consume them — call `clearPendingGeofenceTransitions()`
   * once they are handled.
   *
   * Call this at startup, every time, right after attaching the listener. A
   * region crossing usually happens with the app dead, so the buffer — not the
   * event — is the normal delivery path; treating it as an edge case means
   * missing most crossings. Nothing is buffered while a listener is attached,
   * so this cannot double-deliver a live event.
   */
  getPendingGeofenceTransitions(): Promise<{ transitions: GeofenceTransitionEvent[] }>;

  /** Discard buffered crossings. Call after handling them. */
  clearPendingGeofenceTransitions(): Promise<void>;

  /** A fix was recorded for the active tracking session. */
  addListener(eventName: 'locationUpdate',
    listenerFunc: (data: LocationData) => void): Promise<PluginListenerHandle>;

  /** Device location services were toggled on/off. */
  addListener(eventName: 'locationStatus',
    listenerFunc: (status: { enabled: boolean }) => void): Promise<PluginListenerHandle>;

  /** Live fix stream from a progressive `getCurrentLocation()` request. */
  addListener(eventName: 'currentLocation',
    listenerFunc: (data: CurrentLocation) => void): Promise<PluginListenerHandle>;

  /**
   * Asynchronous failure or warning — see {@link PluginError}. Listen for
   * `BACKGROUND_PERMISSION_DENIED` here to know when to ask the user for
   * "Allow all the time".
   */
  addListener(eventName: 'error',
    listenerFunc: (error: PluginError) => void): Promise<PluginListenerHandle>;

  /** A fix entered the work-hour upload queue. */
  addListener(eventName: 'workHourLocationUpdate',
    listenerFunc: (data: WorkHourLocationData) => void): Promise<PluginListenerHandle>;

  /** A work-hour upload batch succeeded or failed. */
  addListener(eventName: 'workHourLocationUploaded',
    listenerFunc: (data: WorkHourUploadResult) => void): Promise<PluginListenerHandle>;

  /**
   * The device entered or left a monitored region, delivered live.
   *
   * A crossing that fires while the app is dead cannot reach a listener that
   * does not exist yet; it is buffered instead. Attach this listener AND drain
   * `getPendingGeofenceTransitions()` at startup, or you will only ever see the
   * crossings that happen to occur while the app is open.
   */
  addListener(eventName: 'geofenceTransition',
    listenerFunc: (event: GeofenceTransitionEvent) => void): Promise<PluginListenerHandle>;

  /** Remove all listeners registered by this plugin. */
  removeAllListeners(): Promise<void>;
}
