import importlib
import io
import json
import threading
import time

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from backend.app.capabilities import features
from backend.app.contracts import Highlights
from backend.app.highlights import rank_ranges


def settings(**changes):
    return dict(prompt="an astronaut", duration_ms=8000, clip_ms=2000, timestamps_ms=[1000, 3000, 5000, 7000]) | changes


@pytest.mark.parametrize("changes", [dict(prompt=" "), dict(prompt="a" * 301), dict(duration_ms=120001), dict(clip_ms=9000), dict(timestamps_ms=[1000, 1000]), dict(timestamps_ms=[3000, 1000]), dict(timestamps_ms=[-1]), dict(timestamps_ms=[8000]), dict(timestamps_ms=[True]), dict(path="PRIVATE")])
def test_bounded_selected_trim_contract(changes):
    with pytest.raises(ValueError):
        Highlights(**settings(**changes))


def test_relative_ranges_rank_real_measurements_and_avoid_redundant_overlap():
    ranges = rank_ranges([1000, 3000, 5000, 7000], [0.1, 0.8, 0.4, 0.2], 8000, 4000)
    assert ranges[0] == {"start_ms": 3000, "end_ms": 7000, "score": 0.6}
    assert all(0 <= r["start_ms"] < r["end_ms"] <= 8000 for r in ranges)
    assert len(rank_ranges([500, 1500], [0.2, 0.3], 2000, 2000)) == 1
    with pytest.raises(ValueError):
        rank_ranges([500], [float("nan")], 2000, 2000)


def test_model_files_packages_and_loaded_readiness_are_distinct():
    for ready, loaded, state in [(False, False, "unsupported"), (True, False, "not_loaded"), (True, True, "ready")]:
        report = next(x for x in features(installed={"torch": True, "transformers": True}, cuda=False, clip_models_ready=ready, highlights_loaded=loaded) if x["id"] == "semantic-highlights")
        assert report["state"] == state and not report["requires_cuda"]


def test_authenticated_frames_protocol_rejects_bad_uploads_and_cleans_private_samples(tmp_path, monkeypatch):
    module = importlib.import_module("backend.app.main")
    monkeypatch.setenv("RETRO_API_TOKEN", "highlights-test-only-abcdefghijklmnopqrstuvwxyz")
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module, "features", lambda *a, **k: features(installed={"torch": True, "transformers": True}, cuda=False, clip_models_ready=True))

    class ProtocolEngine:
        def run(self, request, folder, job):
            assert sorted(p.name for p in folder.iterdir()) == ["0000.jpg"]
            with Image.open(folder / "0000.jpg") as image:
                assert image.size == (64, 32) and not image.getexif()
            output = job.directory / "highlights.json"
            output.write_text(json.dumps({"version": 1, "ranges": [{"start_ms": 0, "end_ms": 2000, "score": 0.2}]}))
            return output, "application/json"

    monkeypatch.setattr(module, "HIGHLIGHTER", ProtocolEngine())
    module.JOBS.clear()
    image = io.BytesIO(); Image.new("RGB", (64, 32), "pink").save(image, "JPEG")
    payload = json.dumps(settings(duration_ms=2000, timestamps_ms=[1000]))
    with TestClient(module.app) as client:
        assert client.post("/v1/highlights", data={"payload": payload}, files={"frames": ("a.jpg", image.getvalue())}).status_code == 401
        client.headers["Authorization"] = "Bearer highlights-test-only-abcdefghijklmnopqrstuvwxyz"
        def post(data, body=payload):
            return client.post("/v1/highlights", data={"payload": body}, files={"frames": ("../../PRIVATE.jpg", data, "image/jpeg")})
        assert post(image.getvalue(), json.dumps(settings())).status_code == 422
        assert post(b"bad").status_code == 422
        assert post(b"0" * (256 * 1024 + 1)).status_code == 413
        large = io.BytesIO(); Image.new("RGB", (257, 32)).save(large, "JPEG")
        assert post(large.getvalue()).status_code == 422
        assert not list(tmp_path.iterdir())
        response = post(image.getvalue()); assert response.status_code == 202
        job_id = response.json()["id"]
        for _ in range(100):
            status = client.get(f"/v1/jobs/{job_id}").json()
            if status["status"] in ("completed", "failed"): break
            time.sleep(0.01)
        assert status["status"] == "completed", status
        assert client.get(f"/v1/jobs/{job_id}/result").headers["content-type"] == "application/json"
        assert not (tmp_path / job_id / "frames").exists()
        client.delete(f"/v1/jobs/{job_id}/purge")
        assert not (tmp_path / job_id).exists()
    module.JOBS.clear()


def test_inference_errors_and_cancellation_never_produce_fake_results(tmp_path, monkeypatch):
    module = importlib.import_module("backend.app.main")
    class FailingEngine:
        def run(self, *args):
            raise RuntimeError("PRIVATE credential and path")
    monkeypatch.setattr(module, "HIGHLIGHTER", FailingEngine())
    source = tmp_path / "frames"; source.mkdir(); (source / "0000.jpg").write_bytes(b"private")
    job = module.Job("error", tmp_path)
    module.run_job(job, Highlights(**settings()), source)
    assert job.status == "failed" and "PRIVATE" not in job.error
    assert job.output is None and not source.exists()
    cancelled = module.Job("cancel", tmp_path, cancel=threading.Event()); cancelled.cancel.set()
    module.run_job(cancelled, Highlights(**settings()), None)
    assert cancelled.status == "cancelled" and cancelled.output is None
