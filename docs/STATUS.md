# MVP boundaries and verification

This repository implements a native, installable Android MVP. The feature names reflect the product cartridges; conventional algorithms are labeled in the UI.

## Implemented

* Offline photo correction (brightness/exposure/contrast/color/white balance/saturation), denoising, sharpening, 2048-pixel decode cap, JPEG quality or lossless PNG export, original/result switching and zoom.
* Local Real-ESRGAN RGB float32 ONNX x4 inference with 64-pixel tiles / 16-pixel halos; x2 from the x4 result. Model and RAM checks; no bicubic-only AI fallback.
* Media3 video trim, merge with synchronized original audio, center crop to 9:16/1:1/16:9, split range into resumable drafts, timestamped editable captions. Media3 uses the device codecs and GPU effects.
* Histogram scene-change suggestions at one-second intervals; PCM quiet-audio timestamps. These are heuristic suggestions, not semantic AI highlight selection.
* Video enhancement: brightness, contrast, saturation. Photo and video outputs play in native previews.
* GIF from images/video with frame selection, 1–24 FPS, speed, captions, stickers, loop flag and dimensions. Real GIF89a, RGB332 palette, bounded-memory LZW blocks. Smaller dimensions/FPS reduce file size; adaptive palette optimization is not implemented. Maximum 240 frames. MP4 mode preserves video audio and trim; image sequence MP4 uses selected FPS. GIF-only styling controls are hidden for MP4.
* Optional real Stable Diffusion / CogVideoX inference, secure job API, model callbacks, result export and cancellation. Model weights must be obtained by the operator under their licenses.
* Project rename/duplicate/delete/favorite/resume; generation history and gallery filtering; three themes; sounds/haptics; cleanup; consent and Keystore token.

## Not implemented / practical limits

* Face restoration, semantic highlight scoring, automatic on-phone speech transcription, video stabilization, video denoising and video sharpening require additional models or processing implementations. The UI states those limits. Optional faster-whisper CLI creates timestamped captions to paste into the editor.
* Trim/split is based on time; D-pad increments 1/30 second, not guaranteed exact source frame boundaries for variable-FPS media. Preview seeking uses Android's player controls. Merging uses the order of selected imports; no drag reorder timeline yet.
* Before/after is a toggle with zoom, not a draggable image wipe.
* ONNX input must be dynamic spatial size or fixed 96×96, float32 `[1,3,H,W]`; output x4 RGB float32 NCHW. Phone input is sampled to at most 1024 pixels. RAM checks can reject much smaller sources on low-memory phones. No model download runs without your action.
* CogVideoX generates 49 native frames (roughly six seconds at 8 FPS); output is trimmed and center-cropped/resized. Motion slider changes descriptive prompt wording. Image-to-video uses the separate CogVideoX-5b-I2V model. GPU required for video. No claim that these models work on every phone.
* Local jobs use WorkManager's normal ten-minute execution window. Remote polling times out at eight minutes and requests cancellation/purge. Preload models; cold downloads can exceed that window. Server cancellation is checked between diffusion steps and after loading; it cannot interrupt an individual GPU kernel.
* Minimum Android 8; modern Photo Picker falls back to the system document picker where unavailable. 512 MB import/file, 12 MB server image upload, 200 MB generated download. GIFs are intentionally bounded for memory/storage.

## Verification policy

CI runs JVM encoder/rule tests, Android lint and APK compilation, and backend API tests. The instrumentation test needs a device and is not part of default CI. No physical-device or GPU-inference validation is claimed unless separately recorded. The app requires real model files and an operator-owned inference host for AI generation; production code never synthesizes placeholder results.
