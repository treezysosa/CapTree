# Phase 7: CPU visual highlight suggestions

Version 0.7 adds genuine pretrained CLIP ViT-B/32 visual relevance to AI Clipper, through the optional self-hosted CPU backend. The existing scene/silence/caption suggestions stay available offline. CLIP compares small still frames with a text description; it does not perform speech understanding or full video/action reasoning.

## Use on Android

1. Set up [the CPU CLIP server](HIGHLIGHTS.md), configure its HTTPS URL/token in Settings, and allow remote processing. Test Connection reports `semantic-highlights` as unsupported, not loaded, or ready.
2. Import a video in AI Clipper. Select a timeline block and finish its trim (one second to two minutes). Enter a visual description such as “a person holding a guitar,” and choose a suggested clip length. Shorter source trims cap that length.
3. Press **AI VISUAL HIGHLIGHTS · SERVER**, review the target and disclosure, then choose **SEND FRAMES**. The app saves a timeline snapshot and sends only the description, relative sample times, settings and up to 24 JPEG thumbnails. It sends neither audio nor the full video. Cancellation is available on the processing screen.
4. Open the completed result and choose **REVIEW AI HIGHLIGHT RANGES**. The saved timeline reopens with measured ranges. **PREVIEW MATCH** seeks to a candidate's center in the selected source; **USE AI RANGE** trims that block, retimes its captions and creates an undoable edit. Export MP4 through the normal offline workflow. Applying a result uses its saved snapshot; it does not silently modify another open project.

Prompts and requested lengths survive draft/resume and portable backup. Completed JSON reports can also be exported/shared/backed up. Import resets upload consent; retry asks for frame confirmation again. Suggestions clear after changing the relevant trim, selected block, description or requested length.

## What the scores mean

Frames are sampled at equal-bin centers, at most 24, each at most 256 pixels on its longest edge. CLIP normalizes image/text embeddings and measures their cosine similarity. Candidate windows average sampled-frame scores and suppress substantially overlapping results. Up to five candidates are returned. A score is not a probability, confidence percentage, quality judgment or guarantee that an event occurred. Even irrelevant footage has a highest score. Brief events between samples can be missed. English visual descriptions are recommended; review every cut.

Sampling uses Android's native decoder. Android 8.0 limits source frames to four megapixels to bound full-frame decoding; Android 8.1+ uses scaled decoding. Timed samples request the closest decoded frame, so they are approximate on variable-rate media. Frame preparation shows actual completed-frame progress; model loading is indeterminate and inference reports completed samples. Individual decoder/model operations may finish before cancellation is observed.

## Validation

The [real pretrained CLIP CPU check passed](https://github.com/treezysosa/CapTree/actions/runs/40578954665), including the authenticated API, result retrieval and private-frame cleanup. The public-domain NASA astronaut scored 0.298083 against a space-suit description; original gray controls scored 0.205806. This small compatibility check is not a general accuracy benchmark. The artifact records the pinned revision, weight SHA-256, timings, samples and resulting ranges.

Fast backend tests separately exercise request bounds, malformed images, authentication, failure/cancellation and cleanup with a protocol test engine. JVM tests verify sample plans, range validation, portable reports and TLS payload isolation. Android 10/15 instrumentation checks native JPEG sampling, consent cancellation, worker enforcement, saved-result application, caption retiming, undo/redo and real Media3 MP4 export. CUDA generation and physical-phone validation remain outside the selected CPU/emulator environment.
