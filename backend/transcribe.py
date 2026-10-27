"""Optional local transcription: pip install faster-whisper==1.1.1
python backend/transcribe.py input.mp4 > captions.txt
Paste the resulting timestamp|caption lines into the Android caption editor.
"""
import argparse

if __name__ == "__main__":
    from faster_whisper import WhisperModel
    parser = argparse.ArgumentParser()
    parser.add_argument("video")
    parser.add_argument("--model", default="small")
    args = parser.parse_args()
    model = WhisperModel(args.model, device="cpu", compute_type="int8")
    segments, info = model.transcribe(args.video, vad_filter=True)
    for segment in segments:
        print(f"{round(segment.start * 1000)}|{segment.text.strip()}")
        print(f"{round(segment.end * 1000)}|")
