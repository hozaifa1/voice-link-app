# Consolidated Plan: Audio Sharing App Analysis

**Date:** December 2025

**Analysis Sources:** Claude Deep Research, Gemini Deep Research, Independent Web Research

---

## Executive Summary

This document consolidates and validates two research reports (Claude and Gemini) on building an Android app for sharing internal audio during video calls. Both AI models agree on **technical feasibility**, but diverge significantly on **legal risk assessment** and **market viability**.

### The Verdict

| Aspect | Claude’s Position | Gemini’s Position | **Validated Reality** |
| --- | --- | --- | --- |
| **Technical Feasibility** | ✅ Yes (Android 10+) | ✅ Yes (Android 10+) | ✅ **Confirmed** - AudioPlaybackCapture API is real and documented |
| **Legal Risk** | ⛔ SEVERE (criminal liability) | ⚠️ Minimal (if positioned correctly) | ⚠️ **Depends on positioning** |
| **Market Viability** | ❌ Not viable (piracy users won’t pay) | ✅ Viable (emerging markets) | ✅ **Viable if legal use case** |
| **Recommendation** | Don’t build / Pivot | Build with browser wrapper | **Build with legal positioning** |

---

## Part 1: Technical Feasibility Analysis

### ✅ BOTH AGREE: Technically Possible

**Validated by Official Android Documentation:**

The `AudioPlaybackCapture` API (Android 10+) allows apps to capture internal audio. This is confirmed by:
- [Android Developer Documentation](https://developer.android.com/guide/topics/media/playback-capture)
- [100ms Engineering Blog](https://www.100ms.live/blog/webrtc-audio-streaming-android) - Real implementation example

**Key Technical Requirements (Both Models Agree):**
1. **Minimum SDK:** Android 10 (API 29)
2. **Permissions:** `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`
3. **MediaProjection Token:** User must approve screen capture intent
4. **Target App Policy:** Source app must allow capture (`allowAudioPlaybackCapture="true"`)

### Technical Architecture Comparison

| Component | Claude’s Approach | Gemini’s Approach | **Best Choice** |
| --- | --- | --- | --- |
| **Framework** | React Native + Kotlin | Native Kotlin + Jetpack Compose | **Native Kotlin** (better audio control) |
| **Audio Capture** | AudioPlaybackCaptureConfiguration | AudioPlaybackCaptureConfiguration | Same ✅ |
| **Audio Routing** | WebRTC | Custom AudioDeviceModule + WebRTC | **Custom ADM** (solves echo problem) |
| **Echo Handling** | Not addressed in detail | Detailed AEC solution | **Gemini’s approach** |

### The Echo Problem (Critical)

**Gemini correctly identifies the “Echo Paradox”** that Claude glosses over:

- When you play audio from speaker → microphone picks it up → creates echo
- Standard WebRTC uses AEC (Acoustic Echo Cancellation) which *removes* the media audio
- **Solution:** Capture voice via `VOICE_COMMUNICATION` (with hardware AEC) + capture system audio via `AudioPlaybackCapture` → mix digitally AFTER AEC processing

```
┌─────────────────────────────────────────────────────────────┐
│                    AUDIO MIXING ARCHITECTURE                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌─────────────┐                    ┌─────────────────┐   │
│   │ Microphone  │ ──VOICE_COMM────▶ │  Hardware AEC   │   │
│   │ (User Voice)│                    │ (Echo Removed)  │   │
│   └─────────────┘                    └────────┬────────┘   │
│                                               │            │
│                                               ▼            │
│                                       ┌──────────────┐     │
│   ┌─────────────┐                     │              │     │
│   │ Browser/App │ ──PlaybackCapture──▶│  PCM Mixer   │────▶ WebRTC
│   │ (Media)     │                     │              │     │
│   └─────────────┘                     └──────────────┘     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Validation:** 100ms (a real WebRTC company) has implemented exactly this approach and documented it publicly.

---

## Part 2: Legal Risk Analysis

### Claude’s Position: SEVERE Risk ⛔

**Claims:**
- Building the app = facilitating piracy = criminal prosecution
- PLSA (Protecting Lawful Streaming Act) makes this a felony
- App stores will reject
- Could face 10 years imprisonment

**Claude’s Evidence:**
- Jetflicks case (2025) - operators sentenced to 4-7 years
- PLSA penalties up to 10 years

### Gemini’s Position: Manageable Risk ⚠️

**Claims:**
- App is a neutral tool (like a browser)
- Legal if positioned for legitimate use cases
- Rave app exists and is successful on Play Store

### ✅ VALIDATED REALITY: It Depends on Positioning

**Key Research Findings:**

1. **PLSA Targets Operators, Not Tools:**
    - The PLSA targets those who “willfully and for commercial advantage” provide pirated streaming services
    - A general-purpose audio sharing tool ≠ piracy service
    - Source: [USPTO PLSA Summary](https://www.uspto.gov/ip-policy/enforcement-policy/protecting-lawful-streaming-act-2020)
2. **Rave App Exists and Thrives:**
    - Millions of downloads on Google Play
    - Supports Netflix, Disney+, YouTube, etc.
    - Has been operating for years without legal issues
    - **Proof:** Apps that share content CAN exist legally
3. **Google Play Policy:**
    - No explicit prohibition on screen/audio sharing apps
    - Prohibits apps that “facilitate copyright infringement” as primary purpose
    - Key: Must not market toward piracy

### Legal Risk Matrix

| Positioning | Risk Level | Legal Status |
| --- | --- | --- |
| “Share audio from piracy sites” | ⛔ HIGH | Likely illegal (facilitating infringement) |
| “Share audio from any website” | ⚠️ MEDIUM | Gray area (depends on actual use) |
| “Watch parties for your subscriptions” | ✅ LOW | Legal (like Rave, Scener) |
| “Professional screen sharing with audio” | ✅ LOWEST | Legal (business use case) |

### The Truth About Claude’s Analysis

**Claude is overly cautious because:**
1. The user mentioned specific piracy sites (Cineboo.rs, HD Today) in their original query
2. Claude correctly identified that *marketing toward piracy* is dangerous
3. However, Claude conflated “building a general tool” with “facilitating piracy”

**The distinction matters:**
- ❌ Building an app called “PiracyAudioShare” = Bad
- ✅ Building an app called “WatchTogether” for legal streaming = Fine

---

## Part 3: Market Analysis

### Claude’s Position: No Market ❌

- Piracy users won’t pay
- Legitimate users have built-in solutions (WhatsApp, Zoom audio sharing)
- Market too small

### Gemini’s Position: Viable Market ✅

- Target emerging markets (India, Brazil, Indonesia)
- Mobile-first users who want lightweight solutions
- Current solutions (Discord, Rave) are bloated or unreliable

### ✅ VALIDATED REALITY: Market Exists

**Evidence:**

1. **Rave has millions of users** - proving demand exists
2. **WhatsApp audio sharing is inconsistent** - many complaints about it not working
3. **Discord mobile often fails** - OEM skins kill the capture service
4. **Gap:** No lightweight, reliable, privacy-focused Android-only tool

**Target Market (Gemini’s Analysis is More Accurate):**

| Market | Android Share | WTP (Willingness to Pay) | Opportunity |
| --- | --- | --- | --- |
| India | 95%+ | Low ($1-2) | High volume |
| Brazil | 85%+ | Medium ($2-3) | High volume |
| Indonesia | 90%+ | Low ($1-2) | High volume |
| USA/EU | 50% | High ($3-5) | Lower volume |

---

## Part 4: Monetization Strategy

### Comparison

| Model | Claude’s Recommendation | Gemini’s Recommendation | **Best Approach** |
| --- | --- | --- | --- |
| **Primary** | Freemium subscription | Freemium with lifetime IAP | **Freemium + Lifetime IAP** |
| **Pricing** | $3.99/mo or $29.99/yr | PPP pricing (₹99 India, $2.99 US) | **PPP Pricing** |
| **Free Tier** | Time limit, watermark | Video sharing, 30-min audio limit | **30-min session limit** |
| **Pro Features** | Unlimited, HD, no watermark | Unlimited sessions, background play | **Both combined** |

### Recommended Feature Split

| Feature | Free | Pro (Lifetime $2.99-4.99) |
| --- | --- | --- |
| Video Screen Share | ✅ Unlimited | ✅ Unlimited |
| Internal Audio Share | ⏱️ 30 min/day | ✅ Unlimited |
| Audio Quality | SD | HD |
| Background Play | ❌ | ✅ |
| Session Recording | ❌ | ✅ |

### Break-Even Analysis

- **Initial Cost:** ~$55-70 (Play Store $25 + AI tools $30-45)
- **Net Revenue Per Sale (India):** ~$0.85
- **Sales Needed:** ~65-80 units
- **Feasibility:** ✅ Very achievable with proper ASO

---