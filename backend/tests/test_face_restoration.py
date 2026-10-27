import importlib
import io
import time

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from backend.app.capabilities import features
from backend.app.contracts import FaceRestoration
from backend.app.face_restoration import NoFaceDetected, models_present


def test_face_requires_packages_and_explicit_model_files(tmp_path, monkeypatch):
    installed = {name: True for name in ("torch", "gfpgan", "facexlib", "cv2", "basicsr")}
    report = next(x for x in features(installed=installed, cuda=False, face_models_ready=False) if x["id"] == "face-restoration")
    assert not report["supported"] and "RETRO_FACE_MODEL_DIR" in report["reason"]
    report = next(x for x in features(installed=installed, cuda=False, face_models_ready=True, restoration_loaded=True) if x["id"] == "face-restoration")
    assert report["state"] == "ready" and not report["requires_cuda"]
    monkeypatch.setenv("RETRO_FACE_MODEL_DIR", str(tmp_path))
    assert not models_present()


def test_strength_validation():
    for strength in (0, -1, 2, float("nan")):
        with pytest.raises(ValueError):
            FaceRestoration(strength=strength)


def test_face_upload_protocol_cleanup_and_explicit_no_face_error(tmp_path, monkeypatch):
    module = importlib.import_module("backend.app.main")
    monkeypatch.setenv("RETRO_API_TOKEN", "face-test-only-abcdefghijklmnopqrstuvwxyz")
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module, "features", lambda *a, **k: features(installed={name: True for name in ("torch", "gfpgan", "facexlib", "cv2", "basicsr")}, cuda=False, face_models_ready=True))

    class FaceProtocolEngine:
        def run(self, request, image, job):
            with Image.open(image) as source:
                path = job.directory / "result.png"
                source.save(path)
                return path, "image/png"

    monkeypatch.setattr(module, "RESTORER", FaceProtocolEngine())
    module.JOBS.clear()
    image = io.BytesIO(); Image.new("RGB", (32, 32), "pink").save(image, "PNG")
    with TestClient(module.app) as client:
        client.headers["Authorization"] = "Bearer face-test-only-abcdefghijklmnopqrstuvwxyz"
        assert client.post("/v1/face-restorations", data={"payload": '{"strength":0}'}, files={"image": ("input.png", image.getvalue())}).status_code == 422
        assert client.post("/v1/face-restorations", files={"image": ("bad.png", b"bad")}).status_code == 422
        response = client.post("/v1/face-restorations", data={"payload": '{"strength":0.6}'}, files={"image": ("input.png", image.getvalue(), "image/png")})
        assert response.status_code == 202
        job_id = response.json()["id"]
        for _ in range(100):
            status = client.get(f"/v1/jobs/{job_id}").json()
            if status["status"] == "completed": break
            time.sleep(0.01)
        assert status["status"] == "completed"
        assert Image.open(io.BytesIO(client.get(f"/v1/jobs/{job_id}/result").content)).size == (32, 32)
        assert not (tmp_path / job_id / "input.png").exists()
        client.delete(f"/v1/jobs/{job_id}/purge")
        assert not (tmp_path / job_id).exists()
    module.JOBS.clear()

    class EmptyFaceEngine:
        def run(self, *args):
            raise NoFaceDetected()

    monkeypatch.setattr(module, "RESTORER", EmptyFaceEngine())
    source = tmp_path / "input.png"; source.write_bytes(image.getvalue())
    job = module.Job("no-face", tmp_path)
    module.run_job(job, FaceRestoration(), source)
    assert job.status == "failed" and "No suitable face" in job.error
    assert not source.exists()
