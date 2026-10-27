"""Exercise real GFPGAN/RetinaFace/ParseNet CPU inference and the authenticated API."""
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import threading
import time
from types import SimpleNamespace
import urllib.request

import numpy as np
from PIL import Image, ImageFilter
from skimage import data
import torch

from backend.app.contracts import FaceRestoration
from backend.app.face_restoration import FaceRestorationEngine, NoFaceDetected, WEIGHTS
from backend.app.inference import GenerationCancelled

output = Path("model-validation/face").resolve()
output.mkdir(parents=True, exist_ok=True)
weights = Path(".face-model-cache").resolve(); weights.mkdir(exist_ok=True)
urls = [
    "https://github.com/TencentARC/GFPGAN/releases/download/v1.3.0/GFPGANv1.4.pth",
    "https://github.com/xinntao/facexlib/releases/download/v0.1.0/detection_Resnet50_Final.pth",
    "https://github.com/xinntao/facexlib/releases/download/v0.2.2/parsing_parsenet.pth",
]
provenance = []
for name, url in zip(WEIGHTS, urls):
    path = weights / name
    if not path.exists():
        urllib.request.urlretrieve(url, path)
    provenance.append({"file": name, "url": url, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
os.environ["RETRO_FACE_MODEL_DIR"] = str(weights)
torch.set_num_threads(2)
torch.set_num_interop_threads(1)

# scikit-image's astronaut image is a public-domain NASA photograph.
source = Image.fromarray(data.astronaut()).resize((256, 256)).resize((512, 512)).filter(ImageFilter.GaussianBlur(0.35))
source.save(output / "input.png")
engine = FaceRestorationEngine()
job = SimpleNamespace(directory=output, cancel=threading.Event(), stage="", progress=None)
started = time.monotonic()
result, mime = engine.run(FaceRestoration(strength=0.7), output / "input.png", job)
with Image.open(result) as image:
    pixels = np.asarray(image.convert("RGB"))
    assert image.size == source.size
change = np.abs(pixels.astype(np.int16) - np.asarray(source).astype(np.int16)).mean()
assert change > 0.05, "Actual inference must change the detected face output."

blank = output / "blank.png"; Image.new("RGB", (256, 256), "white").save(blank)
try:
    engine.run(FaceRestoration(), blank, job)
    raise AssertionError("A blank photo must not be labeled restored.")
except NoFaceDetected:
    pass

real_model = engine.model
class BrokenModel:
    def __call__(self, *args, **kwargs):
        raise RuntimeError("injected inference failure")
engine.model = BrokenModel()
try:
    engine.run(FaceRestoration(), output / "input.png", job)
    raise AssertionError("Model failure must not silently return an unchanged face.")
except RuntimeError as error:
    assert "injected" in str(error)
finally:
    engine.model = real_model

os.environ["RETRO_API_TOKEN"] = secrets.token_urlsafe(32)
os.environ["RETRO_OUTPUT_DIR"] = str(output / "jobs")
from fastapi.testclient import TestClient
from backend.app import main
main.RESTORER = engine
with TestClient(main.app) as client:
    client.headers["Authorization"] = "Bearer " + os.environ["RETRO_API_TOKEN"]
    feature = next(x for x in client.get("/v1/health").json()["features"] if x["id"] == "face-restoration")
    assert feature["state"] == "ready" and not feature["requires_cuda"]
    response = client.post("/v1/face-restorations", data={"payload": '{"strength":0.7}'}, files={"image": ("input.png", (output / "input.png").read_bytes(), "image/png")})
    assert response.status_code == 202, response.text
    job_id = response.json()["id"]
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        status = client.get(f"/v1/jobs/{job_id}").json()
        if status["status"] in ("completed", "failed"): break
        time.sleep(0.2)
    assert status["status"] == "completed", status
    response = client.get(f"/v1/jobs/{job_id}/result")
    assert response.headers["content-type"] == "image/png"
    (output / "api-result.png").write_bytes(response.content)
    assert Image.open(io.BytesIO(response.content)).size == source.size
    client.delete(f"/v1/jobs/{job_id}/purge")
    assert not (main.ROOT / job_id).exists()

report = {"model": "GFPGAN v1.4 clean + RetinaFace ResNet50 + ParseNet", "device": "CPU", "seconds": time.monotonic() - started, "source": "scikit-image astronaut / public-domain NASA photograph", "output_dimensions": list(source.size), "mean_pixel_change": float(change), "output_sha256": hashlib.sha256(result.read_bytes()).hexdigest(), "weights": provenance, "checks": ["actual face detection, restoration and blending", "authenticated API with real inference", "no-face rejection", "inference error propagation without unchanged fallback", "job purge"]}
(output / "report.json").write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
