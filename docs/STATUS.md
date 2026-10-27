# Version 0.3 boundaries and verification

This repository implements a native, installable Android MVP. The feature names reflect the product cartridges; conventional algorithms are labeled in the UI.

## Implemented

* Offline photo correction (brightness/exposure/contrast/color/white balance/saturation), denoising, sharpening, 2048-pixel decode cap, JPEG quality or lossless PNG export, original/result switching and zoom.
* Local Real-ESRGAN RGB float32 ONNX x4 inference with 64-pixel tiles / 16-pixel halos; x2 from the x4 result. Model and RAM checks, real on-device inference probes before installation, a repeatable Settings self-test, and model SHA-256 diagnostics; no bicubic-only AI fallback.
* Editable Media3 clip timeline with per-block trims, split at playhead, add/remove/reorder controls, synchronized H.264/AAC merge (including silent clips), 9:16/1:1/16:9 crop, timed caption overlays, and editable SRT import/export. Media3 uses the device codecs and GPU effects.
* Histogram scene-change suggestions at one-second intervals; PCM quiet-audio timestamps. These are heuristic suggestions, not semantic AI highlight selection.
* Video enhancement: brightness, contrast, saturation, edge-aware spatial GPU denoising, and unsharp-mask sharpening. Photo comparisons offer original/result switching plus a draggable wipe with shared zoom. Video exports tone-map HDR to SDR.
* GIF from images/video with frame selection, 1–24 FPS, speed, captions, stickers, loop flag and dimensions. Real GIF89a, RGB332 palette, bounded-memory LZW blocks. Smaller dimensions/FPS reduce file size; adaptive palette optimization is not implemented. Maximum 240 frames. MP4 mode preserves video audio and trim; image sequence MP4 uses selected FPS. GIF-only styling controls are hidden for MP4.
* Optional real Stable Diffusion / CogVideoX inference, secure job API, hardware/package readiness reports, model callbacks, result export and cancellation. Unloaded models are distinguished from supported hardware and loaded pipelines. Models must be obtained under their licenses.
* Automatic subtitles via the optional faster-whisper server: explicit audio-upload confirmation, selected-range PCM WAV extraction on the phone, CPU speech inference, editable/exportable SRT results, and applying captions to the saved timeline snapshot. Only the selected audio is uploaded; video frames and local project paths are excluded. Requires backend v0.3 / API v2 and remote consent.
* Project rename/duplicate/delete/favorite/resume; generation history and gallery filtering; three themes; sounds/haptics; cleanup; consent and Keystore token.

## Not implemented / practical limits

* Face restoration, semantic highlight scoring, automatic on-phone speech transcription, and video stabilization require additional models or processing implementations. The UI states those limits. Speech recognition currently runs on a user-owned server or through the optional local-PC faster-whisper CLI; on-phone speech inference is not included.
* Trim/split is based on time; D-pad seeks the selected preview in 1/30-second steps, not guaranteed exact source-frame boundaries for variable-FPS media. Timeline blocks use explicit move-left/right controls. Captions use output timeline times and are not automatically retimed after a trim/reorder. SRT supports up to 2000 cues; invalid timestamps are rejected.
* ONNX input must be dynamic spatial size or fixed 96×96, float32 `[1,3,H,W]`; output x4 RGB float32 NCHW. Phone input is sampled to at most 1024 pixels. RAM checks can reject much smaller sources on low-memory phones. No model download runs without your action.
* CogVideoX generates 49 native frames (roughly six seconds at 8 FPS); output is trimmed and center-cropped/resized. Motion slider changes descriptive prompt wording. Image-to-video uses the separate CogVideoX-5b-I2V model. GPU required for video. No claim that these models work on every phone.
* Local jobs use WorkManager's normal ten-minute execution window. Remote polling times out at eight minutes and requests cancellation/purge. Preload models; cold downloads can exceed that window. Server cancellation is checked between diffusion steps and after loading; it cannot interrupt an individual GPU kernel.
* Minimum Android 8; modern Photo Picker falls back to the system document picker where unavailable. 512 MB import/file, 12 MB server image upload, ten minutes / 64 MB per selected subtitle audio upload, 1 MB subtitle download, 200 MB generated media download. GIFs are intentionally bounded for memory/storage.

## Verification policy

CI runs JVM encoder/timeline/caption tests, Android lint, APK compilation, backend API tests, and Android 10/API 29 plus Android 15/API 35 emulator tests. Instrumentation exercises real photo correction, animated GIF decoding, the import/WorkManager/Room workflow, audio/video trim/merge/crop, caption rendering, GPU shader export, and UI split/reorder controls. Separate pretrained-model checks have successfully run official Real-ESRGAN x4plus through PyTorch/ONNX numerical comparison and Android output comparison, Stable Diffusion 1.5 at 512×512 / 24 CPU steps, and faster-whisper tiny on generated test speech. These are actual model executions, not the protocol test doubles. CUDA video generation and physical-phone testing remain unvalidated. The app requires real model files and an operator-owned inference host for AI generation; production code never synthesizes placeholder results.

## Model evidence

Real-ESRGAN / Stable Diffusion / Android comparison: https://github.com/treezysosa/CapTree/actions/runs/40516628799

Real faster-whisper CPU speech inference: https://github.com/treezysosa/CapTree/actions/runs/40518821390

Artifacts contain the output images/subtitles, timings, input/output dimensions, and hashes. Compatibility smoke tests do not certify output quality on every device or prompt. Automatic captions need review. Test-only model weights are never included in the production APK.
