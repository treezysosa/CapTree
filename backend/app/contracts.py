from typing import Literal
from pydantic import BaseModel, Field


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
