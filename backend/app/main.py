import asyncio
import hmac
import json
import os
import shutil
import threading
import time
import uuid
import wave
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from PIL import Image, UnidentifiedImageError
from pydantic import ValidationError

from .contracts import Generation, Transcription
from .inference import GenerationCancelled, InferenceEngine
from .transcription import TranscriptionEngine
from .capabilities import features, operation_feature

ROOT = Path(os.environ.get("RETRO_OUTPUT_DIR", "backend/outputs")).resolve()
JOBS = {}
LOCK = threading.RLock()
POOL = ThreadPoolExecutor(max_workers=1, thread_name_prefix="retro-inference")
ENGINE = InferenceEngine()
TRANSCRIBER = TranscriptionEngine()
MAX_UPLOAD = 12 * 1024 * 1024
MAX_AUDIO = 64 * 1024 * 1024
Image.MAX_IMAGE_PIXELS = 20_000_000


@dataclass
class Job:
    id: str
    directory: Path
    status: str = "queued"
    stage: str = "Waiting for GPU"
    progress: int | None = None
    error: str = ""
    output: Path | None = None
    mime: str = ""
    seed: int | None = None
    created: float = field(default_factory=time.time)
    cancel: threading.Event = field(default_factory=threading.Event)
    purge: bool = False


def auth(authorization: str = Header(default="")):
    token = os.environ.get("RETRO_API_TOKEN", "")
    if len(token) < 24:
        raise HTTPException(503, "Configure RETRO_API_TOKEN with at least 24 characters.")
    if not hmac.compare_digest(authorization, f"Bearer {token}"):
        raise HTTPException(401, "Invalid bearer token.")


def run_job(job, request, image):
    try:
        if job.cancel.is_set():
            raise GenerationCancelled()
        job.status = "processing"
        job.output, job.mime = (TRANSCRIBER if isinstance(request, Transcription) else ENGINE).run(request, image, job)
        if job.cancel.is_set():
            raise GenerationCancelled()
        job.status, job.stage, job.progress = "completed", "Ready", 100
    except GenerationCancelled:
        job.status, job.stage = "cancelled", "Cancelled"
    except Exception as error:
        # Avoid leaking filesystem paths, request data or server credentials.
        job.status, job.error = "failed", f"{type(error).__name__}: model inference failed. Check model installation, license access, and server RAM/VRAM."
    finally:
        if image:
            image.unlink(missing_ok=True)
        if job.status != "completed" and job.output:
            job.output.unlink(missing_ok=True)
        if job.purge:
            shutil.rmtree(job.directory, ignore_errors=True)
            with LOCK:
                JOBS.pop(job.id, None)


async def janitor():
    while True:
        await asyncio.sleep(300)
        with LOCK:
            expired = [j for j in JOBS.values() if j.status not in ("queued", "processing") and time.time() - j.created > 86400]
            for job in expired:
                JOBS.pop(job.id, None)
                shutil.rmtree(job.directory, ignore_errors=True)


@asynccontextmanager
async def lifespan(app):
    ROOT.mkdir(parents=True, exist_ok=True)
    # No running jobs survive a server restart. Remove only UUID-named job folders.
    for directory in ROOT.iterdir():
        if directory.is_dir():
            try:
                uuid.UUID(directory.name)
                shutil.rmtree(directory)
            except ValueError:
                pass
    task = asyncio.create_task(janitor())
    yield
    task.cancel()
    with LOCK:
        for job in JOBS.values():
            job.cancel.set()


app = FastAPI(title="RETRO AI STUDIO · self-hosted inference", version="0.3.0", lifespan=lifespan)


@app.get("/v1/health", dependencies=[Depends(auth)])
def health():
    status = features(getattr(ENGINE, "kind", None), getattr(TRANSCRIBER, "model", None) is not None)
    return {"status": "online", "api_version": 2, "capabilities": [item["id"] for item in status if item["supported"]], "features": status, "progress": "actual model steps or transcribed audio time; indeterminate during loading/encoding"}


@app.post("/v1/jobs", status_code=202, dependencies=[Depends(auth)])
async def create(payload: str = Form(...), image: UploadFile | None = File(default=None)):
    try:
        if len(payload) > 16000:
            raise ValueError()
        request = Generation.model_validate_json(payload)
    except (ValidationError, ValueError):
        raise HTTPException(422, "Invalid generation payload.")
    # Check hardware before accepting a media upload into a generation job.
    capability = next(item for item in features(getattr(ENGINE, "kind", None)) if item["id"] == operation_feature(request, image is not None))
    if not capability["supported"]:
        raise HTTPException(503, capability["reason"])
    with LOCK:
        if sum(j.status in ("queued", "processing") for j in JOBS.values()) >= 3 or len(JOBS) >= 100:
            raise HTTPException(429, "Server queue is full. Purge old jobs or wait for processing.")
        job_id = str(uuid.uuid4())
        directory = ROOT / job_id
        directory.mkdir(parents=True)
        job = Job(job_id, directory)
        JOBS[job_id] = job
    input_path = None
    try:
        if image:
            raw = directory / "upload.bin"
            count = 0
            with raw.open("wb") as sink:
                while chunk := await image.read(65536):
                    count += len(chunk)
                    if count > MAX_UPLOAD:
                        raise HTTPException(413, "Input image exceeds 12 MB.")
                    sink.write(chunk)
            try:
                with Image.open(raw) as decoded:
                    if decoded.width * decoded.height > 20_000_000:
                        raise ValueError()
                    input_path = directory / "input.png"
                    decoded.convert("RGB").save(input_path)
            except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
                raise HTTPException(422, "Unsupported or damaged input image.")
            raw.unlink(missing_ok=True)
        POOL.submit(run_job, job, request, input_path)
        return {"id": job_id}
    except BaseException:
        with LOCK:
            JOBS.pop(job_id, None)
        shutil.rmtree(directory, ignore_errors=True)
        raise
    finally:
        if image:
            await image.close()


@app.post("/v1/transcriptions", status_code=202, dependencies=[Depends(auth)])
async def transcribe(payload: str = Form(default='{"tool":"transcribe"}'), audio: UploadFile = File(...)):
    try:
        if len(payload) > 4096:
            raise ValueError()
        request = Transcription.model_validate_json(payload)
    except (ValueError, ValidationError):
        raise HTTPException(422, "Invalid transcription settings.")
    capability = next(item for item in features() if item["id"] == "transcription")
    if not capability["supported"]:
        raise HTTPException(503, capability["reason"])
    with LOCK:
        if sum(j.status in ("queued", "processing") for j in JOBS.values()) >= 3 or len(JOBS) >= 100:
            raise HTTPException(429, "Server queue is full.")
        job_id = str(uuid.uuid4())
        directory = ROOT / job_id
        directory.mkdir(parents=True)
        job = Job(job_id, directory)
        JOBS[job_id] = job
    path = directory / "audio.wav"
    try:
        count = 0
        with path.open("wb") as sink:
            while chunk := await audio.read(65536):
                count += len(chunk)
                if count > MAX_AUDIO:
                    raise HTTPException(413, "Selected audio exceeds 64 MB.")
                sink.write(chunk)
        try:
            with wave.open(str(path), "rb") as decoded:
                duration = decoded.getnframes() / max(decoded.getframerate(), 1)
                if decoded.getsampwidth() != 2 or not 1 <= decoded.getnchannels() <= 8 or not 8000 <= decoded.getframerate() <= 192000 or not 0 < duration <= 600:
                    raise ValueError()
                expected = decoded.getnframes() * decoded.getnchannels() * 2
                if len(decoded.readframes(decoded.getnframes())) != expected:
                    raise ValueError()
        except (wave.Error, EOFError, ValueError):
            raise HTTPException(422, "Upload valid 16-bit PCM WAV audio up to ten minutes.")
        POOL.submit(run_job, job, request, path)
        return {"id": job_id}
    except BaseException:
        with LOCK:
            JOBS.pop(job_id, None)
        shutil.rmtree(directory, ignore_errors=True)
        raise
    finally:
        await audio.close()


def get_job(job_id):
    with LOCK:
        job = JOBS.get(job_id)
    if job is None:
        raise HTTPException(404, "Job not found.")
    return job


@app.get("/v1/jobs/{job_id}", dependencies=[Depends(auth)])
def status(job_id: str):
    job = get_job(job_id)
    return {"id": job.id, "status": job.status, "stage": job.stage, "progress": job.progress, "error": job.error, "seed": job.seed}


@app.get("/v1/jobs/{job_id}/result", dependencies=[Depends(auth)])
def result(job_id: str):
    job = get_job(job_id)
    if job.status != "completed" or not job.output or not job.output.is_file():
        raise HTTPException(409, "Output is not ready.")
    return FileResponse(job.output, media_type=job.mime, filename=job.output.name)


@app.delete("/v1/jobs/{job_id}", dependencies=[Depends(auth)])
def cancel(job_id: str):
    job = get_job(job_id)
    job.cancel.set()
    if job.status == "queued":
        job.status = "cancelled"
    return {"status": "cancellation requested"}


@app.delete("/v1/jobs/{job_id}/purge", dependencies=[Depends(auth)])
def purge(job_id: str):
    job = get_job(job_id)
    job.cancel.set()
    job.purge = True
    if job.status not in ("queued", "processing"):
        shutil.rmtree(job.directory, ignore_errors=True)
        with LOCK:
            JOBS.pop(job_id, None)
    return {"status": "purging"}
