# Background Location Plugin - API Documentation

## Overview

The Background Location Plugin provides comprehensive location tracking capabilities for Capacitor applications. It supports both task-based tracking for detailed route recording and work hour tracking for periodic location uploads to a server.

## Installation

```bash
npm install elsapiens-background-location
npx cap sync
```

## Import

```typescript
import { BackgroundLocation } from 'elsapiens-background-location';
```

## API Reference

### Permission Management

#### `checkPermissions()`

Check the current status of all location-related permissions.

**Returns:** `Promise<PermissionStatus>`

```typescript
const permissions = await BackgroundLocation.checkPermissions();
console.log('Location permission:', permissions.location);
console.log('Background location:', permissions.backgroundLocation);
console.log('Foreground service:', permissions.foregroundService);
```

**PermissionStatus Interface:**
```typescript
interface PermissionStatus {
  location: 'granted' | 'denied' | 'prompt';
  backgroundLocation: 'granted' | 'denied' | 'prompt';
  foregroundService: 'granted' | 'denied' | 'prompt';
}
```

#### `requestPermissions()`

Request location permissions from the user. Will automatically request foreground permissions first, then background permissions.

**Returns:** `Promise<PermissionStatus>`

```typescript
const permissions = await BackgroundLocation.requestPermissions();
if (permissions.location === 'granted') {
  console.log('Location permissions granted');
}
```

#### `isLocationServiceEnabled()`

Check if device location services are enabled.

**Returns:** `Promise<{ enabled: boolean }>`

```typescript
const status = await BackgroundLocation.isLocationServiceEnabled();
if (!status.enabled) {
  console.log('Please enable location services');
}
```

#### `openLocationSettings()`

Open the device's location settings page.

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.openLocationSettings();
```

### Task-Based Location Tracking

#### `startTracking(options)`

Start detailed location tracking for a specific task or route.

**Parameters:**
- `reference` (string, required): Unique identifier for this tracking session
- `interval` (number, optional): Update interval in milliseconds (default: 3000)
- `minDistance` (number, optional): Minimum distance in meters before update (default: 10)
- `highAccuracy` (boolean, optional): Use high accuracy mode (default: true)

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.startTracking({
  reference: 'task_12345',
  interval: 5000,        // Update every 5 seconds
  minDistance: 15,       // Only when moved 15+ meters
  highAccuracy: true     // Use GPS for high accuracy
});
```

#### `stopTracking()`

Stop the current task-based location tracking.

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.stopTracking();
```

### Work Hour Tracking

#### `startWorkHourTracking(options)`

Start work hour tracking that periodically uploads location to a server.

**Parameters:**
- `engineerId` (string, required): Unique identifier for the engineer/user
- `serverUrl` (string, required): HTTP endpoint for location uploads
- `uploadInterval` (number, optional): Upload interval in milliseconds (default: 300000 = 5 minutes)
- `authToken` (string, optional): Authentication token for server requests
- `enableOfflineQueue` (boolean, optional): Enable offline queueing (default: true)
- `minDistance` (number, optional): Meters of movement before a new fix is queued (default: 50). Use `0` to send a fix every interval even when stationary, so uploads double as a heartbeat

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.startWorkHourTracking({
  engineerId: 'engineer_123',
  serverUrl: 'https://api.example.com/work-hour-locations',
  uploadInterval: 300000,  // 5 minutes
  authToken: 'bearer_token_xyz',
  enableOfflineQueue: true
});
```

#### `stopWorkHourTracking()`

Stop work hour tracking.

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.stopWorkHourTracking();
```

#### `isWorkHourTrackingActive()`

Check if work hour tracking is currently active.

**Returns:** `Promise<{ active: boolean }>`

```typescript
const status = await BackgroundLocation.isWorkHourTrackingActive();
console.log('Work hour tracking active:', status.active);
```

#### `getQueuedWorkHourLocations()`

Get locations that are queued for upload but haven't been sent to the server yet.

**Returns:** `Promise<{ locations: WorkHourLocationData[] }>`

```typescript
const result = await BackgroundLocation.getQueuedWorkHourLocations();
console.log('Queued locations:', result.locations.length);
```

**WorkHourLocationData Interface:**
```typescript
interface WorkHourLocationData {
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
  engineerId: string;
  altitude?: number;
  speed?: number;
  heading?: number;
}
```

#### `clearQueuedWorkHourLocations()`

Clear all queued work hour locations.

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.clearQueuedWorkHourLocations();
```

### Location Data Access

#### `getCurrentLocation()`

Get the device's current location immediately.

**Returns:** `Promise<LocationData>`

```typescript
const location = await BackgroundLocation.getCurrentLocation();
console.log(`Current location: ${location.latitude}, ${location.longitude}`);
```

#### `getStoredLocations(options)`

Get all stored locations for a specific tracking reference.

**Parameters:**
- `reference` (string, required): The tracking reference to retrieve

**Returns:** `Promise<{ locations: LocationData[] }>`

```typescript
const result = await BackgroundLocation.getStoredLocations({
  reference: 'task_12345'
});
console.log('Stored locations:', result.locations);
```

#### `getLastLocation(options)`

Get the most recent location for a specific tracking reference.

**Parameters:**
- `reference` (string, required): The tracking reference to retrieve

**Returns:** `Promise<LocationData>`

```typescript
const location = await BackgroundLocation.getLastLocation({
  reference: 'task_12345'
});
```

#### `clearStoredLocations()`

Clear all stored location data.

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.clearStoredLocations();
```

**LocationData Interface:**
```typescript
interface LocationData {
  reference: string;
  index: number;
  latitude: number;
  longitude: number;
  altitude?: number;
  accuracy: number;
  speed?: number;
  heading?: number;
  altitudeAccuracy?: number;
  totalDistance?: number;
  timestamp: number;
}
```

### Location Status Monitoring

#### `startLocationStatusTracking()`

Start monitoring device location service status changes.

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.startLocationStatusTracking();

// Listen for status changes
BackgroundLocation.addListener('locationStatus', (status) => {
  console.log('Location services enabled:', status.enabled);
});
```

#### `stopLocationStatusTracking()`

Stop monitoring location service status changes.

**Returns:** `Promise<void>`

```typescript
await BackgroundLocation.stopLocationStatusTracking();
```

## Event Listeners

The plugin emits several events that you can listen to:

### `locationUpdate`

Fired when a new location is recorded during task tracking.

```typescript
BackgroundLocation.addListener('locationUpdate', (location: LocationData) => {
  console.log('New location:', location.latitude, location.longitude);
  console.log('Total distance:', location.totalDistance);
});
```

### `workHourLocationUpdate`

Fired when a new location is captured during work hour tracking.

```typescript
BackgroundLocation.addListener('workHourLocationUpdate', (location: WorkHourLocationData) => {
  console.log('Work hour location:', location.latitude, location.longitude);
  console.log('Engineer ID:', location.engineerId);
});
```

### `locationStatus`

Fired when device location service status changes.

```typescript
BackgroundLocation.addListener('locationStatus', (status: { enabled: boolean }) => {
  if (status.enabled) {
    console.log('Location services are enabled');
  } else {
    console.log('Location services are disabled');
  }
});
```

## Complete Usage Example

```typescript
import { BackgroundLocation } from 'elsapiens-background-location';

export class LocationService {
  
  async initializeLocationServices() {
    // Check if location services are enabled
    const serviceStatus = await BackgroundLocation.isLocationServiceEnabled();
    if (!serviceStatus.enabled) {
      await BackgroundLocation.openLocationSettings();
      return false;
    }
    
    // Check permissions
    const permissions = await BackgroundLocation.checkPermissions();
    if (permissions.location !== 'granted') {
      const newPermissions = await BackgroundLocation.requestPermissions();
      if (newPermissions.location !== 'granted') {
        throw new Error('Location permissions required');
      }
    }
    
    return true;
  }
  
  async startTaskTracking(taskId: string) {
    await this.initializeLocationServices();
    
    // Set up location update listener
    BackgroundLocation.addListener('locationUpdate', (location) => {
      console.log(`Task ${taskId} location:`, location);
      this.saveLocationToLocalStorage(location);
    });
    
    // Start tracking
    await BackgroundLocation.startTracking({
      reference: taskId,
      interval: 3000,      // 3 second updates
      minDistance: 10,     // 10 meter minimum distance
      highAccuracy: true   // Use GPS
    });
    
    console.log(`Started tracking task: ${taskId}`);
  }
  
  async startWorkDay(engineerId: string) {
    await this.initializeLocationServices();
    
    // Set up work hour location listener
    BackgroundLocation.addListener('workHourLocationUpdate', (location) => {
      console.log('Work hour location captured:', location);
    });
    
    // Start work hour tracking
    await BackgroundLocation.startWorkHourTracking({
      engineerId: engineerId,
      serverUrl: 'https://api.company.com/work-locations',
      uploadInterval: 300000,  // 5 minutes
      authToken: await this.getAuthToken(),
      enableOfflineQueue: true
    });
    
    console.log(`Started work hour tracking for: ${engineerId}`);
  }
  
  async stopAllTracking() {
    // Stop both types of tracking
    await BackgroundLocation.stopTracking();
    await BackgroundLocation.stopWorkHourTracking();
    
    console.log('All tracking stopped');
  }
  
  async getTrackingStatus() {
    const workHourStatus = await BackgroundLocation.isWorkHourTrackingActive();
    const queuedLocations = await BackgroundLocation.getQueuedWorkHourLocations();
    
    return {
      workHourActive: workHourStatus.active,
      queuedCount: queuedLocations.locations.length
    };
  }
  
  private saveLocationToLocalStorage(location: LocationData) {
    // Save location data locally
    const stored = JSON.parse(localStorage.getItem('taskLocations') || '[]');
    stored.push(location);
    localStorage.setItem('taskLocations', JSON.stringify(stored));
  }
  
  private async getAuthToken(): Promise<string> {
    // Get authentication token for server requests
    return localStorage.getItem('authToken') || '';
  }
}
```

## Error Handling

All plugin methods return Promises and should be wrapped in try-catch blocks:

```typescript
try {
  await BackgroundLocation.startTracking({ reference: 'task_123' });
  console.log('Tracking started successfully');
} catch (error) {
  console.error('Failed to start tracking:', error.message);
  
  // Handle specific error cases
  if (error.message.includes('permission')) {
    // Request permissions
    await BackgroundLocation.requestPermissions();
  } else if (error.message.includes('location service')) {
    // Prompt user to enable location services
    await BackgroundLocation.openLocationSettings();
  }
}
```

## Platform Support

- **Android**: Full support for all features
- **iOS**: Not currently supported (Android-only plugin)
- **Web**: Stub implementation for development (logs warnings)

## Performance Considerations

### Battery Optimization

- **Task Tracking**: Uses high accuracy GPS, suitable for short-duration detailed tracking
- **Work Hour Tracking**: Uses balanced power mode, optimized for all-day usage
- **Intelligent Coordination**: Multiple tracking modes share a single location request to minimize battery drain

### Data Usage

- **Local Storage**: Task tracking data is stored locally in SQLite database
- **Server Uploads**: Work hour tracking uploads batched location data periodically
- **Offline Support**: Work hour locations are queued when offline and uploaded when connection is restored

### Memory Management

- **Automatic Cleanup**: Plugin automatically cleans up resources when tracking stops
- **Queue Management**: Work hour location queue has built-in size limits
- **Efficient Processing**: Location updates are filtered to avoid unnecessary processing

## Troubleshooting

### Common Issues

1. **"Permission denied" errors**
   - Ensure all required permissions are granted
   - Check that background location permission is granted for work hour tracking

2. **"Location service disabled" errors**
   - Verify device location services are enabled
   - Use `openLocationSettings()` to guide users to settings

3. **No location updates received**
   - Check that tracking has been started with `startTracking()` or `startWorkHourTracking()`
   - Verify the device has GPS signal (try outdoors)
   - Check that minimum distance/interval settings aren't too restrictive

4. **Work hour locations not uploading**
   - Verify server URL is accessible
   - Check authentication token is valid
   - Review server logs for upload errors
   - Use `getQueuedWorkHourLocations()` to check queue status