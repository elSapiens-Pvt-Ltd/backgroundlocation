import { WebPlugin } from '@capacitor/core';

import type {
  BackgroundLocationPlugin,
  CurrentLocation,
  CurrentLocationOptions,
  Geofence,
  GeofenceTransitionEvent,
  LocationData,
  PermissionStatus,
  RequestPermissionsOptions,
  StartTrackingOptions,
  StartTrackingResult,
  TrackingStatus,
  WorkHourLocationData,
  WorkHourTrackingOptions,
  BatteryOptimizationStatus,
} from './definitions';

/**
 * Browser implementation backed by the W3C Geolocation API.
 *
 * Real background tracking and work-hour uploads are native-only; on the web this
 * implementation records fixes in memory while the page is open so app code can be
 * developed and demoed in a browser. Error codes match the native contract.
 */
export class BackgroundLocationWeb extends WebPlugin implements BackgroundLocationPlugin {
  private locations: LocationData[] = [];
  private reference = 'web-tracking';
  private watchId: number | null = null;
  private tracking = false;
  private currentWatchId: number | null = null;
  private totalDistanceKm = 0;

  async checkPermissions(): Promise<PermissionStatus> {
    let location: PermissionStatus['location'] = 'prompt';
    try {
      if (navigator.permissions) {
        const result = await navigator.permissions.query({ name: 'geolocation' as PermissionName });
        location = result.state === 'granted' ? 'granted' : result.state === 'denied' ? 'denied' : 'prompt';
      }
    } catch {
      // permissions API unavailable — leave as prompt
    }
    return {
      location,
      backgroundLocation: location, // No separate tier on the web
      accuracy: location === 'granted' ? 'fine' : 'none',
      foregroundService: 'granted',
    };
  }

  async requestPermissions(options?: RequestPermissionsOptions): Promise<PermissionStatus> {
    void options; // Web has a single permission tier; both requests map to the browser prompt.
    // The browser prompts on first geolocation use; trigger one to surface the dialog.
    await new Promise<void>((resolve) => {
      if (!navigator.geolocation) return resolve();
      navigator.geolocation.getCurrentPosition(() => resolve(), () => resolve(), { timeout: 10000 });
    });
    return this.checkPermissions();
  }

  async isLocationServiceEnabled(): Promise<{ enabled: boolean }> {
    return { enabled: !!navigator.geolocation };
  }

  async openLocationSettings(): Promise<void> {
    console.warn('BackgroundLocation: openLocationSettings is not available on web');
  }

  async openDeviceLocationSettings(): Promise<void> {
    console.warn('BackgroundLocation: openDeviceLocationSettings is not available on web');
  }

  async isIgnoringBatteryOptimizations(): Promise<BatteryOptimizationStatus> {
    // Browsers have no battery-optimization setting to be exempt from.
    return { ignoring: true };
  }

  async openBatteryOptimizationSettings(): Promise<void> {
    console.warn('BackgroundLocation: openBatteryOptimizationSettings is not available on web');
  }

  async startTracking(options: StartTrackingOptions): Promise<StartTrackingResult> {
    if (!options?.reference) {
      throw this.buildError('Missing required reference parameter', 'MISSING_PARAMETER');
    }
    if (!navigator.geolocation) {
      throw this.buildError('Geolocation is not available in this browser', 'LOCATION_SERVICES_DISABLED');
    }
    this.stopWatch();
    this.reference = options.reference;
    this.tracking = true;
    this.totalDistanceKm = 0;

    const maxAccuracy = options.maxAccuracy ?? 30;
    this.watchId = navigator.geolocation.watchPosition(
      (pos) => {
        if (!this.tracking) return;
        if (pos.coords.accuracy > maxAccuracy) return;
        const previous = this.locations.length
          ? this.locations[this.locations.length - 1]
          : undefined;
        if (previous) {
          this.totalDistanceKm += haversineKm(
            previous.latitude, previous.longitude,
            pos.coords.latitude, pos.coords.longitude,
          );
        }
        const data: LocationData = {
          reference: this.reference,
          index: this.locations.length,
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
          altitude: pos.coords.altitude ?? undefined,
          speed: pos.coords.speed ?? undefined,
          heading: pos.coords.heading ?? undefined,
          totalDistance: this.totalDistanceKm,
          timestamp: pos.timestamp,
        };
        this.locations.push(data);
        this.notifyListeners('locationUpdate', data);
      },
      (err) => {
        this.notifyListeners('error', {
          code: err.code === err.PERMISSION_DENIED ? 'PERMISSION_DENIED' : 'LOCATION_UNAVAILABLE',
          message: err.message,
          source: 'taskTracking',
          fatal: err.code === err.PERMISSION_DENIED,
        });
      },
      { enableHighAccuracy: options.highAccuracy ?? true },
    );

    return { backgroundLocationGranted: false, accuracy: 'fine' };
  }

  async stopTracking(): Promise<void> {
    this.tracking = false;
    this.stopWatch();
  }

  async getTrackingStatus(): Promise<TrackingStatus> {
    return {
      isTracking: this.tracking,
      isWorkHourTracking: false,
      reference: this.tracking ? this.reference : null,
    };
  }

  async getCurrentLocation(options?: CurrentLocationOptions): Promise<CurrentLocation> {
    if (!navigator.geolocation) {
      throw this.buildError('Geolocation is not available in this browser', 'LOCATION_SERVICES_DISABLED');
    }
    const timeout = options?.timeout ?? 30000;
    const target = options?.targetAccuracy;

    if (target === undefined) {
      return new Promise<CurrentLocation>((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(
          (pos) => resolve(toCurrentLocation(pos, true, false)),
          (err) => reject(this.buildError(err.message,
            err.code === err.PERMISSION_DENIED ? 'PERMISSION_DENIED' : 'LOCATION_UNAVAILABLE')),
          { enableHighAccuracy: true, timeout },
        );
      });
    }

    // Progressive accuracy: stream fixes until the target accuracy is met.
    await this.cancelCurrentLocationRequest();
    return new Promise<CurrentLocation>((resolve, reject) => {
      let best: CurrentLocation | null = null;
      const timer = window.setTimeout(() => {
        this.stopCurrentWatch();
        if (best) {
          const finalFix = { ...best, isFinal: true, timedOut: true };
          this.notifyListeners('currentLocation', finalFix);
          resolve(finalFix);
        } else {
          reject(this.buildError('Could not obtain a location fix within the timeout', 'LOCATION_UNAVAILABLE'));
        }
      }, timeout);

      this.currentWatchId = navigator.geolocation.watchPosition(
        (pos) => {
          const fix = toCurrentLocation(pos, pos.coords.accuracy <= target, false);
          fix.targetAccuracy = target;
          if (!best || fix.accuracy < best.accuracy) best = fix;
          this.notifyListeners('currentLocation', fix);
          if (fix.isFinal) {
            window.clearTimeout(timer);
            this.stopCurrentWatch();
            resolve(fix);
          }
        },
        (err) => {
          window.clearTimeout(timer);
          this.stopCurrentWatch();
          reject(this.buildError(err.message,
            err.code === err.PERMISSION_DENIED ? 'PERMISSION_DENIED' : 'LOCATION_UNAVAILABLE'));
        },
        { enableHighAccuracy: true },
      );
    });
  }

  async cancelCurrentLocationRequest(): Promise<void> {
    this.stopCurrentWatch();
  }

  async getStoredLocations(options: { reference: string }): Promise<{ locations: LocationData[] }> {
    return { locations: this.locations.filter((l) => l.reference === options.reference) };
  }

  async clearStoredLocations(): Promise<void> {
    this.locations = [];
  }

  async getLastLocation(options: { reference: string }): Promise<LocationData> {
    const matches = this.locations.filter((l) => l.reference === options.reference);
    const last = matches[matches.length - 1];
    if (!last) {
      throw this.buildError(`No location found for reference: ${options.reference}`, 'NOT_FOUND');
    }
    this.notifyListeners('locationUpdate', last);
    return last;
  }

  async startLocationStatusTracking(): Promise<void> {
    setTimeout(() => this.notifyListeners('locationStatus', { enabled: !!navigator.geolocation }), 10);
  }

  async stopLocationStatusTracking(): Promise<void> {
    // Nothing to stop on web.
  }

  async startWorkHourTracking(options: WorkHourTrackingOptions): Promise<StartTrackingResult> {
    console.warn('BackgroundLocation: work-hour tracking is not available on web', options);
    throw this.unavailable('Work-hour tracking requires the native Android platform.');
  }

  async stopWorkHourTracking(): Promise<void> {
    // Nothing running on web.
  }

  async isWorkHourTrackingActive(): Promise<{ active: boolean }> {
    return { active: false };
  }

  async getQueuedWorkHourLocations(): Promise<{ locations: WorkHourLocationData[] }> {
    return { locations: [] };
  }

  async clearQueuedWorkHourLocations(): Promise<void> {
    // Nothing queued on web.
  }

  // Region monitoring has no web equivalent. The browser can watch a position,
  // but only while the page is open — which is the one thing this API exists to
  // avoid depending on, so a polling emulation here would be a worse lie than a
  // clear refusal.
  async addGeofence(options: Geofence): Promise<void> {
    console.warn('BackgroundLocation: geofencing is not available on web', options);
    throw this.unavailable('Region monitoring requires a native platform.');
  }

  async removeGeofence(): Promise<void> {
    // Nothing registered on web.
  }

  async removeAllGeofences(): Promise<void> {
    // Nothing registered on web.
  }

  async listGeofences(): Promise<{ geofences: Geofence[] }> {
    return { geofences: [] };
  }

  async getPendingGeofenceTransitions(): Promise<{ transitions: GeofenceTransitionEvent[] }> {
    return { transitions: [] };
  }

  async clearPendingGeofenceTransitions(): Promise<void> {
    // Nothing buffered on web.
  }

  private stopWatch(): void {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
  }

  private stopCurrentWatch(): void {
    if (this.currentWatchId !== null) {
      navigator.geolocation.clearWatch(this.currentWatchId);
      this.currentWatchId = null;
    }
  }

  private buildError(message: string, code: string): Error {
    const error = new Error(message) as Error & { code: string };
    error.code = code;
    return error;
  }
}

function toCurrentLocation(pos: GeolocationPosition, isFinal: boolean, timedOut: boolean): CurrentLocation {
  return {
    latitude: pos.coords.latitude,
    longitude: pos.coords.longitude,
    accuracy: pos.coords.accuracy,
    altitude: pos.coords.altitude ?? undefined,
    speed: pos.coords.speed ?? undefined,
    heading: pos.coords.heading ?? undefined,
    timestamp: pos.timestamp,
    isFinal,
    timedOut,
  };
}

function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
