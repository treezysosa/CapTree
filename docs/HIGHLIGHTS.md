# Optional CLIP visual highlights on CPU

Use Python 3.11 on a user-owned Linux PC. No CUDA or paid API is required. Budget about 2–4 GB free RAM, 1 GB model disk space plus the Python/Torch installation, and CPU time/electricity. Other simultaneously cached models need additional memory. The app never bundles or automatically downloads CLIP weights.

CLIP ViT-B/32 is MIT-licensed; read the [model card](https://huggingface.co/openai/clip-vit-base-patch32) and [license](../backend/licenses/CLIP-LICENSE.txt) before use. This implementation supports the pinned OpenAI checkpoint, not arbitrary vision models.

From the repository root:

```sh
python3.11 -m venv .venv-highlights
. .venv-highlights/bin/activate
pip install torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu
pip install -r backend/requirements-highlights.txt
PYTHONPATH=. python tools/download_clip_model.py --output clip-model
export RETRO_CLIP_MODEL_DIR="$PWD/clip-model"
export HF_HUB_OFFLINE=1
export HF_HUB_DISABLE_TELEMETRY=1
export RETRO_API_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

Keep that token secure and enter the same token in Android Settings. Use one Uvicorn worker. Expose loopback through a trusted HTTPS reverse proxy with a valid certificate as described in [backend setup](BACKEND.md). Android rejects cleartext, invalid TLS certificates and redirects.

The explicit download helper pins `openai/clip-vit-base-patch32` revision `3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268` and verifies `pytorch_model.bin` SHA-256:

```text
a63082132ba4f97a80bea76823f544493bffa8082296d62d71581a4feff1576f
```

The request-serving engine uses `local_files_only=True`; it does not fetch missing weights. Health checks detect packages/config/weight files, but cannot certify inference until loading succeeds. Use **Test Connection** and inspect `semantic-highlights`: `unsupported` means packages/local files missing, `not_loaded` means unverified local prerequisites detected, `ready` means the CPU model loaded. Resource failures still produce failed jobs.

## Protocol

Backend v0.7 retains API v2. `POST /v1/highlights` requires bearer authentication, multipart `payload` JSON and one `frames` file per timestamp, in the same order:

```json
{"tool":"highlights","prompt":"an astronaut in a space suit","duration_ms":8000,"clip_ms":2000,"timestamps_ms":[1000,3000,5000,7000]}
```

Times are relative to the selected trim; duration is 1000–120000 ms; clip length is 1000–15000 ms and cannot exceed duration. Provide 1–24 ordered unique sample times in `[0,duration_ms)`. Each frame must be a JPEG, maximum 256 pixels per edge and 256 KB. Server filenames are generated; client paths and EXIF are discarded. Failed uploads leave no job data.

Use the existing authenticated job polling/cancel/result/purge routes. Results have `application/json`, schema version 1, selected duration, sample count, model name, `metric: cosine_similarity`, up to five `ranges` with relative `start_ms/end_ms/score`, and measured sample scores. Android accepts at most 64 KB and validates bounds/finite scores before application. Input frames are deleted when inference ends; the app purges the server job after retrieval, or requests cancel/purge on failure. Existing queue/timeouts/retention apply. Cancellation is checked between model calls; one call cannot be interrupted mid-operation.

See [phase workflow and limits](PHASE_7.md). CLIP ranks visual similarity in sparse stills; review candidate cuts. It does not recognize dialogue, infer all temporal events or certify semantic correctness.

## Repeat actual model validation

```sh
pip install scikit-image==0.25.2
PYTHONPATH=. HF_HUB_OFFLINE=0 python tools/validate_highlight_model.py
```

This explicit validation command downloads the pinned pretrained checkpoint into the Hugging Face cache and executes CPU inference plus the authenticated API using a public-domain NASA photograph and original gray controls. Outputs/hashes are written under `model-validation/highlights`. [Verified CI run](https://github.com/treezysosa/CapTree/actions/runs/40578954665) · [result artifact](https://github.com/treezysosa/CapTree/actions/runs/40578954665/artifacts/12768443126). Test fixtures/weights are excluded from the production APK.
