# Work Hour Tracking Feature

The elsapiens-background-location plugin now supports continuous location tracking during work hours, automatically uploading location data to a server every 5 minutes.

## Overview

Work Hour Tracking is designed for tracking engineer locations throughout their work day, separate from task-based tracking. It provides:

- **Continuous tracking**: Automatically captures location every 5 minutes during work hours
- **Server uploads**: Sends location data to configurable server endpoint
- **Offline support**: Queues locations when offline and uploads when connection is restored
- **Background operation**: Runs as Android foreground service for reliable tracking
- **Separate from task tracking**: Independent of existing task-based location features

## Features

### Core Functionality
- Start/stop work hour tracking for specific engineer
- Configurable upload interval (default: 5 minutes)
- Automatic retry with offline queue
- Real-time status monitoring
- Queue management (view/clear pending uploads)

### Technical Details
- Android foreground service for background operation
- HTTP POST uploads with authentication support
- Location accuracy optimized for battery life (balanced power)
- Minimum 50-meter distance filter by default (`minDistance`); set `minDistance: 0` for a fix every interval even when stationary, so the server can tell "not moving" from "phone off"
- Automatic permission handling

## Usage

### Angular Service Integration

```typescript
import { LocationTrackingService } from './location-tracking.service';

// Inject the service
constructor(private locationService: LocationTrackingService) {}

// Start work hour tracking
async startWorkHours() {
  const success = await this.locationService.startWorkHourTracking(
    'engineer_id_123',  // Optional: defaults to current user
    'https://api.example.com/work-hour-locations',  // Optional: defaults to environment API
    'auth_token_xyz'    // Optional: authentication token
  );
  
  if (success) {
    console.log('Work hour tracking started');
  }
}

// Stop work hour tracking
async stopWorkHours() {
  const success = await this.locationService.stopWorkHourTracking();
  if (success) {
    console.log('Work hour tracking stopped');
  }
}

// Check tracking status
async checkStatus() {
  const isActive = await this.locationService.isWorkHourTrackingActiveStatus();
  console.log('Work hour tracking active:', isActive);
}

// Get queued locations (not yet uploaded)
async getQueuedLocations() {
  const locations = await this.locationService.getQueuedWorkHourLocations();
  console.log('Queued locations:', locations);
}

// Clear queued locations
async clearQueue() {
  await this.locationService.clearQueuedWorkHourLocations();
}
```

### Direct Plugin Usage

```typescript
import { BackgroundLocation } from 'elsapiens-background-location';

// Start work hour tracking
await BackgroundLocation.startWorkHourTracking({
  engineerId: 'engineer_123',
  uploadInterval: 300000, // 5 minutes in milliseconds
  serverUrl: 'https://api.example.com/work-hour-locations',
  authToken: 'bearer_token_xyz',
  enableOfflineQueue: true
});

// Listen for location updates
const listener = await BackgroundLocation.addListener('workHourLocationUpdate', 
  (data) => {
    console.log('New work hour location:', data);
  }
);

// Stop tracking
await BackgroundLocation.stopWorkHourTracking();

// Check if active
const { active } = await BackgroundLocation.isWorkHourTrackingActive();

// Get queued locations
const { locations } = await BackgroundLocation.getQueuedWorkHourLocations();

// Clear queue
await BackgroundLocation.clearQueuedWorkHourLocations();
```

## Server Endpoint Requirements

### Expected Request Format

The plugin sends HTTP POST requests to the configured server URL with the following format:

```json
{
  "engineerId": "engineer_123",
  "timestamp": 1640995200000,
  "locations": [
    {
      "latitude": 37.7749,
      "longitude": -122.4194,
      "accuracy": 15.0,
      "timestamp": 1640995200000
    },
    {
      "latitude": 37.7750,
      "longitude": -122.4195,
      "accuracy": 12.0,
      "timestamp": 1640995500000
    }
  ]
}
```

### Required Headers

- `Content-Type: application/json`
- `User-Agent: SignalScout-WorkHourTracker/1.0`
- `Authorization: Bearer <token>` (if authToken provided)

### Expected Response

- **Success**: HTTP status 200-299
- **Failure**: Any other HTTP status code will trigger retry

### Server Implementation Example

```javascript
// Node.js/Express example
app.post('/work-hour-locations', (req, res) => {
  const { engineerId, timestamp, locations } = req.body;
  
  // Validate request
  if (!engineerId || !locations || !Array.isArray(locations)) {
    return res.status(400).json({ error: 'Invalid request format' });
  }
  
  // Save locations to database
  try {
    locations.forEach(location => {
      saveWorkHourLocation({
        engineerId,
        latitude: location.latitude,
        longitude: location.longitude,
        accuracy: location.accuracy,
        timestamp: new Date(location.timestamp),
        receivedAt: new Date()
      });
    });
    
    res.status(200).json({ 
      success: true, 
      saved: locations.length 
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to save locations' });
  }
});
```

## Configuration Options

### StartWorkHourTracking Options

```typescript
interface WorkHourTrackingOptions {
  engineerId: string;           // Required: Unique identifier for engineer
  uploadInterval?: number;      // Optional: Upload interval in ms (default: 300000 = 5 min)
  serverUrl: string;           // Required: HTTP endpoint for uploads
  authToken?: string;          // Optional: Authentication token
  enableOfflineQueue?: boolean; // Optional: Enable offline queueing (default: true)
  minDistance?: number;         // Optional: Meters of movement per fix (default: 50; 0 = heartbeat)
}
```

### Location Data Format

```typescript
interface WorkHourLocationData {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude?: number;
  speed?: number;
  heading?: number;
  timestamp: number;
  engineerId?: string;
}
```

## Permissions

The work hour tracking feature requires the same permissions as regular location tracking:

### Android Manifest (automatically included)
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

### Runtime Permission Handling

The plugin automatically handles permission requests. Use the existing permission methods:

```typescript
// Check permissions
const permissions = await BackgroundLocation.checkPermissions();

// Request permissions
await BackgroundLocation.requestPermissions();
```

## Notifications

Work hour tracking shows a persistent notification while active:

- **Title**: "Work Hour Tracking"
- **Content**: "Tracking location during work hours for engineer: {engineerId}"
- **Icon**: Location icon
- **Category**: Service notification
- **Priority**: Low (minimal disruption)

## Battery Optimization

The work hour tracking feature is optimized for battery life:

- **Balanced power accuracy**: Uses network and GPS efficiently
- **Minimum distance filter**: Only logs locations when moved 50+ meters
- **Configurable intervals**: Default 5-minute interval reduces battery drain
- **Smart queuing**: Reduces network usage when offline

## Troubleshooting

### Common Issues

1. **Tracking not starting**
   - Check location permissions are granted
   - Verify background location permission is allowed
   - Ensure device location services are enabled

2. **Locations not uploading**
   - Check network connectivity
   - Verify server URL is accessible
   - Check authentication token if required
   - Review server logs for errors

3. **High battery usage**
   - Increase upload interval (e.g., 10 minutes instead of 5)
   - Check if multiple location services are running
   - Review device battery optimization settings

### Debug Information

```typescript
// Check tracking status
const isActive = await BackgroundLocation.isWorkHourTrackingActive();
console.log('Work hour tracking active:', isActive);

// Check queued locations count
const { locations } = await BackgroundLocation.getQueuedWorkHourLocations();
console.log('Queued locations:', locations.length);

// Check permissions
const permissions = await BackgroundLocation.checkPermissions();
console.log('Permissions:', permissions);
```

## Migration Guide

### From Task-Based Tracking

Work hour tracking is independent of task-based tracking. You can run both simultaneously:

```typescript
// Start task tracking (existing)
await locationService.startTracking('task_123');

// Start work hour tracking (new)
await locationService.startWorkHourTracking('engineer_123');

// Both run independently
// Task tracking: detailed route tracking
// Work hour tracking: periodic location pings
```

### Upgrading Existing Apps

1. Update the plugin to latest version
2. Add work hour tracking methods to your service
3. Configure server endpoint for work hour data
4. Add UI controls for starting/stopping work hour tracking
5. Test permissions and background functionality

## API Reference

### Plugin Methods

- `startWorkHourTracking(options)` - Start work hour tracking
- `stopWorkHourTracking()` - Stop work hour tracking  
- `isWorkHourTrackingActive()` - Check if tracking is active
- `getQueuedWorkHourLocations()` - Get pending upload queue
- `clearQueuedWorkHourLocations()` - Clear upload queue

### Event Listeners

- `workHourLocationUpdate` - Fired when new location is captured

### Service Methods (Angular)

- `startWorkHourTracking(engineerId?, serverUrl?, authToken?)` - Start tracking
- `stopWorkHourTracking()` - Stop tracking
- `isWorkHourTrackingActiveStatus()` - Get tracking status
- `getQueuedWorkHourLocations()` - Get queued locations
- `clearQueuedWorkHourLocations()` - Clear queue
- `getWorkHourTrackingStatus()` - Get local tracking status