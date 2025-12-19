# Screen Sharing Orientation Synchronization Fix

## Problem
When User A shares their screen in portrait mode but displays landscape content (e.g., fullscreen YouTube video), User B sees the content incorrectly:
- User B sees portrait-shaped frames with landscape video in the middle
- Black bars appear on top and bottom
- Receiver orientation doesn't match sender's actual display orientation

## Root Cause
MediaProjection API captures frames in the device's **physical orientation**, not the content orientation. When User A's device is physically portrait but shows landscape content, the capture sends portrait-shaped frames (e.g., 720x1280). The landscape video is just rotated content within those portrait frames.

Previous attempts failed because:
1. Tried locking receiver orientation based on aspect ratio - but aspect ratio remains portrait (height > width)
2. Tried preserving orientation in capture dimensions - but MediaProjection always captures in device orientation
3. Video frames don't carry rotation metadata through WebRTC

## Solution
Implemented a **rotation metadata signaling system** that transmits the sender's display rotation separately:

### 1. Firebase Signaling (FirebaseSignaling.kt)
- Added `sendDisplayRotation()` - sends rotation in degrees (0, 90, 180, 270)
- Added `listenForDisplayRotation()` - receives rotation updates from remote peer
- Rotation changes are tracked with versioning to prevent duplicate processing

### 2. Sender Side (WebRtcManager.kt)
- Added `startRotationMonitoring()` - monitors device rotation every 500ms
- Added `getDisplayRotation()` - gets current display rotation from WindowManager
- Rotation is sent to Firebase when it changes during screen sharing
- Monitoring starts when screen share begins, stops when it ends

### 3. Receiver Side (RoomScreen.kt)
- Added `remoteDisplayRotation` state flow from WebRtcManager
- **Fullscreen mode**: Locks receiver orientation based on sender's rotation
  - 90° or 270° → Force landscape
  - 0° or 180° → Force portrait
- **Preview mode**: Uses rotation info for proper display keying
- Views are re-keyed when sender rotation changes for proper layout

## How It Works
1. User A starts screen sharing
2. Sender monitors display rotation (0°, 90°, 180°, 270°)
3. Rotation changes are sent via Firebase in real-time
4. User B receives rotation updates
5. User B's device orientation is locked to match sender's orientation in fullscreen
6. Result: User B sees exactly what User A sees, including orientation

## Key Features
- Real-time rotation tracking (500ms polling)
- Automatic receiver orientation locking in fullscreen
- Works for both portrait ↔ landscape transitions
- No impact on video quality or bitrate
- Minimal latency (Firebase real-time updates)

## Testing Instructions
1. Open app on two devices (User A and User B)
2. User A: Start screen sharing in portrait mode
3. User A: Open YouTube, play landscape video, enter fullscreen
4. User A's screen rotates to landscape
5. **Expected**: User B's fullscreen view automatically rotates to landscape and displays correctly
6. **Expected**: No black bars, full landscape display on User B

## Files Modified
- `FirebaseSignaling.kt` - Added rotation signaling methods
- `WebRtcManager.kt` - Added rotation monitoring and state management
- `RoomScreen.kt` - Added rotation-based orientation locking
