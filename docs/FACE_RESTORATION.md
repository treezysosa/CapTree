# Optional CPU face restoration

Photo Auto Enhance and Photo Upscaler can send their locally processed image to a user-owned GFPGAN server. This restores one central detected face and blends it back at the original processed dimensions. It does not run on the phone, needs no paid API, and is off by default. Ordinary editing remains offline. The upscaler still needs its separate on-device Real-ESRGAN model.

## Server setup

Use Python 3.11 in a fresh environment. Plan for several GB of RAM; 8 GB or more is a reasonable starting point for a dedicated face server. Large photos and concurrently loaded generation models need more. CPU speed, storage, electricity and hosting costs remain yours.

From the repository root:

```sh
python -m venv .venv-face
. .venv-face/bin/activate
python -m pip install --upgrade pip
python -m pip install torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cpu
python -m pip install -r backend/requirements-face.txt
python tools/download_face_models.py --directory face-models
export RETRO_FACE_MODEL_DIR=/absolute/path/to/CapTree/face-models
```

Set `RETRO_API_TOKEN` to your own random token of at least 24 characters, then run the existing server:

```sh
python -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

Expose it using the authenticated HTTPS setup in [BACKEND.md](BACKEND.md). Android requires a valid trusted certificate; no cleartext or TLS-verification bypass is included. The face requirements are optional and compatible with the backend's Torch 2.6 stack. BasicSR is pinned to an upstream commit that uses the supported torchvision import. Face-only servers do not need Diffusers/CUDA. Install the other optional requirements if the same host will also provide generation or transcription.

The explicit download command fetches roughly 500 MB of model weights and verifies their SHA-256 values. The server requires all three files before advertising support and does not initiate model downloads. Weights and credentials are never bundled in the APK or project backups. GFPGAN's full [upstream license and third-party terms](../backend/licenses/GFPGAN-LICENSE.txt) are included; review them for your use and redistribution.

## Android workflow

1. Configure the server URL/token in Settings, enable remote processing, and test the backend. `face-restoration` is `unsupported` until packages and files exist, `not_loaded` until loaded, and `ready` after loading. Readiness does not guarantee successful inference on every photo.
2. Import a photo in Auto Enhance or Photo Upscaler and enable **AI face restoration · server**. Adjust strength from 0.1 to 1. Local enhancement/upscaling happens first.
3. Press START PROCESSING or the virtual A button. Both require the **SEND PROCESSED PHOTO?** confirmation. Only the processed PNG is uploaded, with restoration strength; local project paths, captions, credentials and other recipe fields are excluded. Retry asks again.
4. The server detects the central face, aligns a 512×512 crop, runs real GFPGAN v1.4 clean inference, parses/blends the restored region, and returns a PNG. Strength mixes the restored result with the processed input. Final Android export respects JPEG/PNG quality settings.

Input uploads are limited to 12 MB, 4096 pixels per edge and 20 million pixels. Complex 4× images may exceed the PNG upload limit; use 2×, a smaller source, or turn restoration off. Restoration operates in RGB, so it removes transparency. Faces that are tiny, obscured or unsuitable can fail detection. No-face cases and inference failures report errors; they do not return the input as a claimed restoration. Compare the result before saving, since generative restoration may change details.

Loading, detection and model inference are indeterminate stages; no invented completion percentages are shown. Cancellation is checked around model calls and blending, but cannot interrupt an individual Torch operation. Preload models before mobile use to stay within the app's eight-minute remote timeout and WorkManager's ten-minute job window.

## Verified model provenance

The dedicated [CPU validation run](https://github.com/treezysosa/CapTree/actions/runs/40548268355) used a public-domain NASA image from scikit-image. It executed actual detection/restoration, the authenticated API, no-face rejection, inference-error propagation and purge. Artifacts contain input/output PNGs, timings and a provenance report. It is a compatibility check, not a guarantee of perceptual quality or facial identity preservation.

| File | SHA-256 |
|---|---|
| GFPGANv1.4.pth | `e2cd4703ab14f4d01fd1383a8a8b266f9a5833dacee8e6a79d3bf21a1b6be5ad` |
| detection_Resnet50_Final.pth | `6d1de9c2944f2ccddca5f5e010ea5ae64a39845a86311af6fdf30841b0a5a16d` |
| parsing_parsenet.pth | `3d558d8d0e42c20224f13cf5a29c79eba2d59913419f945545d8cf7b72920de2` |
