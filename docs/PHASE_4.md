# Phase 4: reliable offline motion and animation exports

Scope: translation-only video stabilization with measured motion and border cropping; compressed GIF encoding; GIF/MP4 animation caption, sticker, size and speed controls; functional one-shot GIF playback; and actionable clip-range suggestions based on scene/audio signals. Suggestions are conventional heuristics, not semantic AI scoring.

Validation uses JVM codec/math tests and Android 10/15 emulator tests with original synthetic media. Stabilization is accepted only when measured jitter decreases on known shaky footage. MP4 checks cover real encoded frames, duration, and audio timing. Existing genuine AI-model evidence remains separate. CUDA generation and physical-phone validation are not executed in the selected CPU/emulator environment.

## Use the new workflows

1. **Auto Enhance → Import Video → Translation stabilization → Start Processing.** Intensity controls correction strength and border zoom (up to 1.22×). The analysis meter reports sampled frames; the render meter uses Media3's reported progress. Sources must be at most two minutes; trim longer footage in Clipper first. A textured, mostly stationary background is needed. Rotation, rolling shutter and moving subjects can defeat translation estimation. Review the exported result before deleting the original.
2. **AI GIF Generator → Import Photos/Video → GIF or MP4.** Both formats apply the caption, sticker, maximum edge and speed controls. MP4 video audio follows the selected speed. Images with different dimensions are fitted into the first image's aspect ratio with black borders. Video FPS caps output frames without generating intermediate motion. Image MP4 supports FPS × speed up to 60. GIF remains limited to 240 frames, RGB332 colors and centisecond timing; its LZW dictionary now compresses repeated pixel sequences. There is no adaptive palette optimizer. Disable Loop for a one-shot GIF; the preview stops on its final frame and PLAY restarts it. STOP returns to the first frame.
3. **AI Clipper → select/trim a block → Suggest Clip Ranges.** The selected range must be at most two minutes. Scene histograms, low-energy audio windows and existing caption activity determine up to five candidate ranges, each at most eight seconds. The displayed score is a signal ranking, not an AI probability. Silent-source videos fall back to scene/caption signals. **Use This Range** changes the selected block's trim and seeks its preview. Captions use output timeline times and need review after a trim or reorder.

These workflows stay on the device and require no model download, account or server. The separately configured AI generators and speech service retain their existing remote-processing consent requirements.

## Regression checks

`./gradlew testDebugUnitTest lintDebug assembleDebug` checks compression with an independent GIF decoder, dictionary resets, known translations, smoothing around scene cuts, candidate ranking and the existing media rules.

`./gradlew connectedDebugAndroidTest` additionally decodes real Android outputs. Phase 4 tests check a styled image MP4, video overlay appearance/expiry, speed-adjusted audio/video end times, reduced translation jitter, one-shot GIF restart, and the UI action that scans and applies a clip range. Original synthetic fixtures avoid external footage or personal media. CI runs these on API 29 and API 35 with software GPU rendering; this does not establish physical-device codec compatibility.

The timed-overlay test checks indexed display frames against encoded presentation timestamps, avoiding device-specific time-seek behavior. Failed overlay checks retain their synthetic input/output MP4s in the device-verification artifact for inspection. Emulator jobs compile their own APKs and run independently of the JVM/lint build.

Video FPS is an upper target; actual encoded frame counts can be lower with a variable-rate source or device codec. Regression checks verify the selected duration, visible/expired caption intervals and the frame-rate cap instead of requiring a fixed output count. Image-animation checks additionally verify that both differently colored source images appear in the MP4.
