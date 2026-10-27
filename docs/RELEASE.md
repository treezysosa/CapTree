# Building, signing and upgrading Android APKs

Use JDK 17, Android SDK 35/build-tools 35.0.0 and the checked-in Gradle wrapper. Production signing belongs to the app owner; no production key is included. Never commit a keystore, signing password or base64 keystore to source control.

## Development APK

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
bash tools/verify_apks.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed and installable. The release APK is **unsigned** unless all four signing variables below are provided. An unsigned APK cannot be installed. CI publishes these as separate artifacts, with decoded manifest, SHA-256, signature and native alignment reports. Download the debug artifact for development installation.

The main build workflow caches its development key to improve continuity. Cache eviction, a new runner cache scope, a local Android Studio key or an older CI build can still have a different key. The cache is not a durable production signing service. Compare the signer SHA-256 in the artifact's signature report with Settings → **CHECK DEVICE & BUILD** on the installed app. `adb install -r` preserves app data only when the package ID and signing certificate match and the version code is not lower. It must not be worked around by silently uninstalling.

If Android reports an incompatible signature, keep the existing installation while exporting projects with **BACKUP PROJECT** to a file outside app-private storage. Automatic drafts can be kept as projects first; their undo history is local. Import those backups after a deliberate reinstall. Different signing keys cannot be repaired by this app.

## Owner-signed release

Create a dedicated keystore once on a trusted machine. Keytool prompts for passwords; keep the keystore and recovery information backed up securely. Use the same key for every update.

```sh
keytool -genkeypair -keystore /secure/path/retro-release.jks -alias retro -keyalg RSA -keysize 3072 -validity 10000
```

Provide these environment variables to Gradle through your local secret manager or CI secret injection:

| Variable | Value |
|---|---|
| `RETRO_RELEASE_STORE_FILE` | Absolute path to your keystore |
| `RETRO_RELEASE_STORE_PASSWORD` | Keystore password |
| `RETRO_RELEASE_KEY_ALIAS` | Signing alias, such as `retro` |
| `RETRO_RELEASE_KEY_PASSWORD` | Private key password |

```sh
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

All four variables must be set together; partial configuration fails rather than producing a misleading release. Environment values and keystores are excluded from archives. Release builds disable debugging and do not use a development signing fallback. Shrinking is currently disabled to retain compatibility with JNI/reflection-based inference libraries; no claim of a minimized APK is made.

For GitHub Actions, configure repository secrets `RETRO_RELEASE_KEYSTORE_BASE64` (a base64 representation of your binary keystore), `RETRO_RELEASE_STORE_PASSWORD`, `RETRO_RELEASE_KEY_ALIAS`, and `RETRO_RELEASE_KEY_PASSWORD`. Run **Package owner-signed Android release** manually. It stages the key in the runner's temporary directory, builds and verifies the APK, removes the staged key even on failure, and uploads only the signed APK and public verification reports. It does not upload to an app store or create a public release. Owner credentials are not required for normal builds. CI separately tests this signing path using a disposable validation key; that APK is not a distributed upgrade key.

## Packaging and compatibility evidence

`tools/check_apk.py` reads the actual APK's native ELF program tables and ZIP local headers, rather than assuming dependency versions guarantee compatibility. Every 64-bit `.so` must have compatible 16 KB load-segment alignment, uncompressed APK entries and 16 KB data offsets. SDK `zipalign -c -P 16` checks the packaged APK too. The decoded manifest gate checks app identity, SDK boundaries, backup/cleartext settings and an explicit permission allowlist. WorkManager's normal scheduling permissions are included; microphone, camera and broad media/storage access are not.

Static alignment is necessary but does not certify inference on physical ARM/16 KB devices. The ordinary Android 10/15 jobs run x86_64 emulators and exercise native loading, real codecs and editing. CUDA video inference and physical ARM/16 KB execution remain outside this environment's evidence. Importing an AI model still performs its normal on-device inference probe.

The **Validate in-place Android upgrade** workflow builds pinned v0.9 source and the current version using the same development key. It installs v0.9, creates a real imported photo, favorite project, theme/quality settings and automatic draft with undo/redo. It then uses `adb install -r` for the new APK, without clearing data, and verifies all retained data plus a new offline photo export. Artifacts contain logs, JSON evidence and the exported image for API 29 and 35. This proves a compatible-key update path; it cannot change the key used by an older downloaded debug APK.

Retain the APK's checksum and signing fingerprint, keep the matching source, and run the relevant device/model checks for your supported hardware. The optional **EXPORT DEVICE REPORT** writes a local JSON file only when selected and never uploads it automatically.
