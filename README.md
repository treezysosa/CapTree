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

GitHub Actions builds the APK on each push. Open **Actions → Build Android APK → latest successful run → retro-ai-studio-debug**, download the ZIP, and install `app-debug.apk`. Debug APKs are automatically signed with the runner's development key. Different runs may have different debug keys, so an older debug installation may need to be removed before installing a new build (export media first, or save portable project backups when available). A production release needs your own stable signing key.

## Working MVP

| Tool | Implementation / requirements |
|---|---|
| AI Photo Upscaler | Real-ESRGAN-compatible ONNX inference on the phone, tiled RGB processing, 2×/4× output, noise reduction and sharpening. User imports the licensed model; optional self-hosted CPU face restoration runs after upscaling. See [model setup](docs/MODELS.md). |
| AI Video Generator | Actual self-hosted Diffusers CogVideoX inference, text-to-video or image-to-video, progress from diffusion callbacks, cancellation, MP4 preview/export. CUDA server required. |
| AI Photo Generator | Self-hosted Stable Diffusion 1.5 text-to-image / image-to-image, styles, negative prompts, seeds, generation history. |
| AI Clipper | Offline editable clip timeline, per-clip trim/split/reorder, H.264/AAC merge, crop, captions that follow source footage, server subtitles with audio-only consent, editable SRT, scene/quiet-audio scans actionable offline clip-range suggestions, and optional CPU CLIP visual matches with frame-upload consent. |
| Auto Enhance | Offline histogram / white-balance photo correction, denoise and sharpening; optional GFPGAN face restoration on your CPU server; GPU brightness/contrast/saturation, spatial denoising, sharpening and translation-only stabilization for video; photo wipe and linked video comparison. |
| AI GIF Generator | Offline video/images → LZW-compressed animated GIF or MP4, timing, dimensions, speed, captions and stickers. GIF supports adaptive colors, optional dithering, loop control and one-shot preview. Optional AI text → GIF through generated video frames. |

Three persistent console themes, D-pad/A/B/START/SELECT navigation, editor undo/redo, Android Photo Picker imports, private Room projects, portable project backups, DataStore settings, WorkManager jobs, native previews, save-to-gallery, Android share sheet, and SAF export are included. Android 8–9 uses **Export File** for scoped file creation; direct Gallery save needs Android 10+.

See [feature boundaries and verification](docs/STATUS.md), [backend setup](docs/BACKEND.md), and [privacy](docs/PRIVACY.md). Version 0.7 adds [Phase 7 CPU visual highlight suggestions](docs/PHASE_7.md), with [local model setup](docs/HIGHLIGHTS.md). Version 0.6 adds [Phase 6 face restoration and portable projects](docs/PHASE_6.md), with [verified CPU model setup](docs/FACE_RESTORATION.md) and explicit upload consent. [Phase 5](docs/PHASE_5.md) added caption retiming, undo/redo, adaptive GIF palettes/dithering and linked playback. [Phase 4](docs/PHASE_4.md) added stabilization, GIF compression, MP4 styling/speed and clip ranges. [Phase 3](docs/PHASE_3.md) covers earlier model validation and automatic subtitles. CUDA video-model and physical-phone validation remain, along with documented advanced editing limits. Unsupported model-dependent operations are described explicitly; no simulated AI outputs or progress.

## Architecture

`app/.../ui`: custom Compose console components and screens. `StudioViewModel`: editor state, imports, project actions. `data`: Room, DataStore, HTTPS job protocol, Keystore secrets. `media`: streaming GIF encoder, photo algorithms, ONNX tiling, Media3 video transformation and analysis. `work`: persisted background jobs. `backend`: optional authenticated FastAPI/Diffusers inference server.

## Tests

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest  # device/emulator required
python -m pip install -r backend/requirements-test.txt
python -m pytest backend/tests -q
```

Unit tests decode the generated GIF with an independent reader and check compression, colors, timing, trimming, memory limits, timeline splits/order, SRT timing, motion math and clip-range ranking. CI also runs real codec/GPU/WorkManager and UI instrumentation tests on Android 10 and Android 15 emulators, including encoded overlays, speed/audio alignment, stabilization and one-shot GIF playback. Backend tests validate authentication, untrusted uploads, request validation, the asynchronous job protocol, result download, and deletion. GPU model inference is not mocked in production; tests use a small deterministic engine solely to exercise the protocol without downloading multi-GB models.

## Pretrained-model verification

Dedicated CI checks execute official Real-ESRGAN x4plus on CPU and Android, Stable Diffusion 1.5 with 24 CPU inference steps, faster-whisper tiny on test speech, GFPGAN v1.4 with real face detection/restoration on CPU, and CLIP ViT-B/32 visual relevance with the authenticated API. See [evidence and remaining limits](docs/STATUS.md). These checks use real pretrained models; ordinary fast job-protocol tests deliberately substitute a small engine. The production APK contains no model weights and does not download them automatically.
