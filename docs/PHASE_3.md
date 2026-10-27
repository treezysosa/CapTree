# Phase 3: AI readiness and automatic subtitles

Scope: test genuine pretrained Real-ESRGAN inference (including Android ONNX), exercise real image-generation inference on available CPU hardware, report backend package/GPU/model readiness, validate imported ONNX models on the phone, and add consent-based automatic subtitles through a self-hosted faster-whisper server.

Model tests use real upstream weights and retain provenance and output artifacts. They are separate from fast API tests that substitute a small engine to verify the job protocol. No synthetic model is reported as Real-ESRGAN validation. Test model weights are never bundled in the production APK.

CUDA video-generation and physical-phone validation require hardware not attached to this workspace. They remain explicit limits unless a corresponding real test succeeds. Offline editing remains available without a server or model downloads.

Implemented: actual on-device inference probes and diagnostics; backend API v2 readiness reports; selected-audio-only subtitle jobs with explicit consent; editable SRT application to the saved timeline. Genuine pretrained-model checks and their evidence are recorded in STATUS.md. Android regression tests additionally exercise PCM extraction and the consent screen; transport tests verify that project paths never enter multipart requests.
