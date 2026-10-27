"""Real Diffusers inference, imported lazily so API validation needs no GPU packages."""
import os
import secrets
import subprocess
from pathlib import Path

from .contracts import Generation


class GenerationCancelled(Exception):
    pass


STYLES = {
    "Photorealistic": "photorealistic, natural lighting, fine detail",
    "Anime": "anime illustration, cel shading",
    "Pixel Art": "pixel art, limited color palette, crisp pixel edges",
    "Cartoon": "cartoon, expressive shapes",
    "Fantasy": "fantasy concept art, magical atmosphere",
    "Cyberpunk": "cyberpunk, neon city, futuristic",
    "Retro Game Art": "original 16-bit game artwork, pixel art",
    "Oil Painting": "oil painting, visible brush strokes",
    "Comic Book": "comic book panel, ink outlines, halftone",
    "Cinematic": "cinematic lighting, film composition",
    "Retro VHS": "analog VHS recording, chromatic noise, retro colors",
    "Horror": "horror atmosphere, dramatic shadows",
    "Realistic": "realistic, natural light",
    "Sci-Fi": "science fiction, futuristic environments",
    "1990s Camcorder": "1990s home camcorder footage, analog video",
}


class InferenceEngine:
    def __init__(self):
        self.pipe = None
        self.kind = None

    def _pipeline(self, kind, job):
        import torch
        from diffusers import (
            StableDiffusionPipeline, StableDiffusionImg2ImgPipeline,
            CogVideoXPipeline, CogVideoXImageToVideoPipeline,
        )
        if self.kind == kind:
            return self.pipe
        self.pipe = None
        import gc
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        job.stage = "Loading model weights (first use can take several minutes)"
        job.progress = None
        if kind.startswith("video"):
            if not torch.cuda.is_available():
                raise RuntimeError("CogVideoX requires a CUDA GPU on this server.")
            image = kind == "video-image"
            model = os.environ.get("RETRO_I2V_MODEL" if image else "RETRO_VIDEO_MODEL", "THUDM/CogVideoX-5b-I2V" if image else "THUDM/CogVideoX-2b")
            cls = CogVideoXImageToVideoPipeline if image else CogVideoXPipeline
            pipe = cls.from_pretrained(model, torch_dtype=torch.bfloat16 if image else torch.float16)
            pipe.enable_model_cpu_offload()
            pipe.vae.enable_tiling()
            pipe.vae.enable_slicing()
        else:
            model = os.environ.get("RETRO_IMAGE_MODEL", "stable-diffusion-v1-5/stable-diffusion-v1-5")
            cls = StableDiffusionImg2ImgPipeline if kind == "image-image" else StableDiffusionPipeline
            pipe = cls.from_pretrained(model, torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32)
            pipe.to("cuda" if torch.cuda.is_available() else "cpu")
            pipe.enable_attention_slicing()
            # Preserve the model's safety checker when supplied by its repository.
        self.pipe, self.kind = pipe, kind
        return pipe

    def run(self, request: Generation, image: Path | None, job):
        import torch
        from PIL import Image
        from diffusers.utils import export_to_video, load_image
        if job.cancel.is_set():
            raise GenerationCancelled()
        video = request.tool in ("video", "aigif")
        kind = ("video" if video else "image") + ("-image" if image else "")
        pipe = self._pipeline(kind, job)
        if job.cancel.is_set():
            raise GenerationCancelled()
        seed = request.seed if request.seed >= 0 else secrets.randbelow(2**63 - 1)
        job.seed = seed
        generator = torch.Generator(device="cpu").manual_seed(seed)
        prompt = f"{request.prompt}, {STYLES.get(request.style, request.style)}"
        if video:
            prompt += ", " + ("gentle subtle movement" if request.motion < 0.34 else "moderate natural movement" if request.motion < 0.67 else "energetic dynamic movement")
        steps = 30 if video else 24

        def callback(pipeline, index, timestep, kwargs):
            if job.cancel.is_set():
                raise GenerationCancelled()
            job.stage = "Video diffusion" if video else "Image diffusion"
            total = max(1, getattr(pipeline, "num_timesteps", steps))
            job.progress = min(99, round((index + 1) / total * 100))
            return kwargs

        kwargs = dict(prompt=prompt, num_inference_steps=steps, generator=generator, callback_on_step_end=callback)
        width, height = request.dimensions
        if video:
            kwargs.update(num_frames=49, guidance_scale=6.0)
            if image:
                kwargs["image"] = load_image(str(image)).resize((720, 480))
            frames = pipe(**kwargs).frames[0]
            job.stage, job.progress = "Encoding animation", None
            if job.cancel.is_set():
                raise GenerationCancelled()
            frames = frames[:request.duration * 8]
            # Preserve a true aspect ratio by cropping, not stretching the model output.
            from PIL import ImageOps
            frames = [ImageOps.fit(frame.convert("RGB"), (width, height), method=Image.Resampling.LANCZOS) for frame in frames]
            if request.tool == "aigif":
                output = job.directory / "result.gif"
                frames[0].save(output, save_all=True, append_images=frames[1:], duration=125, loop=0, optimize=True)
                return output, "image/gif"
            output = job.directory / "result.mp4"
            export_to_video(frames, str(output), fps=8)
            return output, "video/mp4"
        kwargs.update(negative_prompt=request.negative)
        if image:
            kwargs.update(image=load_image(str(image)).resize((width, height)), strength=0.65)
        else:
            kwargs.update(width=width, height=height)
        result = pipe(**kwargs)
        if getattr(result, "nsfw_content_detected", None) and any(result.nsfw_content_detected):
            raise RuntimeError("The configured model's safety checker blocked this result.")
        if job.cancel.is_set():
            raise GenerationCancelled()
        output = job.directory / "result.png"
        result.images[0].save(output)
        return output, "image/png"
