# Consolidated Technical Implementation Plan (Step-by-Step)

This plan is a single checklist you can follow in order. Each step is either **[Human]** (you must do it) or **[Coding]** (you or an AI coding agent does it).

Payment is intentionally **deferred until after MVP feasibility is proven**.

## Master Checklist (follow in order)

- [x] **Step 0 — [Human] Lock 2 decisions**
  - Package name: `com.voicelink.connect`
  - Signaling choice for MVP: **Firebase signaling** (selected)

- [x] **Step 1 — [Coding] Create the Android project + base scaffolding** (Appendix A, H; pace via Appendix B)
  - Kotlin + Jetpack Compose
  - minSdk 29, targetSdk 34

- [x] **Step 2 — [Human] Create Firebase project (free) + register Android app** (Appendix E; free account context in Appendix C; pacing in Appendix B)
  - Firebase Console: https://console.firebase.google.com/
  - Download `google-services.json`

- [x] **Step 3 — [Coding] Integrate Firebase + Crashlytics (early)** (Appendix E; pacing in Appendix B)
  - Goal: crash visibility while you iterate on audio + WebRTC

- [x] **Step 4 — [Coding] Prove audio capture feasibility locally (no networking yet)** (Appendix A for stack/audio scope; Appendix H Week 2–3 prompts; pacing in Appendix B)
  - Implement MediaProjection permission flow
  - Capture internal audio (AudioPlaybackCapture) + mic audio and mix PCM
  - Confirm you can:
    - start/stop cleanly
    - handle interruptions
  - avoid storing audio to disk

- [x] **Step 5 — [Coding] Prove WebRTC feasibility on same Wi‑Fi (no paid TURN)** (Appendix A for WebRTC stack; Appendix F for STUN/signaling; Appendix H Week 1–3 prompts; pacing in Appendix B)
  - Use public STUN (example: `stun:stun.l.google.com:19302`)
  - Implement signaling via Firebase
  - Test device A ↔ device B on the same network

- [x] **Step 6 — [Human] Decide if you need TURN yet (only if remote tests fail)** (Appendix F TURN options; cost context in Appendix C; pacing in Appendix B)
  - If same‑Wi‑Fi works but remote networks fail, you need TURN.
  - Choose:
    - Managed TURN provider (paid)
    - Self-host coturn (paid VPS + usually paid domain)

- [x] **Step 7 — [Coding] Add TURN support (only after Step 6)** (Appendix F TURN setup; secrets/env in Appendix G; pacing in Appendix B)
  - Add ICE server config support
  - If you use TURN REST credentials, implement credential minting on backend

- [ ] **Step 8 — [Human] Only after MVP works: pay for Google Play Developer account** (Appendix C paid accounts; Appendix D section 2.1; pacing in Appendix B)
  - Sign up: https://play.google.com/console/signup
  - Fee: $25 one-time

- [ ] **Step 9 — [Human] Create Play Console app shell + App Signing setup** (Appendix D sections 2.2–2.3; pacing in Appendix B)
  - Opt into Play App Signing
  - Create/upload your upload key (`.jks`) and back it up

- [ ] **Step 10 — [Coding] Prepare release signing + produce an AAB** (Appendix D section 2.3; secrets handling in Appendix G; pacing in Appendix B)
  - Configure signing in the project
  - Generate release AAB

- [ ] **Step 11 — [Human] Internal testing rollout (fast loop)** (Appendix D section 2.4 Internal testing; pacing in Appendix B)
  - Create internal track
  - Add testers (email list or Google Group)
  - Distribute build and iterate

- [ ] **Step 12 — [Coding] Stabilize based on Crashlytics + tester feedback** (Appendix E Crashlytics verification; Appendix I risk notes; pacing in Appendix B)

- [ ] **Step 13 — [Human] Closed testing gate (only if your account requires it)** (Appendix D section 2.4 Closed testing; gating timing in Appendix B Gate D)
  - 12 opted‑in testers for 14 consecutive days
  - Apply for production access when eligible

- [ ] **Step 14 — [Human] Final compliance + store listing** (Appendix D section 2.5; Appendix I risk/positioning guidance; pacing in Appendix B)
  - Data safety
  - Content rating
  - Privacy policy URL
  - App access instructions (review credentials)
  - Store listing assets

- [ ] **Step 15 — [Coding] Monetization implementation (only after stable MVP)** (Appendix D section 2.6 Billing; secrets/env in Appendix G; pacing in Appendix B)
  - Implement Billing + PRO gating

- [ ] **Step 16 — [Human] Payments profile + in‑app product creation (only when you’re ready to monetize)** (Appendix D section 2.6; paid account context in Appendix C; pacing in Appendix B)
  - Merchant/payments profile
  - Create in‑app product IDs
  - Configure license testers

- [ ] **Step 17 — [Human] Production rollout** (Appendix D release readiness; compliance timeline in Appendix B; risk checks in Appendix I)

---

## Appendix A) Stack Decision (what the agent will build)

```
Framework: Native Android (Kotlin + Jetpack Compose)
├── Reason: Better control over audio APIs
├── Min SDK: 29 (Android 10) for AudioPlaybackCapture
└── Target SDK: 34 (Android 14)

Core Components:
├── WebView (Browser Wrapper)
├── Custom Audio pipeline (Mic VOICE_COMMUNICATION + PlaybackCapture → PCM mix)
├── WebRTC P2P
│   ├── Signaling: Firebase or WebSocket server
│   └── ICE: STUN + TURN
└── Firebase: Analytics + Crashlytics (+ optional Auth + optional App Distribution)
```

---

## Appendix B) Timeline (reference only)

- **Gate A (Day 0-3) — Prove feasibility without paying**
  - Build the Android app locally
  - Create Firebase project + download `google-services.json`
  - Prove internal audio capture + mixing works on your device
  - Prove WebRTC works on same Wi‑Fi using STUN only

- **Gate B (Day 4-7) — Test real networks before paying**
  - Test across different networks (Wi‑Fi mobile data)
  - Only if it fails: decide TURN strategy (managed vs self-host)

- **Gate C (After MVP works) — Pay and start Play distribution**
  - Pay for Play Developer account
  - Create Play Console app + opt into Play App Signing + create upload keystore
  - Set up internal testing track

- **Gate D (If required) — Closed testing requirement**
  - Start closed testing: 12 opted-in testers for 14 days
  - Apply for production access when eligible

- **Gate E — Compliance + production**
  - Finish App content forms + store listing assets
  - Ship production

---

## Appendix C) Accounts / Platforms (defer paid steps)

- **[Free now] Firebase**
  - Console: https://console.firebase.google.com/
  - Cost: typically free tier is enough for MVP feasibility

- **[Paid later] Google Play Developer Account**
  - Sign up: https://play.google.com/console/signup
  - Fee: $25 one-time
  - Do this after MVP works (see Step 8)

- **[Paid later, only if needed] TURN + hosting + domain**
  - Only do this after Step 6 confirms you need TURN for real-world networks
  - Options:
    - Managed TURN provider (subscription)
    - Self-host coturn (VPS + usually a domain)

---

## Appendix D) Google Play Console (Human-Only) Hyper-Specific Checklist

### 2.1 Developer Account Verification / Eligibility

- **[Do] Link/confirm payments profile used for identity**
  - Play Console uses a payments profile for verification.
  - Reference: https://support.google.com/googleplay/android-developer/answer/14177239?hl=en

- **[Do] Complete developer identity verification items (if prompted)**
  - Your legal name/address and developer email can require verification.
  - Identity verification overview: https://support.google.com/googleplay/android-developer/answer/10841920?hl=en
  - Example requirements referenced by Google:
    - Personal: official government identity document
    - Organization: D-U-N-S number + official government identity document + official organization document
  - Developer email verification is required (6-digit code workflow) per the same article.

- **[Do] Understand new personal account testing requirements**
  - If you have a newly created personal developer account, you must run a **closed test** with a minimum of **12 testers opted-in continuously for 14 days**, then apply for production access.
  - Reference: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en

### 2.2 Create the App Listing Shell (before you can upload builds)

- **[Do] Create app** in Play Console
  - Set:
    - Default language
    - App name
    - App or Game
    - Free (recommended even if you have IAP)

### 2.3 App Signing (Keystore + Play App Signing)

- **[Do] Opt into Play App Signing**
  - Play App Signing overview: https://developer.android.com/studio/publish/app-signing

- **[Do] Generate and store an upload key (`.jks`) safely**
  - Android Studio path: `Build > Generate Signed Bundle/APK`.
  - Upload key generation steps: https://developer.android.com/studio/publish/app-signing#generate

- **[Do] Back up secrets in 2 places**
  - Store your `.jks` and passwords in:
    - A password manager
    - An offline backup (encrypted)

### 2.4 Testing Tracks (Internal + Closed)

- **[Do] Set up Internal testing (fast iteration)**
  - Internal test is up to 100 testers and is easiest for quick installs.
  - Guide: https://support.google.com/googleplay/android-developer/answer/9845334?hl=en
  - Path:
    - `Testing > Internal testing > Testers tab`
    - Create email list OR Google Group
    - Add feedback email/URL
    - Copy opt-in/share link and send to testers

- **[Do] Set up Closed testing (required for new personal accounts)**
  - Guide: https://support.google.com/googleplay/android-developer/answer/9845334?hl=en
  - Path:
    - `Testing > Closed testing > Manage track > Testers tab`
    - Create email list OR Google Group
    - Add feedback email/URL
    - Copy shareable opt-in link

- **[Do] Run closed test for 14 consecutive days with >=12 opted-in testers**
  - After meeting the requirement, apply:
    - `Dashboard > Apply for production`
  - Reference: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en

### 2.5 App Content Forms (These Block Review If Missing)

- **[Do] Content rating questionnaire (IARC)**
  - Path: `Policy > App content`
  - Steps and warnings: https://support.google.com/googleplay/android-developer/answer/9859655?hl=en

- **[Do] Data safety form**
  - Path: `Policy > App content` (Data safety form is on App content page)
  - Reference: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
  - Practical note: if you use Firebase Analytics/Crashlytics, you must disclose data collection accurately.

- **[Do] Privacy policy URL**
  - Host this on a publicly accessible URL (your own site is fine).
  - Make sure it matches what your app actually does.
  - Privacy policy guidance + how to add it in Play Console: https://support.google.com/googleplay/android-developer/answer/9859455?hl=en

- **[Do] App access instructions (reviewer access / credentials)**
  - If any feature is restricted (login, invite codes, paywalls), you must provide access.
  - Path: `Policy and programs > App content > App access > Start/Manage > + Add new instructions`
  - App access instructions guide: https://support.google.com/googleplay/android-developer/answer/9859455?hl=en
  - Credential requirements (reusable, valid, English, bypass OTP/MFA where needed): https://support.google.com/googleplay/android-developer/answer/15748846?hl=en

- **[Do] Store listing preview assets (minimum viable)**
  - You must provide:
    - App icon: 512x512 PNG (32-bit with alpha), <= 1024KB
    - Screenshots (recommended; often effectively required for a credible listing)
  - Preview assets guide: https://support.google.com/googleplay/android-developer/answer/9866151?hl=en
  - Note: The preview assets guide also recommends adding alt text for screenshots.

- **[Do] Store listing text fields (minimum viable)**
  - Provide a clear, non-piracy positioning in store listing text.
  - At minimum, ensure you fill:
    - App name
    - Short description
    - Full description
  - Guidance for preview assets + short description requirements: https://support.google.com/googleplay/android-developer/answer/9866151?hl=en

- **[Optional, recommended] Preview video (YouTube URL)**
  - If you add a preview video, it must be a YouTube URL (not a playlist/channel).
  - Requirements include:
    - Video must be public or unlisted
    - Must be embeddable
    - Must not be age-restricted
    - Ads/monetization must be disabled (or use a different video without monetization claims)
  - Preview video requirements: https://support.google.com/googleplay/android-developer/answer/9866151?hl=en

- **[Do] Keep review access unblocked**
  - If you add a paywall (e.g., PRO features), ensure reviewers can fully access all functionality.
  - Credential requirements (valid always, reusable, English, bypass OTP/MFA): https://support.google.com/googleplay/android-developer/answer/15748846?hl=en

### 2.6 Monetization Setup (Google Play Billing)

- **[Do] Create payments profile (merchant)**
  - After creating it, it is linked to Play Console.
  - Reference: https://support.google.com/googleplay/android-developer/answer/7161426?hl=en

- **[Do] Create your one-time “PRO Lifetime” in-app product**
  - Reference: https://support.google.com/googleplay/android-developer/answer/1153481?hl=en
  - Path:
    - `Monetize with Play > Products > In-app products`
  - Product ID rules:
    - must start with a number or lowercase letter
    - only `a-z`, `0-9`, `_`, `.`
    - cannot be changed later
  - Examples:
    - `pro_lifetime`
    - `pro.lifetime`

- **[Do] Configure license testers for Billing tests**
  - Billing tests doc: https://developer.android.com/google/play/billing/test
  - Key detail:
    - License testers can test billing without real charges and can bypass some upload constraints.

- **[Do] Configure License testing list in Play Console (for test purchases)**
  - Path: `Settings > License testing`
  - Steps: https://support.google.com/googleplay/android-developer/answer/6062777?hl=en
  - Notes:
    - License testers should also be eligible to install your internal/closed test release.
    - One-time products must be published before they can be tested.

---

## Appendix E) Firebase (Human-Only) Hyper-Specific Checklist

### 3.1 Create and Configure Project

- **[Do] Create Firebase project**
  - Steps: https://firebase.google.com/docs/android/setup
  - During creation, **enable Google Analytics** (recommended for Crashlytics breadcrumb logs).

- **[Do] Register Android app inside Firebase**
  - You must enter the exact **package name** you chose.

- **[Do] Download `google-services.json`**
  - Place it in the Android module root (`app/google-services.json`).
  - Reference: https://firebase.google.com/docs/android/setup

### 3.2 Crashlytics Setup (for stability during testing)

- **[Do] Enable Crashlytics + Analytics**
  - Start guide: https://firebase.google.com/docs/crashlytics/android/get-started

- **[Do] Verify Crashlytics is working**
  - Force a test crash and confirm it appears in Firebase console.
  - Reference: https://firebase.google.com/docs/crashlytics/android/get-started

### 3.3 App Distribution (optional but recommended)

- **[Do] Set up Firebase App Distribution**
  - Add tester emails/groups in Firebase
  - Upload APK/AAB to distribute to testers
  - Use this for fast feedback while Play closed test is running

### 3.4 If You Choose Firebase Signaling (Option A)

- **[Do] Choose Firestore OR Realtime Database for signaling**
- **[Do] Enable Firebase Authentication**
  - Minimum viable option: Anonymous auth
- **[Do] Set Security Rules**
  - Ensure:
    - only session participants can read/write signaling data
    - signaling data is short-lived
- **[Do] Decide where room join codes live**
  - Example: `rooms/{roomId}` with subcollections for offers/answers/candidates

- **[Do] Confirm Firebase products are actually enabled in the Console**
  - Firebase Console: https://console.firebase.google.com/
  - Typical toggles you’ll need to turn on:
    - Authentication: enable the sign-in method(s) you use (e.g., Anonymous)
    - Firestore or Realtime Database: create the database instance and set its location
    - App Distribution: add testers/groups if you plan to use it

---

## Appendix F) WebRTC Infrastructure (Human-Only) Hyper-Specific Checklist

### 4.1 STUN

- **[Do] Use public STUN for MVP**
  - Example: `stun:stun.l.google.com:19302`
  - No signup, no API key required.

### 4.2 TURN (You Need This for Real-World Networks)

#### Option A: Managed TURN Provider

- **[Do] Choose a provider**
  - Deliverables you must obtain from the provider:
    - TURN URLs
    - username/password OR time-limited credential mechanism
    - any API key/secret if they provide credential minting
- **[Do] Store provider secrets in backend env vars** (never in Android app code)

#### Option B: Self-host coturn (VPS)

- **[Do] Provision a VPS**
  - Choose a provider, create a VM, note the public IP.

- **[Do] (Recommended) Buy a domain and set DNS**
  - Create:
    - `A record`: `turn.yourdomain.com -> VPS_PUBLIC_IP`

- **[Do] Open firewall ports**
  - TURN typical ports:
    - `3478` (TURN over UDP/TCP)
    - `5349` (TURN over TLS)
  - Relay ports:
    - a range you configure (example: `49152-65535`) and open in firewall

- **[Do] Configure coturn for WebRTC long-term credentials**
  - coturn notes:
    - WebRTC uses long-term credentials (`--lt-cred-mech` / `-a`) and a realm (`-r`).
    - Reference: https://github.com/coturn/coturn/wiki/turnserver

- **[Do] Choose authentication mode**
  - **Static users** (simpler but less secure): store a fixed username/password.
  - **TURN REST API (recommended)**:
    - set a shared secret on server
    - backend generates time-limited username/password
    - REST draft described in coturn docs:
      - https://github.com/coturn/coturn/wiki/turnserver

- **[Do] NAT note**
  - If the TURN server is behind NAT, coturn docs mention `-X` for external IP mapping.

### 4.3 Signaling (things the agent cannot do)

- **[Do] Decide how users create/join rooms (UX decision)**
  - Examples:
    - Room code (short alphanumeric)
    - Invite link (deep link)

- **If using Firebase signaling**
  - No server hosting required, but you must:
    - Enable Auth
    - Deploy correct Firestore/RTDB rules
    - Ensure signaling data expires (TTL strategy)

- **If using a WebSocket signaling server**
  - You must pick and set up:
    - Hosting provider (VPS/PaaS)
    - Domain (recommended)
    - TLS/HTTPS so you can use `wss://`
  - Minimum operational checklist:
    - Set runtime environment variables (see section 5)
    - Ensure websockets are supported by the host
    - Ensure inbound firewall allows 443 (and 80 for certificate renewals if using Let’s Encrypt)

### 4.4 Domain + TLS (required for reliable `wss://` and TURN TLS)

- **[Do] Buy a domain and create DNS records**
  - Suggested subdomains:
    - `signal.yourdomain.com` (signaling)
    - `turn.yourdomain.com` (TURN)
- **[Do] Provision TLS certificates**
  - For WebSocket signaling you want:
    - `https://...` and `wss://...`
  - For TURN you may also want:
    - `turns:turn.yourdomain.com:5349`

---

## Appendix G) Secrets / Env Vars Checklist (Things You Must Create + Store)

The Android app should not contain server secrets. Keep secrets on the server or in CI secrets.

### 5.1 Backend (signaling server and/or TURN credential server)

- **[SIGNALING] `SIGNALING_BASE_URL`**
  - Example: `https://signal.yourdomain.com`

- **[SIGNALING] `SIGNALING_WS_URL`**
  - Example: `wss://signal.yourdomain.com/ws`

- **[TURN] `TURN_URLS`**
  - Example: `turn:turn.yourdomain.com:3478?transport=udp,turns:turn.yourdomain.com:5349?transport=tcp`

- **[TURN - static mode] `TURN_USERNAME`, `TURN_PASSWORD`**

- **[TURN - REST mode] `TURN_REALM`, `TURN_SHARED_SECRET`, `TURN_TTL_SECONDS`**

- **[AUTH] `SIGNALING_JWT_SECRET`** (if you protect rooms with JWT)

### 5.2 Android App (non-secret configuration)

- **`SIGNALING_URL`** (public)
- **`ICE_SERVERS_JSON`** (public; contains STUN and TURN urls, but not TURN shared secret)

Practical storage:
- local dev: environment variables or a local `secrets.properties` not committed
- CI: repository secrets
- release: injected via Gradle `buildConfigField`

---

## Appendix H) AI Coding Agent Prompts

**Week 1: Project Setup (agent)**

```
Create a new Android project using Kotlin and Jetpack Compose.
- Package name: com.yourname.watchtogether
- minSdk: 29 (Android 10)
- targetSdk: 34 (Android 14)
- Add dependencies: WebRTC, OkHttp, Kotlin Coroutines
```

**Week 2: Permissions & MediaProjection (agent)**

```
Implement MediaProjection permission flow:
1. Foreground service with mediaProjection
2. Runtime permission RECORD_AUDIO
3. MediaProjectionManager.createScreenCaptureIntent()
4. Build AudioPlaybackCaptureConfiguration
```

**Week 3: Audio Capture & Mixing (agent)**

```
Implement HybridAudioSource:
- Mic AudioRecord: VOICE_COMMUNICATION (AEC)
- System AudioRecord: AudioPlaybackCapture
- Mix PCM (10ms buffers) with clipping
- Feed mixed PCM to WebRTC audio pipeline
```

---

## Appendix I) Risk Mitigation (Human-Only Checklist)

- **[Do] Never market toward piracy**
- **[Do] Store listing language should explicitly target legitimate use cases**
- **[Do] DRM handling UX**
  - Detect silence and show user-facing message (“Protected content cannot be shared”).