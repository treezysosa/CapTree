# Optional self-hosted inference server

Local photo/video/GIF editing needs no server. Generation uses real Diffusers pipelines on your own machine. Software has no app fees; GPU hardware, storage, electricity, or rented hosting may cost money.

Version 0.7 adds [CLIP visual highlights on CPU](HIGHLIGHTS.md), with a separate local-model installation and frame-upload consent. Version 0.6 optionally adds [GFPGAN face restoration on CPU](FACE_RESTORATION.md). Install its separate packages and verified weights to enable the `face-restoration` capability. It does not require Diffusers or CUDA. Existing generation/transcription endpoints and API v2 remain compatible.

## Install

Use Python 3.11 on Linux; an NVIDIA GPU with compatible drivers is recommended. Stable Diffusion 1.5 can run slowly on CPU or on a GPU with around 6 GB VRAM. CogVideoX-2b requires CUDA; budget roughly 16 GB GPU RAM and substantial host RAM when offloading. The 5b I2V model often needs 24+ GB GPU RAM and 32+ GB system RAM. Actual requirements depend on precision, model version, and drivers. Allow tens of GB of model disk space.

```sh
python3.11 -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
export RETRO_API_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
# Store this token securely; enter it in Android Settings.
uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

Expose the loopback server through a trusted HTTPS reverse proxy with a valid certificate (Caddy, nginx, or a private VPN's HTTPS service). Do not disable Android TLS validation. Set the HTTPS origin in the app, enter the same bearer token, save, test the connection, and enable **Allow remote AI processing**. Health checks authenticate but do not load multi-GB pipelines.

Default pipelines:

* `stable-diffusion-v1-5/stable-diffusion-v1-5`: text/image → image (CreativeML Open RAIL-M; review model card).
* `THUDM/CogVideoX-2b`: text → video (Apache 2.0 model release; verify chosen revision).
* `THUDM/CogVideoX-5b-I2V`: image → video (CogVideoX custom license; review conditions before use).

`RETRO_IMAGE_MODEL`, `RETRO_VIDEO_MODEL`, and `RETRO_I2V_MODEL` override the local directory or compatible Hugging Face model ID. They must match the pipeline class; arbitrary architectures are not interchangeable. Download and accept any model license/access requirements before starting mobile generation. Set `HF_HUB_OFFLINE=1` when running from predownloaded local paths. Models are loaded lazily and cached one at a time; switch between large models only when you have enough host memory. The default safety checker is preserved.

Preload a model by making a generation request on the server before mobile use; initial downloads can exceed the mobile eight-minute timeout. Backend v0.3 reports `api_version: 2` and a per-feature `features` report. `unsupported` identifies missing packages or CUDA hardware; `not_loaded` means prerequisites were detected but weights have not been loaded; `ready` means the pipeline is loaded. A loaded model may still fail from insufficient resources. Android v0.3 preflights the requested feature before uploading. Upgrade an older backend before using the new app.

## Job protocol

All routes require `Authorization: Bearer <token>`. `POST /v1/jobs` is multipart with a `payload` JSON string and optional `image` file. Payload fields are `tool` (`photo`, `video`, `aigif`), `prompt`, `negative`, `style`, `aspect`, `seed` (-1=random), `duration` (2/3/6), and `motion` (0–1). The response is `{ "id": "uuid" }`.

Poll `GET /v1/jobs/{id}`; `progress` is a measured diffusion-step percentage or null during loading/encoding. Download `GET /v1/jobs/{id}/result` after `completed`. `DELETE /v1/jobs/{id}` requests cancellation; `DELETE /v1/jobs/{id}/purge` also removes private files. One worker serializes model inference and the queue holds at most three active jobs. Use one Uvicorn worker; jobs are in memory, and a server restart invalidates them and clears old job folders. Outputs expire after 24 hours.

```sh
curl -H "Authorization: Bearer $RETRO_API_TOKEN" \
  -F 'payload={"tool":"photo","prompt":"an original purple retro handheld on a desk","style":"Pixel Art","seed":42}' \
  https://YOUR-HOST/v1/jobs
```

No production route returns placeholder media. Missing packages/models, unsupported GPU or insufficient VRAM produce an explicit failed job. Diffusion progress may be indeterminate during model loading; cancellation is cooperative between inference steps.

## Optional transcription

```sh
pip install -r backend/requirements-speech.txt
python backend/transcribe.py movie.mp4 --model small > captions.srt
```

Use AI Clipper → Import SRT to load the generated file. `--format legacy` outputs the original `milliseconds|caption` events for pasting. Times are relative to exported time zero; adjust them when trimming or reordering source clips.

## Automatic subtitles from Android

Set `RETRO_WHISPER_MODEL=tiny` (default, fast CPU) or a compatible faster-whisper model/local path. `pip install -r backend/requirements.txt` includes the speech dependencies; for a CPU speech-only server, install `backend/requirements-test.txt` plus `backend/requirements-speech.txt`. No CUDA GPU is needed for speech. The first job downloads model weights unless a local path/cache is used.

In AI Clipper, select and trim a block, choose the speech language, and press **AI Auto Subtitles · Server**. Confirm **Send Audio**. The app decodes the selected audio range locally, uploads only its PCM WAV to `POST /v1/transcriptions`, and polls the authenticated job API. The result is a real SRT file. Open the completed save and choose **Reopen Timeline With Subtitles** to replace captions in the selected output range while preserving captions elsewhere. It uses the saved timeline snapshot; review timing after later edits.

The multipart transcription endpoint accepts `payload={"tool":"transcribe","language":"auto"}` and an `audio` WAV field. Uploads are limited to 16-bit PCM, 1–8 channels, 8–192 kHz, ten minutes, and 64 MB. Audio files are deleted after inference and on purge. Cancellation is cooperative between speech segments; model loading and individual inference calls may take time. No-speech results produce a clear failed-job message. Default tiny-model captions need manual correction.
