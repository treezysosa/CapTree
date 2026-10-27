# Phase 9: automatic drafts and process recovery

Version 0.9 adds durable editor checkpoints and undo/redo recovery. Every editing workspace gets its own automatic draft; opening a different cartridge starts a fresh workspace without overwriting earlier drafts. Empty, unchanged workspaces are not saved.

## Using automatic drafts

* Edits queue a checkpoint after a 500 ms pause. The editor shows **AUTO-SAVING**, then **AUTO-SAVED** only after the database commit succeeds. Leaving the editor or stopping the activity also requests an immediate save. Saves continue in the application scope during activity recreation.
* **CONTINUE LAST DRAFT** on Home opens the most recently edited checkpoint. In Save Games, select **Autosaves** to browse all automatic drafts. **RESTORE AUTO-SAVE** restores the current edit and available undo/redo history without starting a processing job.
* **KEEP AS PROJECT** creates a normal unfinished project from that checkpoint. Use its existing **BACKUP PROJECT** action to save a portable archive. Automatic history remains on this device; portable backups continue to exclude undo history, server settings and model weights.
* **DISCARD DRAFT** removes that automatic draft and its history. If it is the current workspace, its editor is reset. Existing exported media and normal saved projects are separate. Storage cleanup removes imports only after no saved project, automatic draft, retained history or current edit references them.

Checkpoints preserve inputs, timeline order, trim fields, caption text/source timing, image/GIF settings, prompts, seed text, selected block and playhead. Unfinished fields such as an empty trim end remain unfinished when recovered; they are not silently converted into a valid export. New manual unfinished projects also preserve raw trim and seed fields.

## Persistence and privacy

Room database v2 adds `editor_sessions` through an explicit v1→v2 migration; existing projects are retained. Lightweight summary queries keep large histories out of gallery/menu flows. Monotonic revisions prevent a delayed save replacing a newer one, and in-process deletion tombstones reject late writes after discard. Storage cleanup coordinates with the draft writer and pins pending media references.

Private checkpoints contain editing values, not backend URLs/tokens, work IDs or upload confirmations. Pending confirmation dialogs and transient analysis results are not restored. A recovered remote editor still needs normal current Settings consent and any required per-operation confirmation before upload. Restore itself performs no inference or network upload.

Automatic drafts are local app data, not an uninstall backup. They disappear if app data is cleared or the app is uninstalled. GitHub debug builds can have different signing keys; an incompatible installation may still require uninstall, so export portable backups first. Use a stable signing key for production upgrades. See [privacy](PRIVACY.md).

## Limits and failure states

Up to 30 undo/redo steps are retained, subject to a 1,000,000-byte checkpoint budget. Nearby history is prioritized when snapshots are large; the UI reports limited history. A current draft too large for that budget shows **AUTO-SAVE PAUSED** and keeps the existing committed checkpoint. Reduce text and choose **RETRY AUTO-SAVE**. Storage errors never produce a saved status.

Only committed checkpoints survive abrupt process termination. The most recent 500 ms of active typing, or a write interrupted before commit, can be lost. A hard process stop is not guaranteed to invoke Android lifecycle callbacks. Check the saved indicator before relying on a checkpoint. Missing current media prevents recovery with a clear error; missing media in older history removes those unavailable steps while preserving the current edit. Captions remain editable and normal export validation still applies.

## Validation

JVM tests exercise checkpoint round trips, incomplete fields, history ordering/budgets, transient/connection exclusions and retained media paths. Android 10/15 tests exercise actual Room persistence, v1 database migration with existing projects, stale/deleted write rejection, cleanup protection and the Home restore control.

The separate **Validate editor process recovery** workflow installs the APKs once, imports a real video through a content URI, prepares caption/trim edits with both undo and redo available, and commits the checkpoint. It then force-stops the actual app process and runs a second instrumentation in a new process. Recovery verifies the original media hash, unfinished-field rejection, caption-aware undo/redo, storage cleanup and a real H.264/AAC MP4 export. Logs, a report and the exported video are retained as CI artifacts. This is separate from the ordinary in-process recreation checks; no AI model changes are made in this phase.
