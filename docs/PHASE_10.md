# Phase 10: release packaging and upgrade continuity

Version 0.10 adds a verified release build path, owner signing configuration, APK inspection and in-place Android upgrade checks. It keeps native offline editing available without an account or server; model setup and previous feature limits remain unchanged.

## Included

* Debug and unsigned-release builds, lint for both variants and an optional owner-signed release workflow. Missing owner secrets stop the manual signing workflow clearly. The ordinary build does not need those secrets.
* Actual APK SHA-256, manifest/permission checks, SDK signature verification, 64-bit ELF load-segment and ZIP-entry 16 KB alignment gates. No AI weights are bundled. Gradle uses uncompressed native packaging.
* A disposable-key CI check that signs a release through the real Gradle configuration and verifies its certificate against that supplied keystore. Production credentials are not generated, requested or included in an artifact.
* Best-effort development-key caching and explicit upgrade/signing instructions. A persistent owner key is the reliable production update path; caching cannot guarantee an older debug APK's signing identity.
* Settings → **CHECK DEVICE & BUILD**: Android/CPU, actual kernel page size, available memory/storage, H.264 encoders, app version and signing fingerprint. **EXPORT DEVICE REPORT** uses the Android document picker. Reports exclude identifiers, private paths, media, projects and backend settings and are never uploaded by the app.
* An actual v0.9 → v0.10 `adb install -r` workflow on Android 10 and 15. It retains an imported media hash, favorite project/recipe, DataStore settings and committed automatic history, then runs a fresh offline export after the update. No uninstall or data clear is used.

## Validation and boundaries

Python tests deliberately construct misaligned ELF/ZIP inputs and changed manifest permissions to verify the APK gate rejects them. Android tests inspect the installed build's local report and load the bundled ONNX native runtime. The normal encoder, timeline, persistence, UI, worker and backend suites remain enabled. Upgrade checks run separately because they must retain the old app's actual private data across two APK installations.

This phase does not claim app-store publication, a production signing identity, physical ARM/16 KB runtime validation or CUDA video inference. Static ELF/ZIP evidence is reported separately from emulator execution. See [release commands and signing details](RELEASE.md) and [current feature boundaries](STATUS.md).
