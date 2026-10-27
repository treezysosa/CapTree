"""Explicit operator download; the request-serving process only loads local files."""
import argparse
import hashlib
from pathlib import Path
from huggingface_hub import snapshot_download
from backend.app.highlights import MODEL_ID, MODEL_REVISION, WEIGHTS_SHA256

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Download the MIT-licensed OpenAI CLIP ViT-B/32 checkpoint (~600 MB). Review its model card first.")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    snapshot_download(MODEL_ID, revision=MODEL_REVISION, local_dir=args.output, allow_patterns=["*.json", "*.txt", "pytorch_model.bin", "README.md", "LICENSE*"])
    digest = hashlib.sha256()
    with (Path(args.output) / "pytorch_model.bin").open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != WEIGHTS_SHA256:
        raise RuntimeError("The downloaded CLIP checkpoint does not match the verified SHA-256.")
    print(f"CLIP revision {MODEL_REVISION} downloaded. Set RETRO_CLIP_MODEL_DIR to {args.output}.")
