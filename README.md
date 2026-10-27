# RETRO AI STUDIO for Android

An original handheld-inspired **native Kotlin + Jetpack Compose** media studio. No WebView, accounts, ads, analytics, subscriptions, credits, or watermarks. Android 8.0+ (API 26), target Android 15.

## Build and install

Open this folder in Android Studio (Meerkat or newer), install Android SDK 35, and use JDK 17. Then:

```sh
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows: `gradlew.bat testDebugUnitTest lintDebug assembleDebug`.

GitHub Actions builds the APK on each push. Open **Actions → Build Android APK → latest successful run → retro-ai-studio-debug**, download the ZIP, and install `app-debug.apk`. Debug APKs are automatically signed with the development key. A production release needs your own signing key.

## Working MVP

| Tool | Implementation / requirements |
|---|---|
| AI Photo Upscaler | Real-ESRGAN-compatible ONNX inference on the phone, tiled RGB processing, 2×/4× output, noise reduction and sharpening. User imports the licensed model; see [model setup](docs/MODELS.md). |
| AI Video Generator | Actual self-hosted Diffusers CogVideoX inference, text-to-video or image-to-video, progress from diffusion callbacks, cancellation, MP4 preview/export. CUDA server required. |
| AI Photo Generator | Self-hosted Stable Diffusion 1.5 text-to-image / image-to-image, styles, negative prompts, seeds, generation history. |
| AI Clipper | Offline Media3 H.264/AAC MP4 trimming and merging, split-to-drafts, crop, manual timed captions, scene-cut and quiet-audio suggestions. |
| Auto Enhance | Offline histogram / white-balance photo correction, denoise and sharpening; GPU brightness/contrast/saturation for video. |
| AI GIF Generator | Offline video/images → genuine animated GIF, timing, dimensions, speed, captions, stickers, loop flag, or MP4. Optional AI text → GIF through generated video frames. |

Three persistent console themes, D-pad/A/B/START/SELECT navigation, Android Photo Picker imports, private Room projects, DataStore settings, WorkManager jobs, native previews, save-to-gallery, Android share sheet, and SAF export are included. Android 8–9 uses **Export File** for scoped file creation; direct Gallery save needs Android 10+.

See [feature boundaries and verification](docs/STATUS.md), [backend setup](docs/BACKEND.md), and [privacy](docs/PRIVACY.md). This is the first working MVP, not every advanced feature in the longer product brief. Unsupported model-dependent operations are described explicitly; no simulated AI outputs or progress.

## Architecture

`app/.../ui`: custom Compose console components and screens. `StudioViewModel`: editor state, imports, project actions. `data`: Room, DataStore, HTTPS job protocol, Keystore secrets. `media`: streaming GIF encoder, photo algorithms, ONNX tiling, Media3 video transformation and analysis. `work`: persisted background jobs. `backend`: optional authenticated FastAPI/Diffusers inference server.

## Tests

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest  # device/emulator required
python -m pip install -r backend/requirements-test.txt
python -m pytest backend/tests -q
```

Unit tests decode the generated GIF with an independent reader and check frame count, colors, frame timing, trimming, and memory limits. Backend tests validate authentication, untrusted uploads, request validation, the asynchronous job protocol, result download, and deletion. GPU model inference is not mocked in production; tests use a small deterministic engine solely to exercise the protocol without downloading multi-GB models.
