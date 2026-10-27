import importlib
import io
import time

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from backend.app.contracts import Generation


@pytest.fixture
def api(tmp_path, monkeypatch):
    monkeypatch.setenv("RETRO_API_TOKEN", "test-token-only-abcdefghijklmnopqrstuvwxyz")
    module = importlib.import_module("backend.app.main")
    monkeypatch.setattr(module, "ROOT", tmp_path)
    module.JOBS.clear()
    with TestClient(module.app) as client:
        client.headers["Authorization"] = "Bearer test-token-only-abcdefghijklmnopqrstuvwxyz"
        yield client, module
    module.JOBS.clear()


def test_auth_required(api):
    client, _ = api
    assert client.get("/v1/health", headers={"Authorization": ""}).status_code == 401
    assert client.get("/v1/health").status_code == 200


def test_validation_and_path_traversal(api):
    client, _ = api
    assert client.post("/v1/jobs", data={"payload": '{"tool":"shell","prompt":"hello"}'}).status_code == 422
    assert client.post("/v1/jobs", data={"payload": '{"tool":"photo","prompt":""}'}).status_code == 422
    assert client.get("/v1/jobs/not-a-job/result").status_code == 404
    response = client.post("/v1/jobs", data={"payload": '{"tool":"photo","prompt":"hello"}'}, files={"image": ("photo.png", b"not an image", "image/png")})
    assert response.status_code == 422


def test_real_job_lifecycle_protocol_without_loading_gpu_models(api, monkeypatch):
    client, module = api

    class DeterministicTestEngine:
        def run(self, request, image, job):
            path = job.directory / "result.png"
            Image.new("RGB", (16, 16), "purple").save(path)
            return path, "image/png"

    monkeypatch.setattr(module, "ENGINE", DeterministicTestEngine())
    response = client.post("/v1/jobs", data={"payload": '{"tool":"photo","prompt":"violet console"}'})
    assert response.status_code == 202
    job_id = response.json()["id"]
    for _ in range(100):
        status = client.get(f"/v1/jobs/{job_id}").json()
        if status["status"] == "completed":
            break
        time.sleep(0.01)
    assert status["status"] == "completed"
    result = client.get(f"/v1/jobs/{job_id}/result")
    assert result.status_code == 200
    assert Image.open(io.BytesIO(result.content)).size == (16, 16)
    assert client.delete(f"/v1/jobs/{job_id}/purge").status_code == 200
    assert client.get(f"/v1/jobs/{job_id}").status_code == 404
    assert not (module.ROOT / job_id).exists()


def test_aspects_and_request_limits():
    assert Generation(tool="photo", prompt="hello", aspect="9:16").dimensions == (432, 768)
    with pytest.raises(ValueError):
        Generation(tool="video", prompt="hello", duration=60)
