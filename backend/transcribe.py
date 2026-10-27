"""Optional local transcription: pip install -r backend/requirements-speech.txt
python backend/transcribe.py input.mp4 > captions.srt
Import the result in Android's SRT caption editor. --format legacy preserves
the original milliseconds|caption output.
"""
import argparse


def timestamp(milliseconds):
    value = max(0, milliseconds)
    return f"{value // 3600000:02d}:{value // 60000 % 60:02d}:{value // 1000 % 60:02d},{value % 1000:03d}"


def caption_blocks(segments, format="srt"):
    for index, segment in enumerate(segments, start=1):
        start, end = round(segment.start * 1000), round(segment.end * 1000)
        text = segment.text.strip()
        if end <= start or not text:
            continue
        if format == "legacy":
            yield f"{start}|{text}\n{end}|"
        else:
            yield f"{index}\n{timestamp(start)} --> {timestamp(end)}\n{text}\n"

if __name__ == "__main__":
    from faster_whisper import WhisperModel
    parser = argparse.ArgumentParser()
    parser.add_argument("video")
    parser.add_argument("--model", default="small")
    parser.add_argument("--format", choices=["srt", "legacy"], default="srt")
    args = parser.parse_args()
    model = WhisperModel(args.model, device="cpu", compute_type="int8")
    segments, info = model.transcribe(args.video, vad_filter=True)
    for block in caption_blocks(segments, args.format):
        print(block)
