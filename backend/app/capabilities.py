"""Report actual package/GPU prerequisites without pretending an unloaded model is ready."""
import importlib.util
import os


def features(loaded_kind=None, transcription_loaded=False, installed=None, cuda=None):
    packages = installed if installed is not None else {name: importlib.util.find_spec(name) is not None for name in ("torch", "diffusers", "transformers", "faster_whisper")}
    if cuda is None:
        try:
            import torch
            cuda = torch.cuda.is_available()
        except (ImportError, OSError, RuntimeError):
            cuda = False
    diffusion = all(packages.get(name, False) for name in ("torch", "diffusers", "transformers"))
    descriptions = [
        ("text-to-image", diffusion, "image", os.environ.get("RETRO_IMAGE_MODEL", "stable-diffusion-v1-5/stable-diffusion-v1-5"), False),
        ("image-to-image", diffusion, "image-image", os.environ.get("RETRO_IMAGE_MODEL", "stable-diffusion-v1-5/stable-diffusion-v1-5"), False),
        ("text-to-video", diffusion, "video", os.environ.get("RETRO_VIDEO_MODEL", "THUDM/CogVideoX-2b"), True),
        ("image-to-video", diffusion, "video-image", os.environ.get("RETRO_I2V_MODEL", "THUDM/CogVideoX-5b-I2V"), True),
        ("text-to-gif", diffusion, "video", os.environ.get("RETRO_VIDEO_MODEL", "THUDM/CogVideoX-2b"), True),
        ("transcription", packages.get("faster_whisper", False), "transcription", os.environ.get("RETRO_WHISPER_MODEL", "tiny"), False),
    ]
    result = []
    for feature, dependencies, kind, model, gpu_required in descriptions:
        reason = "Required model packages are not installed." if not dependencies else "This video pipeline requires a CUDA GPU." if gpu_required and not cuda else ""
        loaded = transcription_loaded if kind == "transcription" else loaded_kind == kind
        state = "unsupported" if reason else "ready" if loaded else "not_loaded"
        result.append({"id": feature, "supported": not reason, "state": state, "reason": reason or ("Model loaded; generation can still fail if resources are insufficient." if loaded else "Prerequisites detected; model weights have not been loaded or verified yet."), "model": model, "requires_cuda": gpu_required})
    return result


def operation_feature(request, has_image=False):
    return "transcription" if request.tool == "transcribe" else "image-to-image" if request.tool == "photo" and has_image else "text-to-image" if request.tool == "photo" else "text-to-gif" if request.tool == "aigif" else "image-to-video" if has_image else "text-to-video"
