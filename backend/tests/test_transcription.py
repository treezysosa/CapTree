import importlib
import io
import time
import wave
import pytest
from fastapi.testclient import TestClient
from backend.app.capabilities import features


def wav():
    out = io.BytesIO()
    with wave.open(out, "wb") as audio:
        audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000)
        audio.writeframes(b"\0\0" * 16000)
    return out.getvalue()


def test_audio_upload_job_result_and_cleanup(tmp_path, monkeypatch):
    module = importlib.import_module("backend.app.main")
    monkeypatch.setenv("RETRO_API_TOKEN", "test-only-transcription-abcdefghijklmnopqrstuvwxyz")
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module, "features", lambda *args, **kwargs: features(installed={"faster_whisper": True}, cuda=False))
    class ProtocolTestTranscriber:
        def run(self, request, audio, job):
            path = job.directory / "result.srt"
            path.write_text("1\n00:00:00,000 --> 00:00:00,900\nRetro studio\n")
            return path, "application/x-subrip"
    monkeypatch.setattr(module, "TRANSCRIBER", ProtocolTestTranscriber())
    module.JOBS.clear()
    with TestClient(module.app) as client:
        client.headers["Authorization"] = "Bearer test-only-transcription-abcdefghijklmnopqrstuvwxyz"
        assert client.post("/v1/transcriptions", files={"audio": ("bad.wav", b"bad")}).status_code == 422
        response = client.post("/v1/transcriptions", data={"payload": '{"language":"en"}'}, files={"audio": ("selected.wav", wav(), "audio/wav")})
        assert response.status_code == 202
        job_id = response.json()["id"]
        for _ in range(100):
            status = client.get(f"/v1/jobs/{job_id}").json()
            if status["status"] == "completed": break
            time.sleep(0.01)
        assert status["status"] == "completed"
        assert "Retro studio" in client.get(f"/v1/jobs/{job_id}/result").text
        assert not (tmp_path / job_id / "audio.wav").exists()
        client.delete(f"/v1/jobs/{job_id}/purge")
        assert not (tmp_path / job_id).exists()
    module.JOBS.clear()
