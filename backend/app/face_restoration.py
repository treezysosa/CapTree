"""GFPGAN v1.4 CPU inference. No unchanged-input fallback on model errors."""
import os
from pathlib import Path

from .inference import GenerationCancelled

WEIGHTS = ("GFPGANv1.4.pth", "detection_Resnet50_Final.pth", "parsing_parsenet.pth")


class NoFaceDetected(Exception):
    pass


def model_directory():
    configured = os.environ.get("RETRO_FACE_MODEL_DIR", "")
    return Path(configured).expanduser().resolve() if configured else None


def models_present():
    directory = model_directory()
    return directory is not None and all((directory / name).is_file() for name in WEIGHTS)


class FaceRestorationEngine:
    def __init__(self):
        self.model = None
        self.helper = None

    def load(self, job):
        if self.model is not None:
            return
        if not models_present():
            raise ValueError("Configure the three licensed face-model weights first.")
        if job.cancel.is_set():
            raise GenerationCancelled()
        job.stage, job.progress = "Loading GFPGAN face models on CPU", None
        import torch
        from gfpgan.archs.gfpganv1_clean_arch import GFPGANv1Clean
        from facexlib.utils.face_restoration_helper import FaceRestoreHelper

        directory = model_directory()
        device = torch.device("cpu")
        model = GFPGANv1Clean(out_size=512, num_style_feat=512, channel_multiplier=2,
                              decoder_load_path=None, fix_decoder=False, num_mlp=8,
                              input_is_latent=True, different_w=True, narrow=1, sft_half=True)
        checkpoint = torch.load(directory / WEIGHTS[0], map_location="cpu", weights_only=True)
        model.load_state_dict(checkpoint.get("params_ema", checkpoint.get("params", checkpoint)), strict=True)
        model.eval().to(device)
        helper = FaceRestoreHelper(1, face_size=512, crop_ratio=(1, 1), det_model="retinaface_resnet50",
                                   save_ext="png", use_parse=True, device=device, model_rootpath=str(directory))
        self.model, self.helper = model, helper

    def run(self, request, image, job):
        self.load(job)
        if job.cancel.is_set():
            raise GenerationCancelled()
        import cv2
        import numpy as np
        import torch
        from basicsr.utils import img2tensor, tensor2img
        from torchvision.transforms.functional import normalize

        original = cv2.imread(str(image), cv2.IMREAD_COLOR)
        if original is None:
            raise ValueError("Cannot decode the input photo.")
        helper = self.helper
        helper.clean_all()
        try:
            job.stage, job.progress = "Detecting the central face", None
            helper.read_image(original)
            helper.get_face_landmarks_5(only_center_face=True, eye_dist_threshold=5)
            helper.align_warp_face()
            if not helper.cropped_faces:
                raise NoFaceDetected()
            if job.cancel.is_set():
                raise GenerationCancelled()
            job.stage = "Restoring the central face with GFPGAN"
            for cropped in helper.cropped_faces:
                tensor = img2tensor(cropped / 255.0, bgr2rgb=True, float32=True)
                normalize(tensor, (0.5, 0.5, 0.5), (0.5, 0.5, 0.5), inplace=True)
                with torch.inference_mode():
                    prediction = self.model(tensor.unsqueeze(0), return_rgb=False, weight=0.5)[0]
                if not torch.isfinite(prediction).all():
                    raise RuntimeError("Model returned non-finite pixels.")
                restored = tensor2img(prediction.squeeze(0), rgb2bgr=True, min_max=(-1, 1)).astype("uint8")
                helper.add_restored_face(restored)
                if job.cancel.is_set():
                    raise GenerationCancelled()
            job.stage = "Blending restored face"
            helper.get_inverse_affine(None)
            result = helper.paste_faces_to_input_image(upsample_img=None)
            if result is None or result.shape != original.shape:
                raise RuntimeError("Face output dimensions did not match the source.")
            result = np.rint(original.astype(np.float32) * (1 - request.strength) + result.astype(np.float32) * request.strength).clip(0, 255).astype(np.uint8)
            if job.cancel.is_set():
                raise GenerationCancelled()
            output = job.directory / "restored.png"
            if not cv2.imwrite(str(output), result):
                raise OSError("Cannot save restored face image.")
            return output, "image/png"
        finally:
            helper.clean_all()
