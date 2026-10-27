# Phase 8: offline automatic English subtitles

Version 0.8 adds actual on-phone Vosk speech recognition to AI Clipper. After a one-time model import, a selected timeline block can be transcribed without a backend, account, microphone permission or audio upload. Existing server Whisper subtitles remain available for other languages and longer clips.

## Workflow

1. In Settings, choose **GET ENGLISH SPEECH MODEL** to open the official download in your browser. Import the original ZIP with **IMPORT ENGLISH MODEL ZIP**. The app checks its size/SHA-256, extracts only the known model files and runs an actual native recognizer before installation. Downloading is an explicit action; weights are not bundled or fetched automatically.
2. In AI Clipper, import a video, select a block and finish its trim. **OFFLINE ENGLISH SUBTITLES** is enabled when the model is installed. Keep the selected range within two minutes. Server settings and remote consent are not needed for this action.
3. WorkManager decodes only the selected audio, downmixes/resamples it to mono 16 kHz, and runs the English model on the phone. The local stage meter measures decoded/converted/recognized audio; model loading is indeterminate. Cancellation is checked between processing blocks. Individual native calls must return before cancellation can finish.
4. Open the saved SRT result and choose **REOPEN TIMELINE WITH SUBTITLES**. Recognized cues replace the selected saved output range and preserve captions outside it. Review/edit text and timing, then export the normal captioned MP4. Applying a result uses its saved timeline snapshot.

Subtitles and model provenance stay in local projects; portable project backups preserve the SRT and speech engine/model metadata. Model weights are excluded from backups. Imported offline-subtitle projects need the model installed before being transcribed again. Removing the model does not delete existing captions.

## Limits

The supported model is small US English Vosk 0.15. It can misrecognize accents, names, music, overlapping speakers and noisy speech. Word times come from the recognizer and are grouped into readable cues; they need review. There is no automatic punctuation, speaker diarization or translation. Empty/no-speech recognition fails clearly and never creates placeholder captions.

The model ZIP is about 40 MB; installation needs at least 256 MB free storage and inference requires at least 384 MB free system memory. Real usage depends on the device and other apps. Recognition is serialized to avoid loading several models at once. Decoded source WAV has the existing 64 MB bound; very high-rate multichannel clips may need a shorter trim. Model/decoder calls can take time on older CPUs, and normal WorkManager limits still apply. ARM phones and 16 KB page-size devices need separate physical-device verification; the chosen environment validates x86_64 Android emulators.

See [model setup and checksum](OFFLINE_SPEECH.md). Full motion/action highlight understanding, on-phone face restoration and CUDA video-model validation remain outside this phase.
