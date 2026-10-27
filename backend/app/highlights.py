"""CPU CLIP visual relevance. No audio, video understanding or fabricated highlights."""
import json
import math
import os

from .inference import GenerationCancelled

MODEL_ID = "openai/clip-vit-base-patch32"
MODEL_REVISION = "3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268"


def rank_ranges(times, scores, duration_ms, clip_ms):
    if len(times) != len(scores) or not times or not all(math.isfinite(s) and -1.00001 <= s <= 1.00001 for s in scores):
        raise ValueError("Invalid CLIP measurements.")
    candidates = {}
    for center in times:
        start = max(0, min(center - clip_ms // 2, duration_ms - clip_ms))
        end = start + clip_ms
        measured = [s for t, s in zip(times, scores) if start <= t < end]
        candidates[start] = {"start_ms": start, "end_ms": end, "score": round(sum(measured) / len(measured), 6)}
    selected = []
    for candidate in sorted(candidates.values(), key=lambda c: (-c["score"], c["start_ms"])):
        if all(max(0, min(candidate["end_ms"], old["end_ms"]) - max(candidate["start_ms"], old["start_ms"])) / clip_ms <= 0.4 for old in selected):
            selected.append(candidate)
            if len(selected) == 5:
                break
    return selected


class HighlightEngine:
    def __init__(self):
        self.model = None
        self.processor = None

    def run(self, request, frames, job):
        import torch
        from PIL import Image
        from transformers import CLIPModel, CLIPProcessor

        def check():
            if job.cancel.is_set():
                raise GenerationCancelled()

        check()
        if self.model is None:
            job.progress, job.stage = None, "Loading CLIP visual model on CPU"
            # Operators explicitly install/cache weights. Requests never download models.
            location = os.environ.get("RETRO_CLIP_MODEL_DIR", "").strip()
            if not location:
                raise RuntimeError("Configure RETRO_CLIP_MODEL_DIR with a downloaded CLIP model.")
            processor = CLIPProcessor.from_pretrained(location, local_files_only=True)
            model = CLIPModel.from_pretrained(location, local_files_only=True).to("cpu").eval()
            self.processor, self.model = processor, model
        check()
        text = self.processor(text=[request.prompt], return_tensors="pt", padding=True, truncation=True, max_length=77)
        with torch.inference_mode():
            query = self.model.get_text_features(**text)
            query = query / query.norm(dim=-1, keepdim=True).clamp_min(1e-12)
        scores = []
        for index in range(len(request.timestamps_ms)):
            check()
            job.stage = f"Comparing sampled frame {index + 1}/{len(request.timestamps_ms)}"
            with Image.open(frames / f"{index:04d}.jpg") as image:
                pixels = self.processor(images=image.convert("RGB"), return_tensors="pt")
            with torch.inference_mode():
                embedding = self.model.get_image_features(**pixels)
                embedding = embedding / embedding.norm(dim=-1, keepdim=True).clamp_min(1e-12)
                scores.append(float((embedding @ query.T).item()))
            job.progress = (index + 1) * 99 // len(request.timestamps_ms)
        check()
        result = {
            "version": 1, "duration_ms": request.duration_ms, "sample_count": len(scores),
            "model": MODEL_ID, "metric": "cosine_similarity",
            "ranges": rank_ranges(request.timestamps_ms, scores, request.duration_ms, request.clip_ms),
            "samples": [{"time_ms": t, "score": round(s, 6)} for t, s in zip(request.timestamps_ms, scores)],
        }
        output = job.directory / "highlights.json"
        output.write_text(json.dumps(result), encoding="utf-8")
        return output, "application/json"
