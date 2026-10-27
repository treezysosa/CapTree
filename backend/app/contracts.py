from typing import Literal
from pydantic import BaseModel, Field, ConfigDict, model_validator


class Generation(BaseModel):
    tool: Literal["photo", "video", "aigif"]
    prompt: str = Field(min_length=1, max_length=2000)
    negative: str = Field(default="", max_length=2000)
    style: str = Field(default="Cinematic", max_length=64)
    aspect: Literal["1:1", "9:16", "16:9"] = "1:1"
    seed: int = Field(default=-1, ge=-1, le=2**63 - 1)
    duration: Literal[2, 3, 6] = 3
    motion: float = Field(default=0.5, ge=0, le=1)

    @property
    def dimensions(self) -> tuple[int, int]:
        return {"1:1": (512, 512), "9:16": (432, 768), "16:9": (768, 432)}[self.aspect]


class Transcription(BaseModel):
    tool: Literal["transcribe"] = "transcribe"
    language: str = Field(default="auto", pattern=r"^(auto|[a-z]{2,3})$")


class FaceRestoration(BaseModel):
    tool: Literal["restore-face"] = "restore-face"
    strength: float = Field(default=0.7, gt=0, le=1)


class Highlights(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    tool: Literal["highlights"] = "highlights"
    prompt: str = Field(min_length=1, max_length=300)
    duration_ms: int = Field(ge=1000, le=120000)
    clip_ms: int = Field(ge=1000, le=15000)
    timestamps_ms: list[int] = Field(min_length=1, max_length=24)

    @model_validator(mode="after")
    def valid_samples(self):
        if not self.prompt.strip() or self.clip_ms > self.duration_ms:
            raise ValueError("Choose a description and a clip length within the selected trim.")
        if self.timestamps_ms != sorted(set(self.timestamps_ms)) or not all(0 <= t < self.duration_ms for t in self.timestamps_ms):
            raise ValueError("Sample times must be unique, ordered and inside the selected trim.")
        return self
