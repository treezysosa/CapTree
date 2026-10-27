"""Genuine pretrained-model smoke checks. Never substitute fabricated model outputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import time


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def realesrgan(args):
    import numpy as np
    import onnxruntime as ort
    from PIL import Image
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    y, x = np.mgrid[0:16, 0:16]
    rgb = np.stack((x * 17, y * 17, ((x + y) % 2) * 220 + 20), axis=-1).astype(np.uint8)
    Image.fromarray(rgb).save(output / "input.png")
    # Match the Android engine's 64-pixel core and 16-pixel replicated halo.
    positions = np.clip(np.arange(96) - 16, 0, 15)
    padded = rgb[positions[:, None], positions[None, :]].astype(np.float32) / 255
    tensor = padded.transpose(2, 0, 1)[None].copy()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(args.model, sess_options=options, providers=["CPUExecutionProvider"])
    started = time.monotonic()
    result = session.run(None, {session.get_inputs()[0].name: tensor})[0]
    assert result.shape == (1, 3, 384, 384)
    assert np.isfinite(result).all()
    cropped = result[0, :, 64:128, 64:128].transpose(1, 2, 0)
    golden = np.clip(cropped * 255, 0, 255).astype(np.uint8)
    assert golden.std() > 5
    Image.fromarray(golden).save(output / "expected-x4.png")
    report = {"model": "RealESRGAN_x4plus", "weights_source": "https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.1.0", "weights_sha256": digest(args.weights), "onnx_sha256": digest(args.model), "input_shape": list(tensor.shape), "output_shape": list(result.shape), "seconds": time.monotonic() - started, "provider": "CPUExecutionProvider", "validation": "real pretrained RRDBNet; Android golden tile"}
    (output / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


def image(args):
    import torch
    from types import SimpleNamespace
    import threading
    from backend.app.contracts import Generation
    from backend.app.inference import InferenceEngine
    from PIL import ImageStat
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    job = SimpleNamespace(directory=output, cancel=threading.Event(), stage="", progress=None, seed=None)
    started = time.monotonic()
    engine = InferenceEngine()
    result, mime = engine.run(Generation(tool="photo", prompt="an original purple retro handheld console on a wooden desk, product photography", seed=42), None, job)
    from PIL import Image
    with Image.open(result) as rendered:
        assert rendered.size == (512, 512)
        assert max(ImageStat.Stat(rendered.convert("RGB")).stddev) > 1
    report = {"model": os.environ.get("RETRO_IMAGE_MODEL", "stable-diffusion-v1-5/stable-diffusion-v1-5"), "seed": job.seed, "mime": mime, "sha256": digest(result), "seconds": time.monotonic() - started, "inference_steps": 24, "device": "cuda" if torch.cuda.is_available() else "cpu", "validation": "actual production Diffusers inference; no test engine"}
    (output / "image-report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=["realesrgan", "image"])
    parser.add_argument("--model")
    parser.add_argument("--weights")
    parser.add_argument("--output", required=True)
    arguments = parser.parse_args()
    (realesrgan if arguments.kind == "realesrgan" else image)(arguments)
