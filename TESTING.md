# Testing StreamSync

This document covers manual verification for the features overhauled in the StreamSync rebrand: participant sync, real-time notifications, audio routing, and signed release builds.

## Prerequisites

- Two physical Android devices, both API 29+ (Android 10 or newer).
- Same or different networks — the Cloudflare TURN fallback handles symmetric NATs.
- A valid `google-services.json` for `com.streamsync.app` (see `KEYSTORE_SETUP.md`).

## 1. Member count sync

1. Device A: open StreamSync, tap **Create Room**, share the code.
2. Device B: tap **Join Room**, enter the code.
3. **Expected:** within ~1 second, both screens show **2 members**.
4. Device B closes the app or taps back to leave.
5. **Expected:** Device A drops to **1 member** within ~3 seconds.

If the count freezes, the participant snapshot listener is not firing — check Firestore rules and the `participantsVersion` field on the room document.

## 2. Join / leave notifications

Both sides should see a transient toast/banner labelled with the participant's role colour:

| Action                  | Banner text                              |
| ----------------------- | ---------------------------------------- |
| Other participant joins | "Participant joined"                     |
| Other participant leaves | "Participant left"                       |
| Other starts sharing    | "Participant started sharing screen"     |
| Other stops sharing     | "Participant stopped sharing screen"     |

Banners auto-dismiss after 3 seconds. Rapid join/leave bursts (rare in normal use) should still show all four banners thanks to the buffered `SharedFlow`.

## 3. Screen + audio sharing

1. Device A taps **Start Sharing**, accepts the MediaProjection consent dialog twice (one for video, one for audio capture).
2. Device B should see Device A's screen render fullscreen within ~2 seconds.

### Audio routing (priority logic)

| Source on sender                  | What receiver hears |
| --------------------------------- | ------------------- |
| Mic only (silent app on screen)   | Mic                 |
| App playing sound + mic           | App audio (mic suppressed) |
| App goes silent (paused video)    | Mic resumes immediately |

Use a media app (YouTube, Spotify, a video file) on Device A and pause/play it. Device B's audio should switch within one buffer (~20 ms) without dropouts.

### HD toggle

Toggle HD from either side. Resolution stays 1080p; only the encoder bitrate ramps from 5 Mbps SD → 10 Mbps HD. There should be no black-screen flash during the switch.

## 4. Connection resilience

1. Establish a connection, then put Device B into airplane mode for ~10 seconds.
2. Restore connectivity.
3. **Expected:** ICE restart fires within 10 seconds and the call resumes without rejoining the room. Up to 3 restarts within a 5-second cooldown are attempted before failing.

## 5. Release build smoke test

```bash
cd android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Verify:

- App launches and shows the StreamSync welcome screen with the teal gradient.
- APK size ≤ ~70 MB (down from ~100 MB before ABI/resource trimming).
- R8 has stripped verbose logs — `adb logcat | grep WebRtcManager` should show only warnings/errors, no `Log.d`/`Log.i` lines.

## 6. CI release workflow

Push a tag like `v0.1.0`:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The `Release APK` workflow should:

1. Decode `KEYSTORE_FILE` into `app/release.keystore`.
2. Build `assembleRelease` with the four signing secrets.
3. Upload the signed APK as a workflow artifact.
4. Attach the APK to a GitHub Release for the tag.

A push to `main` runs the same build but only uploads the artifact — it does not create a Release.
