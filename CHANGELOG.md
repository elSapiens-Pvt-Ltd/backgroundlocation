# Changelog

All notable changes to the Elsapiens Background Location Plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] - 2026-09-21

### Added
- **Native crossing reports** — `addGeofence({ report: { url, authToken } })`
  posts every crossing of the region to a server from native code, so a
  crossing reaches the server with the app closed. Reports are persisted and
  sent strictly in order (an entry must never arrive before the exit it
  answers); failures stay queued and retry — on Android through WorkManager
  once a network is available, on iOS at the next wake (another crossing, the
  app opening, the plugin loading). A 401/403 means the credential was revoked:
  what is queued for the region is dropped and the region stops being watched.
  `listGeofences` returns the report URL but never the token.
- **Per-direction notifications** — `enterNotification` / `exitNotification`
  override `notification`, since arriving and leaving usually need different
  words.

### Changed
- Android now depends on `androidx.work:work-runtime` for report delivery.

## [0.3.0] - 2026-08-31

### Added
- **Geofencing / region monitoring** on both platforms — `addGeofence`,
  `removeGeofence`, `removeAllGeofences`, `listGeofences`, the
  `geofenceTransition` event, and `getPendingGeofenceTransitions` /
  `clearPendingGeofenceTransitions` for crossings that arrived with nothing
  listening. Built on `CLCircularRegion` (iOS) and `GeofencingClient`
  (Android).

  This is not the tracking API with a distance check on top, and the difference
  matters: the OS holds the watch, wakes a *terminated* app to deliver the
  crossing, and costs effectively no battery. Sampling cannot see a phone in a
  pocket with the app closed, which is the only situation the feature exists
  for.

- Optional `notification` on a geofence, posted natively at the instant of the
  crossing. A crossing can relaunch a terminated app, but the webview takes
  seconds to boot and may be suspended again first — so a reminder that exists
  only as a JavaScript event is one the user may never see.

- Crossing buffer. When no JavaScript listener is attached — the normal case —
  the crossing is persisted and read back with `getPendingGeofenceTransitions`.
  Bounded at 50, oldest dropped first, so a buffer behind an app that never
  launches cannot grow without limit.

### Notes
- Region monitoring requires "Allow all the time" location permission on both
  platforms. `addGeofence` **rejects** with `BACKGROUND_PERMISSION_DENIED`
  rather than registering a region that would be created and then silently
  never fire.
- Arming a region around a device already inside it would never produce a
  crossing, so both platforms are asked to report initial state
  (`requestState` on iOS, `INITIAL_TRIGGER_ENTER` on Android).
- Platform limits: iOS monitors at most 20 regions per app and clamps radii
  above the device maximum; Android allows 100. Delivery is best-effort and can
  lag by a minute or more — a crossing means "they arrived", not a precise
  timestamp.
- Android gains `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`, and a
  manifest-registered `GeofenceBroadcastReceiver`. Manifest-registered because
  the whole point is being reachable when the app is not running: Android
  starts a fresh process for the broadcast, where a runtime-registered receiver
  would not exist.
- Web throws `unavailable` for `addGeofence`. A browser can watch a position
  only while the page is open, which is precisely the dependency this API
  exists to remove; emulating it by polling would be a worse lie than a clear
  refusal.

## [0.2.0] - 2026-07-17

### Added
- **Full iOS implementation** — the platform moves from "planned/not implemented"
  to full feature parity with Android: task tracking with route recording and
  distance calculation, work-hour tracking with batched server uploads and an
  offline queue, progressive-accuracy `getCurrentLocation`, the same typed
  `ErrorCode`/`error` event contract, and session persistence that survives the
  app being suspended and relaunched by the system.
- iOS route/fix storage via a direct `libsqlite3` binding (no third-party
  dependency, matching Android's own zero-extra-dependency storage), with the
  same schema and distance math (`DistanceTracker`, ported 1:1 including the
  haversine formula) so a route recorded on either platform is held to the same
  standard.
- 32 XCTest unit tests covering the ported pure-logic classes (`LocationFilter`,
  `DistanceTracker`, `CurrentLocationWatcher`, `WorkHourLocationQueue`,
  `TrackingStateStore`), run against an iOS Simulator destination; full
  `xcodebuild` compile verified for `generic/platform=iOS`.
- README iOS Configuration section documenting the two required one-time Xcode
  steps (Info.plist usage-description keys, Background Modes → Location
  updates capability) and the platform's Settings deep-link limitation; new
  Feature Parity table and iOS Requirements section.

### Fixed (iOS crash parity)
Two iOS crashes in the same class as the Android `ForegroundServiceDidNotStartInTimeException`
this plugin was originally rewritten to fix — both are guarded against before
they can occur, never worked around after the fact:
- Setting `allowsBackgroundLocationUpdates = true` without the host app's
  Info.plist declaring the `location` UIBackgroundModes capability throws an
  uncaught `NSInvalidArgumentException` and kills the app. The iOS trackers
  check `hostAppDeclaresBackgroundLocationMode` first and report a non-fatal
  `SERVICE_START_FAILED` error instead.
- Calling `requestWhenInUseAuthorization()`/`requestAlwaysAuthorization()`
  without the matching `NSLocation*UsageDescription` Info.plist key crashes
  immediately ("attempted to access privacy-sensitive data without a usage
  description"). `requestPermissions()` checks for the key first and rejects
  with a clear configuration error naming the missing key instead of ever
  calling the crash-prone API.

### Changed
- `interval` (task tracking) and `uploadInterval` (work-hour tracking) are
  honored on iOS as an explicit time gate layered on top of CoreLocation's
  distance-filtered delivery — CoreLocation has no request-interval API the way
  Android's FusedLocationProvider does, so this keeps recorded/queued cadence
  comparable across platforms for the same options rather than silently
  dropping the parameter on iOS.
- `openLocationSettings()` and `openDeviceLocationSettings()` both open the
  app's Settings page on iOS (Apple permits no deep link to the system-wide
  Location Services screen); documented as a platform difference, not a bug.

## [0.1.1] - 2026-07-17

### Changed
- Documentation only — no code changes. Android compatibility is presented
  capability-first (tracking works on every supported version; per-version
  differences are one-time grant steps the plugin guides the user through),
  minSdk corrected to 31, architecture diagram updated to the current
  components.

## [0.1.0] - 2026-07-17

### Fixed (crashes)
- **App no longer crashes when the user grants only "While using the app"**: both
  foreground services promote themselves with `startForeground()` FIRST and stop
  gracefully on permission problems. Previously the service stopped itself without
  ever calling `startForeground()`, and Android killed the whole app with
  `ForegroundServiceDidNotStartInTimeException` — repeatedly, via the sticky
  restart and the 30-minute watchdog alarm.
- Restart receivers (`ServiceRestartReceiver`, boot/task-removed paths) now check
  the persisted session state and permissions before attempting a restart, ending
  the background crash loop.
- Work-hour uploader no longer NPE-crashes when the plugin instance is gone
  (app process killed, service restarted standalone).
- `SecurityException` from revoked permissions during tracking is caught and
  surfaced as an `error` event instead of crashing.

### Fixed (correctness)
- **Tracking now works with foreground-only permission**: previously the service
  demanded `ACCESS_BACKGROUND_LOCATION` and silently recorded nothing (and then
  crashed). Background permission is only needed for background restarts and its
  absence is reported, not fatal.
- **Approximate location supported**: permission checks were FINE AND COARSE, so
  the Android 12+ "Approximate" choice disabled tracking entirely; now fine OR
  coarse suffices and the granted accuracy tier is reported.
- **Work-hour tracking actually works in the background**: `startWorkHourTracking`
  never started its foreground service (sampling died with the app process), and
  the uploader always uploaded an empty list — no fix ever reached the server.
- **Duplicate location events fixed**: the service registered a second
  `LocationBroadcastReceiver` in the same process, delivering every fix twice.
- **Duplicate route points fixed**: task tracking ran a second, in-process pipeline
  writing the same fixes to the same SQLite table as the service.
- **Distance no longer inflated**: travelled distance was accumulated before the
  accuracy filter, double-counting stretches around rejected fixes. It is now
  computed over accepted fixes only and resumes correctly after service restarts.
- System restarts no longer corrupt sessions with `default_reference` /
  `auto_restart` references — parameters persist in `TrackingStateStore` and are
  restored on every restart path.
- If the user toggles device location services off mid-session, the service now
  waits and resumes automatically when they are re-enabled.

### Added
- **Typed error contract**: every rejection carries a machine-readable `code`
  (`PERMISSION_DENIED`, `BACKGROUND_PERMISSION_DENIED`,
  `LOCATION_SERVICES_DISABLED`, `SERVICE_START_FAILED`, `LOCATION_UNAVAILABLE`,
  `MISSING_PARAMETER`, `NOT_FOUND`, `CANCELLED`, `INTERNAL_ERROR`), and a new
  `error` event reports asynchronous failures with `source` and `fatal` flags.
- **Progressive-accuracy `getCurrentLocation({ targetAccuracy, timeout })`**:
  streams live fixes as `currentLocation` events until the requested accuracy is
  met; resolves the best fix on timeout. `cancelCurrentLocationRequest()` cancels.
- `requestPermissions({ permissions })` can request the foreground and background
  tiers separately (recommended incremental UX on Android 11+).
- `checkPermissions()` now reports the granted accuracy tier (`fine`/`coarse`/
  `none`) and real `prompt`/`denied` states.
- `getTrackingStatus()` to resync UI with the native session after app restarts.
- `openDeviceLocationSettings()` for the GPS-off case.
- `startTracking` options: `maxAccuracy` (fix filter), `notificationTitle`,
  `notificationText`; resolves `{ backgroundLocationGranted, accuracy }`.
- Bounded, service-owned work-hour upload queue with real batch uploads, retry
  and offline handling; `workHourLocationUploaded` now reports `{ success, count,
  error? }`.
- Unit test suite for the extracted pure logic (filtering, distance, progressive
  accuracy watcher, queue, state store) — 33 tests.
- Web implementation backed by the real W3C Geolocation API, matching the native
  error contract.

### Changed
- Internals reorganised along SOLID lines: `LocationFilter`, `DistanceTracker`,
  `CurrentLocationWatcher`, `WorkHourLocationQueue`, `TrackingStateStore` (behind
  `KeyValueStore`), `NotificationFactory`, `ErrorCodes` — services and plugin
  delegate instead of duplicating logic. `LocationCoordinator` (dead second
  pipeline) removed.
- Plugin manifest trimmed to least privilege: removed `CAMERA`,
  `READ_PHONE_STATE`, WiFi-state and exact-alarm permissions that leaked into
  consuming apps.
- `startLocationStatusTracking()` no longer demands location permission (reading
  the GPS toggle requires none), so an "enable location" banner can be shown
  before any permission prompt.
- Tracking sessions survive the WebView/plugin being destroyed: the plugin no
  longer stops the services in `handleOnDestroy`.

## [Unreleased]

### Added
- Comprehensive documentation suite
- Setup guide with step-by-step integration instructions
- Developer guide with architecture overview
- API documentation with detailed examples
- Work hour tracking with server integration
- Offline queue support for work hour locations
- Intelligent location service coordination
- Modular architecture with separated concerns

### Changed
- Refactored monolithic plugin into modular components
- Improved error handling and validation
- Enhanced battery optimization through intelligent coordination
- Better permission management with granular controls

### Fixed
- Compilation errors with missing imports
- Class naming conflicts during refactoring
- Permission state access modifier issues
- Service lifecycle management issues

## [0.0.17] - 2025-09-18

### Added
- LocationPermissionManager for centralized permission handling
- LocationDataManager for location data processing and validation
- LocationTrackingManager for tracking coordination
- LocationCoordinator for parallel service management
- WorkHourLocationUploader for server communication
- Background location service improvements
- Enhanced location filtering and validation

### Changed
- Separated concerns from monolithic BackgroundLocationPlugin
- Improved code organization and maintainability
- Enhanced location accuracy validation
- Better resource management and cleanup

### Fixed
- Background service permission issues
- Location update coordination problems
- Memory leaks in service management
- Database operation efficiency

## [0.0.16] - Previous Release

### Added
- Basic work hour tracking functionality
- Server upload capabilities
- Offline location queueing

### Changed
- Improved location service stability
- Enhanced permission request flow

### Fixed
- Background service lifecycle issues
- Location permission edge cases

## [0.0.15] - Previous Release

### Added
- Parallel service coordination system
- LocationCoordinator singleton for service management
- Work hour tracking with configurable intervals

### Changed
- Optimized battery usage through service coordination
- Improved location request parameter management

### Fixed
- Multiple service conflicts
- Battery drain from duplicate location requests

## [0.0.14] - Previous Release

### Added
- Enhanced location validation
- Distance calculation improvements
- Better error handling for edge cases

### Changed
- Improved location accuracy filtering
- Enhanced database performance

### Fixed
- Location data inconsistencies
- Database connection management

## [0.0.13] - Previous Release

### Added
- Work hour tracking foundation
- Server communication framework
- Authentication token support

### Changed
- Enhanced plugin architecture
- Improved service management

### Fixed
- Service startup reliability
- Permission handling edge cases

## [0.0.12] - Previous Release

### Added
- Background location service improvements
- Enhanced notification management
- Better foreground service handling

### Changed
- Improved service lifecycle management
- Enhanced error reporting

### Fixed
- Service notification issues
- Background service stability

## [0.0.11] - Previous Release

### Added
- Enhanced permission management
- Better location service status monitoring
- Improved error handling

### Changed
- Updated permission request flow
- Enhanced service coordination

### Fixed
- Permission state synchronization
- Location service detection

## [0.0.10] - Previous Release

### Added
- Basic background location tracking
- SQLite database storage
- Location service monitoring

### Changed
- Improved location accuracy
- Enhanced data persistence

### Fixed
- Location update reliability
- Database initialization

## [0.0.9] - Previous Release

### Added
- Foreground service implementation
- Location permission handling
- Basic location tracking functionality

### Changed
- Enhanced service architecture
- Improved location provider setup

### Fixed
- Service startup issues
- Location provider initialization

## [0.0.8] - Previous Release

### Added
- Initial location tracking implementation
- Basic permission management
- SQLite database integration

### Changed
- Core plugin structure
- Location service framework

### Fixed
- Initial setup and configuration issues

## [0.0.7] - Previous Release

### Added
- Core Capacitor plugin structure
- Basic Android implementation
- Location service foundation

## Earlier Versions

- Initial development and proof of concept
- Basic location tracking experiments
- Plugin architecture design

---

## Migration Guide

### From 0.0.16 to 0.0.17

The plugin has been significantly refactored for better maintainability and performance. While the public API remains largely compatible, there are some internal changes:

#### Breaking Changes
- None for public API users
- Internal class structure has changed (affects custom extensions)

#### New Features
- Modular architecture with separated components
- Enhanced error handling and validation
- Improved battery optimization
- Better documentation and examples

#### Migration Steps
1. Update to version 0.0.17: `npm install elsapiens-background-location@0.0.17`
2. Run `npx cap sync` to update native dependencies
3. Test your existing implementation (API compatibility maintained)
4. Review new documentation for enhanced features
5. Consider adopting new best practices from updated examples

### From Earlier Versions

For migrations from versions prior to 0.0.16, please refer to the specific version notes above and consider reviewing the complete [Setup Guide](SETUP_GUIDE.md) for the most current implementation patterns.

## Development Notes

### Version 0.0.17 Development Focus
- **Architecture**: Complete refactoring from monolithic to modular design
- **Documentation**: Comprehensive documentation suite creation
- **Testing**: Enhanced error handling and edge case coverage
- **Performance**: Battery optimization through intelligent service coordination
- **Maintainability**: Separated concerns for easier development and testing

### Future Roadmap
- **iOS Support**: Native iOS implementation
- **Web Support**: Full web platform support for development
- **Enhanced Analytics**: Built-in location analytics and reporting
- **Geofencing**: Geofence monitoring capabilities
- **Advanced Filtering**: More sophisticated location filtering algorithms

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details on:

- How to report bugs
- How to suggest enhancements
- Development setup
- Code style guidelines
- Pull request process

## Support

- **Documentation**: Complete guides available in the repository
- **Issues**: [GitHub Issues](https://github.com/your-org/elsapiens-background-location/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/elsapiens-background-location/discussions)
- **Email**: support@elsapiens.com