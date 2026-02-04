# VoiceLink Connect

> **Ultra-low-latency Android watch-party audio bridge** built with Jetpack Compose, Firebase signaling, and a hybrid WebRTC + MediaProjection audio pipeline.

---

## 📌 Executive Overview

VoiceLink Connect is an Android 10+ (API 29) application that lets two peers join a room and share:

- **Internal / system audio** (movie, game, browser tab) captured via `AudioPlaybackCapture`.
- **Live microphone audio** with WhatsApp-grade noise suppression.
- **Screen content** rendered through WebRTC with adaptive SD/HD streaming.

The app is purpose-built for legitimate, synchronized listening or co-watching sessions, emphasizing privacy, reliability, and playful touches (e.g., a remote horn “make noise” button).

---

## ✨ Feature Highlights

| Area | Capabilities |
| --- | --- |
| **Session Flow** | 6-character room codes, Firebase-backed signaling, participant presence & notifications |
| **Audio Pipeline** | Mixed mic + system audio, priority switching, horn sound injection, silent mode, loudspeaker routing |
| **Screen Sharing** | MediaProjection-driven capture, HD/SD toggle, fullscreen viewer with rotation + gesture controls |
| **Stability** | Cloudflare Realtime TURN (1 TB/mo) with OpenRelay fallback, ICE restarts, connectivity monitor |
| **UX Details** | Compose-based welcome & room screens, animated participant banners, copy-to-clipboard invites |
| **Ops Tooling** | Firebase Crashlytics + Analytics, App Distribution testers, automated foreground services for capture |

---

## 🏗 Architecture at a Glance

```
┌────────────────────────────────────────────────────────────────┐
│                           Android App                          │
│                                                                │
│  Jetpack Compose UI                                            │
│  ├── WelcomeScreen → RoomScreen (state machine)                 │
│  ├── Participant banners, dialogs, fullscreen viewer           │
│  └── Service orchestration (AudioCaptureService, WebRtcService)│
│                                                                │
│  Domain / Managers                                             │
│  ├── WebRtcManager                                             │
│  │   ├─ FirebaseSignaling (Firestore + Anonymous Auth)         │
│  │   ├─ CloudflareTurnService (cached 24h)                     │
│  │   └─ SystemAudioMixer + VoiceNoiseSuppressor                │
│  ├── AudioCaptureManager (MediaProjection token lifecycle)     │
│  └── HornSoundPlayer, HybridAudioSource utilities              │
│                                                                │
│  Data / Infra                                                  │
│  ├── Firebase (Firestore, Auth, Analytics, Crashlytics)        │
│  └── WebRTC stack (PeerConnection, JavaAudioDeviceModule)      │
└────────────────────────────────────────────────────────────────┘
```

### Key Modules
- **`ui/screens/RoomScreen.kt`** – Manages state transitions (Initial → Creating/Joining → Connected), permission prompts, screen-share warnings, dialog UX, and fullscreen viewer controls.
- **`webrtc/WebRtcManager.kt`** – Owns PeerConnection lifecycle, track management, HD renegotiation, participant events, ICE restarts, and screen-share coordination.
- **`webrtc/FirebaseSignaling.kt`** – Firestore schema for rooms, offers/answers, ICE candidates, participant presence, HD requests, and screen-share status.
- **`audio/*`** – Custom DSP utilities: system audio capture/mixing, horn injection, hybrid audio source, and voice noise suppression.
- **`service/AudioCaptureService.kt` + `service/WebRtcService.kt`** – Foreground services that hold MediaProjection tokens and keep sessions alive under aggressive OEM policies.

---

## 🔊 Audio & Screen-Sharing Pipeline

1. **Permissions** – Runtime microphone + MediaProjection grants handled via Compose activity launchers.
2. **Foreground Service** – `AudioCaptureService` spins up Notification channel and pins capture per Android 10+ requirements.
3. **Audio Mixing Order**
   1. Horn overlay (highest priority)
   2. System audio frames (captured using MediaProjection APIs)
   3. Microphone voice processed by `VoiceNoiseSuppressor`
4. **Dynamic Muting** – When system audio is playing, remote audio is auto-muted to prevent feedback; Silent Mode overrides keep remote muted regardless.
5. **Screen Share Sync** – When either participant shares, Firestore broadcasts status so the peer auto-stops their own capture, preventing dual-share conflicts.
6. **HD/SD Toggle** – Renegotiates SDP + bitrate (5 Mbps SD, 10 Mbps HD) and frame rate (24 vs 30 fps) for screen clarity vs bandwidth.

---

## 🔁 Realtime Session Lifecycle

1. **Create Room** – Initiator calls `createRoom()`, Firestore stores offer + participant entry.
2. **Join Room** – Callee validates room, registers presence, and fetches SDP offer.
3. **ICE & TURN** – Cloudflare credentials minted via `CloudflareTurnService`; OpenRelay fallback ensures resilience.
4. **Connected State** – Compose UI exposes controls: share screen, share system audio, mute mic, silent mode, loudspeaker toggle, horn trigger, HD mode.
5. **Participant Events** – Joins/leaves/share start/stop events push banners + logs via `MutableStateFlow`.
6. **Disconnect** – Services stop, Firestore participant status flips to `left`, and peer connection resources are released.

---

## ⚙️ Build & Run

> Requires Android Studio Giraffe+ or command-line Gradle, JDK 17, Android SDK 34.

```powershell
# From repo root
.\androiduild.gradle.kts  # Gradle configuration

# Build debug APK
.\gradlew.bat assembleDebug

# Install on connected device (example)
.\gradlew.bat installDebug

# Distribute to Firebase testers
.\gradlew.bat appDistributionUploadDebug
```

1. Open the `android/` folder in Android Studio.
2. Sync Gradle; ensure `google-services.json` is present under `android/app/`.
3. Run the **app** configuration on a device running Android 10 or newer (internal audio capture is blocked on emulators).

---

## ✅ Verification Checklist

| Area | How to Verify |
| --- | --- |
| Permissions | Launch app → grant microphone + media projection → ensure dialogs close cleanly |
| Room creation | Tap “Create Room” → copy invite → observe Waiting state and participant count |
| Join flow | Second device enters code → should transition to Connected within 30 seconds |
| System audio share | Tap “Share Screen + Audio” → approve prompt → verify remote hears system audio while local remote audio mutes |
| Screen share conflict | If remote already shares, starting a share triggers warning + auto-stop logic |
| Horn feature | Tap “Make Noise” → remote device hears horn overlay |
| HD toggle | Switch HD button → check video sharpness + renegotiation logs |

---

## 🛡 Compliance & Positioning Notes

- Designed for **legitimate co-watching / collaboration** scenarios; marketing and copy avoid piracy references.
- **Data safety**: Firebase anonymized auth, no PII storage, no audio recording to disk.
- **Foreground notifications** ensure transparency when capture is active.
- **Privacy policy & reviewer access** must explain screen/audio capture use and provide test credentials when publishing to Play.

---

## 🚀 Roadmap Snapshot

1. **Play Console Prep** – Set up internal + closed testing tracks, fulfill 12-user/14-day requirement for new accounts.
2. **Stability Sprint** – Use Crashlytics signals to fix OEM-specific capture quirks (MIUI/ColorOS).
3. **Monetization** – Implement Play Billing with lifetime “Pro” unlock (unlimited audio, recording, background mode).
4. **Telemetry Enhancements** – Add analytics events for share start/stop, HD toggles, and ICE restarts to gauge quality.
5. **Accessibility** – Voice guidance for dialogs, larger touch targets, and caption overlays for shared screens.

---

## 🙌 Credits

- **Cloudflare Realtime TURN** (1 TB/mo) for hassle-free relay.
- **OpenRelay** as backup to guarantee connectivity.
- **Android MediaProjection + AudioPlaybackCapture** for internal audio streaming.
- **Firebase** (Auth, Firestore, Crashlytics, Analytics, App Distribution) powering the signaling and feedback loop.

Feel free to open issues or PRs if you spot regressions or want to extend the feature set.
