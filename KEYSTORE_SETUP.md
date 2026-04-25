# Keystore & Firebase Setup

The release workflow needs five GitHub secrets to build a signed APK. Follow steps 1–4 in order.

---

## Step 1 — Fix google-services.json (REQUIRED first)

The committed `android/app/google-services.json` was generated for the old `com.voicelink.connect` package. CI will fail until you supply a file that includes `com.streamsync.app`.

1. Open the [Firebase Console](https://console.firebase.google.com/) for this project.
2. **Project settings → Your apps → Add app** → Android.
3. Enter package name **`com.streamsync.app`** and register the app.
4. Download the new `google-services.json` (it will contain entries for both the old and new app).
5. Base64-encode it and add as the `GOOGLE_SERVICES_JSON` GitHub secret (see Step 3).

> You do **not** need to commit the new file to the repo — CI injects it from the secret at build time.

---

## Step 2 — Generate a release keystore (once)

```bash
keytool -genkey -v \
  -keystore streamsync-release.keystore \
  -alias streamsync \
  -keyalg RSA -keysize 2048 -validity 10000
```

Pick strong passwords and store the file safely — losing it means you can never publish upgrades for the same package.

---

## Step 3 — Add all five GitHub secrets

Go to **Settings → Secrets and variables → Actions → New repository secret** for each:

| Secret                 | How to get the value                                                        |
| ---------------------- | --------------------------------------------------------------------------- |
| `GOOGLE_SERVICES_JSON` | `base64 -w0 google-services.json` (the new file from Step 1)               |
| `KEYSTORE_FILE`        | `base64 -w0 streamsync-release.keystore`                                    |
| `KEY_ALIAS`            | Alias used in Step 2 (e.g. `streamsync`)                                    |
| `KEY_PASSWORD`         | Key password chosen in Step 2                                               |
| `STORE_PASSWORD`       | Store password chosen in Step 2                                             |

On macOS use `base64 -i file` (no `-w0`); on Windows use `certutil -encode file file.b64` and strip the header/footer lines.

---

## Step 4 — Re-enable Firebase services

After adding the new app in Firebase:

- **Crashlytics:** no extra steps — it reads from the google-services.json.
- **App Distribution:** re-add tester emails under the new `com.streamsync.app` app entry in Firebase Console.
- **Firestore rules:** confirm the rules allow reads/writes for the new package (they are server-side so package name doesn't matter, but double-check your project is the right one).

---

## Local signed builds (optional)

Set env vars before running Gradle:

```bash
export KEYSTORE_PATH=/path/to/streamsync-release.keystore
export KEY_ALIAS=streamsync
export KEY_PASSWORD=...
export STORE_PASSWORD=...

cd android && ./gradlew :app:assembleRelease
```

Or add them to `~/.gradle/gradle.properties` (never commit this file):

```properties
KEYSTORE_PATH=/absolute/path/streamsync-release.keystore
KEY_ALIAS=streamsync
KEY_PASSWORD=...
STORE_PASSWORD=...
```

If the signing credentials are missing, the build falls back to debug signing automatically — local development still works.
