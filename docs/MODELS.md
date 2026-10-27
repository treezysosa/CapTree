# Real-ESRGAN on-device setup

The app executes ONNX Runtime CPU inference; it does not relabel interpolation as AI. Weights are not bundled or automatically downloaded. Only install trusted ONNX files obtained under a license you may use. Model import can allocate native memory, so use vetted model sources.

Expected model: Real-ESRGAN x4plus, float32 RGB NCHW input `[1,3,H,W]` normalized to `[0,1]`, float32 RGB output `[1,3,4H,4W]`. Spatial axes must be dynamic or fixed at 96×96 input / 384×384 output. No uint8, NHWC, x2-only, anime compact, or externally stored tensor files. Use opset 17. See the upstream [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) project (BSD 3-Clause) and its model release terms.

## Reproducible export on a PC

Use a separate Python 3.10 environment for the original BasicSR exporter; older BasicSR uses a torchvision API removed in newer versions.

```sh
python3.10 -m venv .model-export
. .model-export/bin/activate
pip install 'numpy<2' torch==2.1.2 torchvision==0.16.2 basicsr==1.4.2 onnx==1.16.2 onnxruntime==1.19.2
# Download RealESRGAN_x4plus.pth from the official upstream release:
# https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.1.0
python tools/export_realesrgan.py RealESRGAN_x4plus.pth realesrgan-x4.onnx
```

Copy the resulting ONNX file to the phone; Settings → Import ONNX Model. The app executes a real 96×96 inference probe before installing the model, validates float32 input/output tensor contracts and finite output pixels, and stores a SHA-256 diagnostic report. Settings → Run On-device Model Test repeats the probe on the current phone. The probe checks compatibility, not the authenticity of user-supplied weights. The x4plus model can be around 64 MB. Model import is capped at 160 MB. Keep the upstream attribution and model license with any redistributed weights. The repository does not grant rights to third-party models.

The app samples input images to at most 1024 pixels, uses 64×64 core tiles with a 16-pixel context halo, and limits output allocation based on current system and Java heap memory. Low-memory phones should use 256–512 pixel input. Inference is CPU-based and may be slow; cancellation is checked between tiles. 2× output is downsampled from genuine x4 inference. The context halo can leave minor seams on some images; larger models/tiling policies need device validation. Optional [GFPGAN face restoration](FACE_RESTORATION.md) runs on a separately configured CPU server after local processing and requires explicit upload consent.

## Verified upstream checkpoint

The official `RealESRGAN_x4plus.pth` tested in CI has SHA-256 `4fa0d38905f75ac06eb49a7951b426670021be3018265fd191d2125df9d682f1`. The verified exported ONNX artifact has SHA-256 `4c22717b3165830b43ad1e261cb4da10f397f50f58c21397539781a91ff22392`; exports made with different tool versions may differ. The dedicated model workflow downloads official weights, checks PyTorch/ONNX agreement, and compares Android x4 output against an independent CPU reference with at most three levels of per-channel pixel error. Model artifacts include the upstream BSD license and provenance report. These test weights are separate from the application APK.
