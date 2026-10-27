from types import SimpleNamespace
from backend.transcribe import caption_blocks, timestamp


def test_whisper_segments_produce_importable_srt():
    segments = [SimpleNamespace(start=0.125, end=1.75, text=" Hello 世界 ")]
    assert list(caption_blocks(segments)) == ["1\n00:00:00,125 --> 00:00:01,750\nHello 世界\n"]


def test_legacy_captions_remain_compatible_and_empty_segments_are_ignored():
    segments = [SimpleNamespace(start=1, end=2, text="Retro"), SimpleNamespace(start=2, end=2, text="skip")]
    assert list(caption_blocks(segments, "legacy")) == ["1000|Retro\n2000|"]


def test_long_subtitle_timestamps():
    assert timestamp(3_723_004) == "01:02:03,004"
