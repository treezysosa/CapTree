# Offline speech model setup

Android uses `com.alphacephei:vosk-android:0.3.75` and `net.java.dev.jna:jna:5.18.1`, following the upstream Android demo dependencies. The optional small English model is free and listed under Apache 2.0 on the [official model page](https://alphacephei.com/vosk/models). The [Apache license](../app/src/main/assets/licenses/Vosk-Apache-2.0.txt) is included in app assets. JNA is used under its Apache 2.0 option; see the third-party notices.

Download the original archive, keep it zipped, and import it through Settings:

```text
URL: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
Size: 41205931 bytes
SHA-256: 30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498
```

Renaming the ZIP is fine; modifying/repacking it is not. Only this verified model is accepted in this version. The app does not infer model language from an arbitrary directory. Other Vosk checkpoints need an explicit supported profile and tests before they can be added.

The importer uses private staging, rejects wrong size/hash, unknown/duplicate/traversal entries and excessive extraction sizes, then loads the actual model and recognizes a silence probe. That proves compatibility, not accuracy on your speech. Replacement keeps the previous model until the new one passes. Startup restores an old model if a process interruption occurred between replacement moves, then removes captured stale UUID staging folders. Ordinary cache cleanup does not remove the installed speech model.

The model lives in app-private storage and remains available offline across launches. The app reads audio from imported files, so no microphone permission is requested. The offline worker never constructs a backend client. Remote consent can stay off. The server's language selector applies only to server transcription; offline recognition is English.

## Validation

The [initial real CPU check passed](https://github.com/treezysosa/CapTree/actions/runs/40581995894): official model, original espeak-ng speech, actual Vosk CPU inference and timed words. It recognized all five spoken number words; it also misrecognized part of the sentence, illustrating the need to review captions. This is a compatibility check, not an accuracy benchmark.

The separate **Validate offline speech model** workflow supplies the verified weights only to instrumentation tests, runs real recognition on Android 10 and 15 with remote consent disabled, applies the resulting captions to a saved multi-block timeline, and exports an actual MP4. It also checks silence, cancellation, model readiness, microphone permissions and provenance. Normal CI does not bundle pretrained models and skips this model-dependent test; it runs PCM/timing/import tests plus the rest of the app's suite.

To repeat CPU validation:

```sh
python -m pip install vosk==0.3.45
# Install espeak-ng and ffmpeg on your machine first.
python tools/validate_offline_speech.py
```

Outputs are written under `model-validation/offline-speech`, including the archive hash, original fixture audio/video, timed words and transcript. The GitHub workflow uses the same fixtures for Android. No model files are included in the production APK or source archive.
