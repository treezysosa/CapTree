# Phase 5: captions, editing history and color quality

Scope: source-aware caption retiming through trims, splits, reordering and removal; a bounded undo/redo history; adaptive GIF palettes with optional Floyd–Steinberg dithering; and linked before/after video playback.

Validation uses JVM mapping/history/quantization tests and Android 10/15 emulator tests. GIF output must decode with independent desktop and Android readers. Caption edits must preserve association with the correct source clip, including split children and separately imported copies. Video comparisons must seek and play actual media with a shared playhead and a single audio source. CPU/emulator work continues without requiring a CUDA host or a physical phone.

## Captions and history

Captions now follow their source footage through trim, split, move and remove actions, including **Use This Range**. A cue crossing a cut is clipped into the retained portions; reordering those portions carries their text with them. Adjacent parts are rejoined when playback stays continuous. Separately imported clips retain separate identities even when their file paths match.

The saved project retains the source caption timings, so shortening a trim hides text and expanding it restores the covered portions. Editing/importing captions or applying a fresh transcript starts a new caption source from the current timeline. The original source-cue data is private project metadata and is excluded from backend uploads. Storage cleanup keeps source files referenced by saved caption sources and current undo/redo states.

**UNDO / REDO** retains up to 30 editor steps in memory, including media imports, settings, captions and timeline actions. Quick edits to the same text field or slider are grouped; seeking and preview playback do not create history entries. Opening/resuming another project resets history. Saving a draft preserves media, current options and retained caption timings, but does not persist the undo stack. Invalid partial trim input can be corrected without changing the last valid timeline; export rejects unfinished trim fields. Invalid caption syntax must be fixed before a timeline edit can retime it. Cues remain limited to 2000; an edit that exceeds the fragment limit is rejected.

## GIF color controls

Choose **Classic 256**, **Adaptive 64**, **Adaptive 128**, or **Adaptive 256** in the GIF editor. Classic retains RGB332 colors. Adaptive uses per-frame local color tables with a weighted median-cut palette and cached nearest-color lookup. Frames already within the selected color budget retain their colors exactly. The selected number is a maximum palette size, not a guaranteed count. Older saved projects retain Classic 256 until changed.

**Dither gradients** enables Floyd–Steinberg error diffusion for adaptive palettes. Dithering reduces visible bands but can add grain and increase file size. Frame-local palettes can vary across an animation; Classic provides consistent fixed colors. Adaptive quantization adds CPU work and checks for cancellation while constructing colors and processing rows. Encoding remains bounded to one frame at a time and the existing 240-frame limit. Color palettes affect GIF output; MP4 uses its codec's color encoding.

## Linked video comparison

For an enhanced video, open its saved result and choose **Linked** under Comparison. Original and enhanced videos appear side by side with shared PLAY/PAUSE, RESTART and seek controls. Only original audio is audible. Playback is limited to the shorter duration, pauses when the app leaves the foreground, and releases both players when the comparison closes. Before/After single previews remain available on devices that cannot decode both streams at once.

The players periodically correct drift; this is a visual comparison rather than guaranteed frame-accurate synchronization. Two simultaneous decoders use more memory and device resources than a single preview. No additional AI model or server is required.
