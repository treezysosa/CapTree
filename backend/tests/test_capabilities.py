from backend.app.capabilities import features


def test_cpu_server_does_not_advertise_video_as_supported():
    report = {entry["id"]: entry for entry in features(installed={name: True for name in ("torch", "diffusers", "transformers", "faster_whisper")}, cuda=False)}
    assert report["text-to-image"]["supported"]
    assert report["transcription"]["supported"]
    assert not report["text-to-video"]["supported"]
    assert report["text-to-video"]["state"] == "unsupported"
    assert report["text-to-image"]["state"] == "not_loaded"


def test_loaded_models_and_missing_dependencies_are_distinct():
    installed = {name: True for name in ("torch", "diffusers", "transformers", "faster_whisper")}
    report = features(loaded_kind="image", installed=installed, cuda=False)
    assert next(item for item in report if item["id"] == "text-to-image")["state"] == "ready"
    assert all(not item["supported"] for item in features(installed={}, cuda=False))
