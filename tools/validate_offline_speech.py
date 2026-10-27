"""Execute the official small English Vosk checkpoint on original test speech."""
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import wave
import zipfile

MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
MODEL_FOLDER = "vosk-model-small-en-us-0.15"
MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"


def prepare(output):
    output.mkdir(parents=True, exist_ok=True)
    archive = output / "speech-model.zip"
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(MODEL_URL, timeout=120) as source, archive.open("wb") as sink:
        while chunk := source.read(1024 * 1024):
            size += len(chunk)
            if size > 64 * 1024 * 1024:
                raise ValueError("Unexpected model archive size.")
            digest.update(chunk)
            sink.write(chunk)
    assert digest.hexdigest() == MODEL_SHA256, "Official archive checksum changed; review it before use."
    with zipfile.ZipFile(archive) as zipped:
        names = zipped.namelist()
        assert len(names) < 100
        assert all(not name.startswith("/") and ".." not in name.split("/") and name.startswith(MODEL_FOLDER + "/") for name in names)
        zipped.extractall(output)
    subprocess.run(["espeak-ng", "-v", "en-us", "-s", "135", "-w", str(output / "raw.wav"), "one two three four five. this is a speech recognition test."], check=True)
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(output / "raw.wav"), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(output / "speech.wav")], check=True)
    # Match AudioEngine's internal 44-byte PCM header, without ffmpeg's LIST metadata.
    with wave.open(str(output / "speech.wav"), "rb") as decoded:
        pcm = decoded.readframes(decoded.getnframes())
    with wave.open(str(output / "speech.wav"), "wb") as canonical:
        canonical.setnchannels(1)
        canonical.setsampwidth(2)
        canonical.setframerate(16000)
        canonical.writeframes(pcm)
    assert (output / "speech.wav").read_bytes()[36:40] == b"data"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "color=c=0x24253c:s=320x240:r=15", "-i", str(output / "speech.wav"), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(output / "speech.mp4")], check=True)
    return archive, digest.hexdigest(), names


if __name__ == "__main__":
    from vosk import Model, KaldiRecognizer
    output = Path("model-validation/offline-speech")
    archive, digest, names = prepare(output)
    started = time.monotonic()
    model = Model(str(output / MODEL_FOLDER))
    parts = []
    with wave.open(str(output / "speech.wav"), "rb") as audio:
        assert audio.getframerate() == 16000 and audio.getnchannels() == 1 and audio.getsampwidth() == 2
        duration = audio.getnframes() / 16000
        recognizer = KaldiRecognizer(model, 16000)
        recognizer.SetWords(True)
        while chunk := audio.readframes(4000):
            if recognizer.AcceptWaveform(chunk):
                parts.append(json.loads(recognizer.Result()))
        parts.append(json.loads(recognizer.FinalResult()))
    text = " ".join(part.get("text", "") for part in parts)
    words = [word for part in parts for word in part.get("result", [])]
    assert len(set(text.split()) & {"one", "two", "three", "four", "five"}) >= 3, text
    assert words and all(0 <= word["start"] < word["end"] <= duration + 0.1 for word in words), words
    report = {"model": MODEL_FOLDER, "model_url": MODEL_URL, "license": "Apache-2.0 (official model listing)", "archive_bytes": archive.stat().st_size, "archive_sha256": digest, "entries": names, "audio_seconds": duration, "inference_seconds": time.monotonic() - started, "transcript": text, "words": words, "source": "Original espeak-ng synthetic English test speech", "engine": "Vosk 0.3.45 CPU"}
    (output / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report))
