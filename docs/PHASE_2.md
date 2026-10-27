# Phase 2: editing and Android verification

Scope: editable ordered clip timelines with per-clip trims and splits, seekable preview controls, SRT caption import/export and timed overlays, spatial GPU video denoising/sharpening, draggable photo comparisons, and executable Android emulator tests for actual media creation.

Acceptance checks: JVM tests for timeline/caption rules; Android lint and APK build; Android emulator tests for UI navigation, Room persistence, real animated GIF output, photo processing, and H.264/AAC trim/merge export. Emulator rendering is not a substitute for physical-phone testing. Full Real-ESRGAN/Stable Diffusion/CogVideoX model validation needs actual operator-supplied weights/hardware; a synthetic fixture is never reported as pretrained-model validation.
