# Phase 5: captions, editing history and color quality

Scope: source-aware caption retiming through trims, splits, reordering and removal; a bounded undo/redo history; adaptive GIF palettes with optional Floyd–Steinberg dithering; and linked before/after video playback.

Validation uses JVM mapping/history/quantization tests and Android 10/15 emulator tests. GIF output must decode with independent desktop and Android readers. Caption edits must preserve association with the correct source clip, including split children and separately imported copies. Video comparisons must seek and play actual media with a shared playhead and a single audio source. CPU/emulator work continues without requiring a CUDA host or a physical phone.
