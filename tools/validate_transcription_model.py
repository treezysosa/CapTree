"""Run actual faster-whisper weights on original synthetic test speech."""
import hashlib
import json
from pathlib import Path
import subprocess
import threading
import time
from types import SimpleNamespace
from backend.app.contracts import Transcription
from backend.app.transcription import TranscriptionEngine

output = Path("model-validation/transcription")
output.mkdir(parents=True, exist_ok=True)
audio = output / "input.wav"
subprocess.run(["espeak-ng", "-s", "145", "-w", str(audio), "Retro studio creates automatic subtitles. This is a real speech recognition test."], check=True)
job = SimpleNamespace(directory=output, cancel=threading.Event(), stage="", progress=None)
started = time.monotonic()
result, mime = TranscriptionEngine().run(Transcription(language="en"), audio, job)
text = result.read_text()
assert " --> " in text and len(text.strip()) > 35
report = {"model": "Systran/faster-whisper-tiny", "engine": "faster-whisper / CTranslate2 CPU int8", "mime": mime, "seconds": time.monotonic() - started, "result_sha256": hashlib.sha256(result.read_bytes()).hexdigest(), "validation": "actual pretrained speech inference on espeak-ng test speech", "transcript": text}
(output / "report.json").write_text(json.dumps(report, indent=2))
print(json.dumps(report))
