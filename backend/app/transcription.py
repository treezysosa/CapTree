"""Real faster-whisper CPU inference, loaded only when explicitly requested."""
import os
from backend.transcribe import caption_blocks
from .inference import GenerationCancelled


class TranscriptionEngine:
    def __init__(self):
        self.model = None

    def run(self, request, audio, job):
        if job.cancel.is_set():
            raise GenerationCancelled()
        if self.model is None:
            from faster_whisper import WhisperModel
            job.progress, job.stage = None, "Loading speech model"
            self.model = WhisperModel(os.environ.get("RETRO_WHISPER_MODEL", "tiny"), device="cpu", compute_type="int8", cpu_threads=2, num_workers=1)
        job.progress, job.stage = None, "Transcribing selected audio"
        segments, info = self.model.transcribe(str(audio), language=None if request.language == "auto" else request.language, vad_filter=True, beam_size=5)
        blocks = []
        for segment in segments:
            if job.cancel.is_set():
                raise GenerationCancelled()
            blocks.extend(caption_blocks([segment]))
            job.stage = "Transcribing selected audio"
            job.progress = min(99, round(segment.end / max(info.duration, 0.001) * 100))
        if job.cancel.is_set():
            raise GenerationCancelled()
        if not blocks:
            raise RuntimeError("No speech was detected in the selected audio.")
        # Re-number the independently formatted speech segments.
        output = job.directory / "result.srt"
        output.write_text("\n".join(block.replace("1\n", f"{index}\n", 1) for index, block in enumerate(blocks, 1)), encoding="utf-8")
        return output, "application/x-subrip"
