import Capacitor
import CoreLocation
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Capacitor plugin for background location tracking — iOS implementation.
///
/// Mirrors the Android plugin's method surface and event contract exactly (see
/// `../../../src/definitions.ts`, the single source of truth both platforms
/// implement against). This class only translates between `CAPPluginCall` and the
/// native `BackgroundLocation` orchestrator; all tracking logic lives there and in
/// its collaborators, so it can be tested without a Capacitor bridge.
///
/// Error contract: every reject carries a code from `ErrorCode`; asynchronous
/// failures surface through the "error" event. As on Android, this plugin never lets
/// a permission or configuration problem crash the host app — see the two
/// crash-parity guards documented on `LocationPermissionManager` and
/// `TaskLocationTracker`.
@objc(BackgroundLocationPlugin)
public class BackgroundLocationPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BackgroundLocationPlugin"
    public let jsName = "BackgroundLocation"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isLocationServiceEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openLocationSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openDeviceLocationSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getTrackingStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCurrentLocation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancelCurrentLocationRequest", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStoredLocations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearStoredLocations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLastLocation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startLocationStatusTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopLocationStatusTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startWorkHourTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopWorkHourTracking", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isWorkHourTrackingActive", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getQueuedWorkHourLocations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearQueuedWorkHourLocations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addGeofence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeGeofence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeAllGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "listGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPendingGeofenceTransitions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearPendingGeofenceTransitions", returnType: CAPPluginReturnPromise),
    ]

    private let location = BackgroundLocation()
    private var isObservingLocationStatus = false

    public override func load() {
        location.eventSink = self
        location.geofences.hasLiveListener = { [weak self] in
            self?.hasListeners("geofenceTransition") ?? false
        }
        location.resumeSessionsIfNeeded()
        // Crossings queued while offline (or while the app was relaunched for
        // a region event and suspended again mid-send) go out now.
        location.geofences.flushReports()
        #if canImport(UIKit)
        NotificationCenter.default.addObserver(
            self, selector: #selector(applicationDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification, object: nil)
        #endif
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func applicationDidBecomeActive() {
        // Defensive resync: if a session was active but CoreLocation's delivery
        // lapsed while suspended, this puts it back in the exact same state as a
        // fresh start with the persisted parameters. Idempotent when already active.
        location.resumeSessionsIfNeeded()
        location.geofences.flushReports()
    }

    // MARK: Permissions

    @objc public override func checkPermissions(_ call: CAPPluginCall) {
        call.resolve(permissionStatusPayload())
    }

    @objc public override func requestPermissions(_ call: CAPPluginCall) {
        let requested = call.getArray("permissions", String.self)
        let wantsForeground = requested == nil || requested!.isEmpty || requested!.contains("location")
        let wantsBackground = requested == nil || requested!.isEmpty || requested!.contains("backgroundLocation")

        if wantsForeground, !location.permissions.hasForegroundLocationPermission,
           let missingKey = location.permissions.missingUsageDescriptionKey(forBackground: false) {
            call.reject("Info.plist is missing \(missingKey). Add it under the app target's Info tab "
                + "before requesting location permission.", ErrorCode.internalError.rawValue)
            return
        }
        let willHaveForeground = location.permissions.hasForegroundLocationPermission || wantsForeground
        if wantsBackground, willHaveForeground, !location.permissions.hasBackgroundLocationPermission,
           let missingKey = location.permissions.missingUsageDescriptionKey(forBackground: true) {
            call.reject("Info.plist is missing \(missingKey). Add it under the app target's Info tab "
                + "before requesting background location permission.", ErrorCode.internalError.rawValue)
            return
        }

        requestForegroundIfNeeded(wanted: wantsForeground) { [self] in
            requestBackgroundIfNeeded(wanted: wantsBackground) {
                call.resolve(self.permissionStatusPayload())
            }
        }
    }

    private func requestForegroundIfNeeded(wanted: Bool, completion: @escaping () -> Void) {
        guard wanted, !location.permissions.hasForegroundLocationPermission else {
            completion()
            return
        }
        location.permissions.requestForegroundPermission { _ in completion() }
    }

    private func requestBackgroundIfNeeded(wanted: Bool, completion: @escaping () -> Void) {
        guard wanted, location.permissions.hasForegroundLocationPermission,
              !location.permissions.hasBackgroundLocationPermission else {
            completion()
            return
        }
        location.permissions.requestBackgroundPermission { _ in completion() }
    }

    private func permissionStatusPayload() -> [String: Any] {
        let status = location.permissions.authorizationStatus
        return [
            "location": foregroundStateString(status),
            "backgroundLocation": backgroundStateString(status),
            "accuracy": location.permissions.grantedAccuracy.rawValue,
            // No iOS equivalent of Android 14's install-time FOREGROUND_SERVICE_LOCATION
            // permission — always satisfied on this platform.
            "foregroundService": "granted",
        ]
    }

    private func foregroundStateString(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .authorizedWhenInUse, .authorizedAlways: return "granted"
        case .denied, .restricted: return "denied"
        case .notDetermined: return "prompt"
        @unknown default: return "prompt"
        }
    }

    private func backgroundStateString(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .authorizedAlways: return "granted"
        case .denied, .restricted: return "denied"
        case .authorizedWhenInUse, .notDetermined: return "prompt"
        @unknown default: return "prompt"
        }
    }

    @objc func isLocationServiceEnabled(_ call: CAPPluginCall) {
        location.permissions.isLocationServicesEnabled { enabled in
            call.resolve(["enabled": enabled])
        }
    }

    @objc func openLocationSettings(_ call: CAPPluginCall) {
        openAppSettings(call)
    }

    @objc func openDeviceLocationSettings(_ call: CAPPluginCall) {
        // iOS permits deep-linking only to the current app's own Settings page —
        // there is no public URL for the system-wide Location Services screen the
        // way Android's ACTION_LOCATION_SOURCE_SETTINGS provides. Both methods land
        // on the same screen on iOS; see the README's iOS platform notes.
        openAppSettings(call)
    }

    private func openAppSettings(_ call: CAPPluginCall) {
        #if canImport(UIKit)
        DispatchQueue.main.async {
            guard let url = URL(string: UIApplication.openSettingsURLString) else {
                call.reject("Could not build the app settings URL", ErrorCode.internalError.rawValue)
                return
            }
            UIApplication.shared.open(url, options: [:]) { success in
                if success {
                    call.resolve()
                } else {
                    call.reject("Could not open app settings", ErrorCode.internalError.rawValue)
                }
            }
        }
        #else
        call.reject("Not available on this platform", ErrorCode.internalError.rawValue)
        #endif
    }

    // MARK: Task tracking

    @objc func startTracking(_ call: CAPPluginCall) {
        guard let reference = call.getString("reference"),
              !reference.trimmingCharacters(in: .whitespaces).isEmpty else {
            call.reject("Missing required 'reference' parameter", ErrorCode.missingParameter.rawValue)
            return
        }
        // JS options are milliseconds/meters; CoreLocation and our trackers work in
        // seconds/meters.
        let intervalMs = call.getDouble("interval") ?? TrackingStateStore.defaultIntervalSeconds * 1000
        let minDistance = call.getDouble("minDistance") ?? TrackingStateStore.defaultMinDistanceMeters
        let highAccuracy = call.getBool("highAccuracy") ?? true
        let maxAccuracy = call.getDouble("maxAccuracy") ?? TrackingStateStore.defaultMaxAccuracyMeters

        location.taskTracker.start(reference: reference, interval: intervalMs / 1000, minDistance: minDistance,
                                    highAccuracy: highAccuracy, maxAccuracy: maxAccuracy) { [weak self] result in
            guard let self else { return }
            if result.success {
                call.resolve([
                    "backgroundLocationGranted": result.backgroundLocationGranted,
                    "accuracy": self.location.permissions.grantedAccuracy.rawValue,
                ])
            } else {
                call.reject(result.message, (result.code ?? .internalError).rawValue)
            }
        }
    }

    @objc func stopTracking(_ call: CAPPluginCall) {
        location.taskTracker.stop()
        call.resolve()
    }

    @objc func getTrackingStatus(_ call: CAPPluginCall) {
        // Explicit NSNull rather than `as Any`: an Any-boxed Optional<String>.none is
        // not guaranteed to bridge to JSON null across bridge versions/serializers.
        let reference: Any = location.taskTracker.currentReference ?? NSNull()
        call.resolve([
            "isTracking": location.taskTracker.isActive,
            "isWorkHourTracking": location.workHourTracker.isActive,
            "reference": reference,
        ])
    }

    // MARK: Work hour tracking

    @objc func startWorkHourTracking(_ call: CAPPluginCall) {
        let engineerId = call.getString("engineerId") ?? ""
        let serverUrl = call.getString("serverUrl") ?? ""
        let uploadIntervalMs = call.getDouble("uploadInterval") ?? TrackingStateStore.defaultUploadIntervalSeconds * 1000
        let authToken = call.getString("authToken")
        let enableOfflineQueue = call.getBool("enableOfflineQueue") ?? true

        location.workHourTracker.start(engineerId: engineerId, uploadInterval: uploadIntervalMs / 1000,
                                        serverUrl: serverUrl, authToken: authToken,
                                        enableOfflineQueue: enableOfflineQueue) { [weak self] result in
            guard let self else { return }
            if result.success {
                call.resolve(["backgroundLocationGranted": result.backgroundLocationGranted])
            } else {
                call.reject(result.message, (result.code ?? .internalError).rawValue)
            }
        }
    }

    @objc func stopWorkHourTracking(_ call: CAPPluginCall) {
        location.workHourTracker.stop()
        call.resolve()
    }

    @objc func isWorkHourTrackingActive(_ call: CAPPluginCall) {
        call.resolve(["active": location.workHourTracker.isActive])
    }

    @objc func getQueuedWorkHourLocations(_ call: CAPPluginCall) {
        call.resolve(["locations": location.workHourTracker.queueSnapshot().map(workHourDict)])
    }

    @objc func clearQueuedWorkHourLocations(_ call: CAPPluginCall) {
        location.workHourTracker.clearQueue()
        call.resolve()
    }

    // MARK: Current location

    @objc func getCurrentLocation(_ call: CAPPluginCall) {
        let timeout = (call.getDouble("timeout") ?? CurrentLocationWatcher.defaultTimeout * 1000) / 1000
        let targetAccuracy = call.getDouble("targetAccuracy")

        let completion: (CurrentLocationRequester.FixResult) -> Void = { result in
            switch result {
            case .success(let (fix, timedOut)):
                call.resolve(self.fixDict(fix, isFinal: true, timedOut: timedOut, targetAccuracy: targetAccuracy))
            case .failure(let code):
                call.reject(self.message(for: code), code.rawValue)
            }
        }

        if let targetAccuracy {
            location.currentLocationRequester.requestProgressive(
                targetAccuracy: targetAccuracy, timeout: timeout,
                onLiveUpdate: { [weak self] fix in
                    guard let self else { return }
                    self.notifyListeners("currentLocation",
                        data: self.fixDict(fix, isFinal: false, timedOut: false, targetAccuracy: targetAccuracy))
                },
                completion: completion)
        } else {
            location.currentLocationRequester.requestSingleShot(timeout: timeout, completion: completion)
        }
    }

    @objc func cancelCurrentLocationRequest(_ call: CAPPluginCall) {
        location.currentLocationRequester.cancel()
        call.resolve()
    }

    private func message(for code: ErrorCode) -> String {
        switch code {
        case .permissionDenied: return "Location permission not granted"
        case .locationUnavailable: return "Could not obtain a location fix within the timeout"
        default: return code.rawValue
        }
    }

    // MARK: Stored location data

    @objc func getStoredLocations(_ call: CAPPluginCall) {
        guard let reference = call.getString("reference"), !reference.isEmpty else {
            call.reject("Missing required 'reference' parameter", ErrorCode.missingParameter.rawValue)
            return
        }
        call.resolve(["locations": location.database.getLocations(forReference: reference).map(locationItemDict)])
    }

    @objc func clearStoredLocations(_ call: CAPPluginCall) {
        location.database.clearAll()
        call.resolve()
    }

    @objc func getLastLocation(_ call: CAPPluginCall) {
        guard let reference = call.getString("reference"), !reference.isEmpty else {
            call.reject("Missing required 'reference' parameter", ErrorCode.missingParameter.rawValue)
            return
        }
        guard let item = location.database.getLastLocation(forReference: reference) else {
            call.reject("No location found for reference: \(reference)", ErrorCode.notFound.rawValue)
            return
        }
        let dict = locationItemDict(item)
        notifyListeners("locationUpdate", data: dict)
        call.resolve(dict)
    }

    // MARK: Location services status

    @objc func startLocationStatusTracking(_ call: CAPPluginCall) {
        isObservingLocationStatus = true
        location.permissions.isLocationServicesEnabled { [weak self] enabled in
            self?.notifyListeners("locationStatus", data: ["enabled": enabled])
        }
        call.resolve()
    }

    @objc func stopLocationStatusTracking(_ call: CAPPluginCall) {
        isObservingLocationStatus = false
        call.resolve()
    }

    // MARK: JSON payload helpers

    private func locationItemDict(_ item: LocationItem) -> [String: Any] {
        [
            "reference": item.reference,
            "index": item.index,
            "latitude": item.latitude,
            "longitude": item.longitude,
            "altitude": item.altitude,
            "accuracy": item.accuracy,
            "speed": item.speed,
            "heading": item.heading,
            "altitudeAccuracy": item.altitudeAccuracy,
            "totalDistance": item.totalDistance,
            "timestamp": item.timestamp.timeIntervalSince1970 * 1000,
        ]
    }

    private func fixDict(_ fix: CurrentLocationWatcher.Fix, isFinal: Bool, timedOut: Bool, targetAccuracy: Double?) -> [String: Any] {
        var dict: [String: Any] = [
            "latitude": fix.latitude,
            "longitude": fix.longitude,
            "accuracy": fix.accuracy,
            "altitude": fix.altitude,
            "speed": fix.speed,
            "heading": fix.heading,
            "timestamp": fix.timestamp.timeIntervalSince1970 * 1000,
            "isFinal": isFinal,
            "timedOut": timedOut,
        ]
        if let targetAccuracy { dict["targetAccuracy"] = targetAccuracy }
        return dict
    }

    private func workHourDict(_ data: WorkHourLocationData) -> [String: Any] {
        [
            "latitude": data.latitude,
            "longitude": data.longitude,
            "accuracy": data.accuracy,
            "timestamp": data.timestamp.timeIntervalSince1970 * 1000,
            "engineerId": data.engineerId,
        ]
    }

    // MARK: Geofencing

    @objc func addGeofence(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), !id.isEmpty,
              let latitude = call.getDouble("latitude"),
              let longitude = call.getDouble("longitude"),
              let radius = call.getDouble("radius")
        else {
            call.reject("id, latitude, longitude and radius are required",
                        ErrorCode.missingParameter.rawValue)
            return
        }

        func notificationSpec(_ key: String) -> GeofenceNotificationSpec? {
            guard let spec = call.getObject(key),
                  let title = spec["title"] as? String,
                  let body = spec["body"] as? String else { return nil }
            return GeofenceNotificationSpec(title: title, body: body)
        }
        let notification = notificationSpec("notification")

        var report: GeofenceReportSpec?
        if let spec = call.getObject("report"), let url = spec["url"] as? String, !url.isEmpty {
            report = GeofenceReportSpec(url: url, authToken: spec["authToken"] as? String)
        }

        let definition = GeofenceDefinition(
            id: id,
            latitude: latitude,
            longitude: longitude,
            radius: radius,
            notifyOnEntry: call.getBool("notifyOnEntry") ?? true,
            notifyOnExit: call.getBool("notifyOnExit") ?? false,
            notification: notification,
            enterNotification: notificationSpec("enterNotification"),
            exitNotification: notificationSpec("exitNotification"),
            report: report
        )

        do {
            try location.geofences.add(definition)
            call.resolve()
        } catch GeofenceError.backgroundPermissionDenied {
            call.reject("\"Allow all the time\" location permission is required to monitor a region",
                        ErrorCode.backgroundPermissionDenied.rawValue)
        } catch GeofenceError.tooManyRegions {
            call.reject("at most \(GeofenceMonitor.maxRegions) regions can be monitored",
                        ErrorCode.internalError.rawValue)
        } catch {
            call.reject("region monitoring is not available on this device",
                        ErrorCode.internalError.rawValue)
        }
    }

    @objc func removeGeofence(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), !id.isEmpty else {
            call.reject("id is required", ErrorCode.missingParameter.rawValue)
            return
        }
        location.geofences.remove(id: id)
        call.resolve()
    }

    @objc func removeAllGeofences(_ call: CAPPluginCall) {
        location.geofences.removeAll()
        call.resolve()
    }

    @objc func listGeofences(_ call: CAPPluginCall) {
        call.resolve(["geofences": location.geofences.list().map(geofenceDict)])
    }

    @objc func getPendingGeofenceTransitions(_ call: CAPPluginCall) {
        let transitions = location.geofences.pendingTransitions()
            .map { geofenceTransitionDict($0, buffered: true) }
        call.resolve(["transitions": transitions])
    }

    @objc func clearPendingGeofenceTransitions(_ call: CAPPluginCall) {
        location.geofences.clearPendingTransitions()
        call.resolve()
    }

    private func geofenceDict(_ definition: GeofenceDefinition) -> [String: Any] {
        var dict: [String: Any] = [
            "id": definition.id,
            "latitude": definition.latitude,
            "longitude": definition.longitude,
            "radius": definition.radius,
            "notifyOnEntry": definition.notifyOnEntry,
            "notifyOnExit": definition.notifyOnExit,
        ]
        if let notification = definition.notification {
            dict["notification"] = ["title": notification.title, "body": notification.body]
        }
        if let notification = definition.enterNotification {
            dict["enterNotification"] = ["title": notification.title, "body": notification.body]
        }
        if let notification = definition.exitNotification {
            dict["exitNotification"] = ["title": notification.title, "body": notification.body]
        }
        // The URL only — never the token. Lets the app confirm a region is
        // still reporting without handing its credential back to JavaScript.
        if let report = definition.report {
            dict["report"] = ["url": report.url]
        }
        return dict
    }

    private func geofenceTransitionDict(_ transition: GeofenceTransition, buffered: Bool) -> [String: Any] {
        var dict: [String: Any] = [
            "id": transition.id,
            "transition": transition.transition,
            "timestamp": transition.timestamp,
            "buffered": buffered,
        ]
        if let latitude = transition.latitude { dict["latitude"] = latitude }
        if let longitude = transition.longitude { dict["longitude"] = longitude }
        if let accuracy = transition.accuracy { dict["accuracy"] = accuracy }
        return dict
    }
}

// MARK: - BackgroundLocationEventSink

extension BackgroundLocationPlugin: BackgroundLocationEventSink {
    func didRecordLocation(_ item: LocationItem) {
        notifyListeners("locationUpdate", data: locationItemDict(item))
    }

    func didChangeLocationServicesEnabled(_ enabled: Bool) {
        notifyListeners("locationStatus", data: ["enabled": enabled])
    }

    func didQueueWorkHourLocation(_ data: WorkHourLocationData) {
        notifyListeners("workHourLocationUpdate", data: workHourDict(data))
    }

    func didFinishWorkHourUpload(count: Int, success: Bool, error: String?) {
        var data: [String: Any] = ["success": success, "count": count]
        if let error { data["error"] = error }
        notifyListeners("workHourLocationUploaded", data: data)
    }

    func didCrossGeofence(_ transition: GeofenceTransition, buffered: Bool) {
        notifyListeners("geofenceTransition", data: geofenceTransitionDict(transition, buffered: buffered))
    }

    func didEmitError(code: ErrorCode, message: String, source: ErrorSource, fatal: Bool) {
        notifyListeners("error", data: [
            "code": code.rawValue,
            "message": message,
            "source": source.rawValue,
            "fatal": fatal,
        ])
    }
}
