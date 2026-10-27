"""Explicit operator download; the request-serving process only loads local files."""
import argparse
from huggingface_hub import snapshot_download
from backend.app.highlights import MODEL_ID, MODEL_REVISION

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Download the MIT-licensed OpenAI CLIP ViT-B/32 checkpoint (~600 MB). Review its model card first.")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    snapshot_download(MODEL_ID, revision=MODEL_REVISION, local_dir=args.output, allow_patterns=["*.json", "*.txt", "pytorch_model.bin", "README.md", "LICENSE*"])
    print(f"CLIP revision {MODEL_REVISION} downloaded. Set RETRO_CLIP_MODEL_DIR to {args.output}.")
