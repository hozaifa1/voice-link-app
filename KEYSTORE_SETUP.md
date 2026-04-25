# Keystore Setup

The release workflow signs APKs with an Android keystore you control. CI gets it through four GitHub secrets; locally you can use Gradle properties.

## 1. Generate a keystore (once)

```bash
keytool -genkey -v \
  -keystore streamsync-release.keystore \
  -alias streamsync \
  -keyalg RSA -keysize 2048 -validity 10000
```

Pick a strong store password and key password. **Store the keystore file somewhere safe** — losing it means you can never publish updates that upgrade the same install.

## 2. Add GitHub secrets

The CI workflow at `.github/workflows/release.yml` expects these four repository secrets:

| Secret           | Value                                                        |
| ---------------- | ------------------------------------------------------------ |
| `KEYSTORE_FILE`  | Base64-encoded keystore: `base64 -w0 streamsync-release.keystore` |
| `KEY_ALIAS`      | Alias you used above (e.g. `streamsync`)                     |
| `KEY_PASSWORD`   | Key password                                                 |
| `STORE_PASSWORD` | Store password                                               |

Add them under **Settings → Secrets and variables → Actions**.

## 3. Local signed builds (optional)

Either set environment variables before running Gradle:

```bash
export KEYSTORE_PATH=/path/to/streamsync-release.keystore
export KEY_ALIAS=streamsync
export KEY_PASSWORD=...
export STORE_PASSWORD=...

./gradlew :app:assembleRelease
```

…or put them in `~/.gradle/gradle.properties` (not in the repo):

```properties
KEYSTORE_PATH=/absolute/path/streamsync-release.keystore
KEY_ALIAS=streamsync
KEY_PASSWORD=...
STORE_PASSWORD=...
```

If credentials are missing, the build falls back to debug signing so local development still works.

## 4. Firebase google-services.json

The repo's `android/app/google-services.json` was generated for the legacy `com.voicelink.connect` package. Because the package was renamed to `com.streamsync.app`, you must:

1. Open the [Firebase Console](https://console.firebase.google.com/) for this project.
2. **Project settings → Your apps → Add app** → Android, package name `com.streamsync.app`.
3. Download the new `google-services.json` and replace `android/app/google-services.json`.
4. Re-run any required service registrations (Crashlytics, App Distribution testers).

Without this, Firebase signaling, Crashlytics, and App Distribution will all fail to initialize at runtime.
