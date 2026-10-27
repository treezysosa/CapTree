"""Actual pretrained CPU CLIP and authenticated API, distinct from protocol doubles."""
import hashlib
import io
import json
import os
from pathlib import Path
import threading
import time

from fastapi.testclient import TestClient
from huggingface_hub import snapshot_download
from PIL import Image
from skimage.data import astronaut
import torch

from backend.app.contracts import Highlights
from backend.app.highlights import HighlightEngine, MODEL_ID, MODEL_REVISION

torch.set_num_threads(2)
output = Path("model-validation/highlights").resolve(); output.mkdir(parents=True, exist_ok=True)
model = snapshot_download(MODEL_ID, revision=MODEL_REVISION, allow_patterns=["*.json", "*.txt", "pytorch_model.bin"])
os.environ["RETRO_CLIP_MODEL_DIR"] = model
os.environ["RETRO_API_TOKEN"] = "cpu-validation-test-only-abcdefghijklmnopqrstuvwxyz"
os.environ["RETRO_OUTPUT_DIR"] = str(output / "jobs")
frames = output / "samples"; frames.mkdir(exist_ok=True)
plain = Image.new("RGB", (256, 256), "gray")
space = Image.fromarray(astronaut()).resize((256, 256))
for index, image in enumerate([plain, space, space, plain]):
    image.save(frames / f"{index:04d}.jpg", quality=90)
request = Highlights(prompt="a photograph of an astronaut in a space suit", duration_ms=8000, clip_ms=2000, timestamps_ms=[1000, 3000, 5000, 7000])
from types import SimpleNamespace
job = SimpleNamespace(directory=output, cancel=threading.Event(), stage="", progress=None)
started = time.monotonic()
engine = HighlightEngine()
path, mime = engine.run(request, frames, job)
result = json.loads(path.read_text())
scores = [s["score"] for s in result["samples"]]
assert scores[1] > scores[0] + 0.03 and scores[2] > scores[3] + 0.03, scores
assert result["ranges"][0]["start_ms"] in (2000, 4000), result
from backend.app import main
main.HIGHLIGHTER = engine
with TestClient(main.app) as client:
    client.headers["Authorization"] = "Bearer " + os.environ["RETRO_API_TOKEN"]
    ready = next(f for f in client.get("/v1/health").json()["features"] if f["id"] == "semantic-highlights")
    assert ready["state"] == "ready", ready
    uploads = [("frames", (p.name, p.read_bytes(), "image/jpeg")) for p in sorted(frames.glob("*.jpg"))]
    response = client.post("/v1/highlights", data={"payload": request.model_dump_json()}, files=uploads)
    assert response.status_code == 202, response.text
    job_id = response.json()["id"]
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        status = client.get(f"/v1/jobs/{job_id}").json()
        if status["status"] in ("completed", "failed"): break
        time.sleep(0.05)
    assert status["status"] == "completed", status
    remote = client.get(f"/v1/jobs/{job_id}/result").json()
    assert remote["ranges"][0]["start_ms"] in (2000, 4000), remote
    (output / "api-result.json").write_text(json.dumps(remote, indent=2))
    assert not (main.ROOT / job_id / "frames").exists()
    client.delete(f"/v1/jobs/{job_id}/purge")
    assert not (main.ROOT / job_id).exists()
report = {"model": MODEL_ID, "revision": MODEL_REVISION, "engine": "Transformers CLIP CPU float32", "seconds": time.monotonic() - started, "scores": scores, "assertions": "NASA astronaut outranks original plain gray samples by >0.03 cosine; authenticated API; private frame cleanup and purge", "source": "Public-domain NASA astronaut photograph distributed by scikit-image; original gray control", "weights_sha256": hashlib.sha256((Path(model) / "pytorch_model.bin").read_bytes()).hexdigest()}
(output / "report.json").write_text(json.dumps(report, indent=2))
print(json.dumps(report))
