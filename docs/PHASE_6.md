# Phase 6: optional face restoration and portable projects

Scope: genuine GFPGAN v1.4 restoration through an optional self-hosted CPU backend, explicit processed-photo upload consent, model readiness and setup documentation, and portable project archives with local media and recipes. Offline editing remains available without a server. Backups exclude backend credentials and reset remote-processing consent on import.

Validation includes authenticated API tests, Android export/import and consent tests, malicious-archive checks, and a separate pretrained-model CPU workflow that exercises actual face detection, restoration, blending and result retrieval. CUDA generation and physical-phone testing remain outside the selected environment.

## Face restoration

See [FACE_RESTORATION.md](FACE_RESTORATION.md) for exact packages, verified downloads, hardware requirements, upload limits and licensing. The real [GFPGAN CPU model check passed](https://github.com/treezysosa/CapTree/actions/runs/40548268355), including the authenticated API. Android adds an optional face toggle/strength control to photo enhancement/upscaling and requires a separate processed-photo upload confirmation from both touch and virtual-A actions. The worker verifies consent again before upload. Face restoration is not offered for video.

## Portable projects

In Save Games, choose **BACKUP PROJECT** and save a `.retro.zip` file through Android's document picker. **IMPORT PROJECT BACKUP** restores it as a new save with new private-media paths. Drafts preserve their inputs and edits; completed saves also include the output. The current save and existing projects are not overwritten.

Archives preserve timeline order, source-caption timing, prompts, GIF options and other creation settings. They exclude server URLs/tokens, remote consent, work IDs and model weights. Face restoration is disabled in imported recipes and requires re-enabling/confirmation. Existing device Settings are not replaced. No import starts generation or uploads media automatically. Downloaded/generated media remains available from the restored result, and restored inputs can be resumed through normal editing/export.

Limits: at most 100 media assets, 512 MB per asset, 1 GB total media and 2 MB metadata. Running jobs must finish or be cancelled before backup. Each asset has a declared size and SHA-256; import also checks ZIP integrity. Unknown/duplicate entries, path traversal, deeply nested metadata, missing or oversized assets and checksum mismatches are rejected. Files stage in a private temporary folder and are moved to fresh names only after all checks. A failed database save or cancellation before commit removes staged/new files. Import needs free space for the declared media plus metadata overhead.

The transfer meter reflects bytes hashed/written/read. Transfers can be cancelled while active; the final database commit completes atomically. Backups contain your original media, outputs and caption text, so choose a storage location appropriate for that content. They are not encrypted and do not include the in-memory undo stack. Uninstalling the app still removes its private data; finish saving a backup first and keep its ZIP outside app-private storage.

On startup, private UUID staging folders left by an interrupted process are removed in the background. Cleanup captures that previous-process list before new imports can start, and leaves unrelated folders alone. Manual cache cleanup is blocked while an import/transfer is active.
