"""Explicitly download the three verified face weights; the server never downloads them."""
import argparse
import hashlib
from pathlib import Path
import tempfile
import urllib.request

MODELS = [
    ("GFPGANv1.4.pth", "https://github.com/TencentARC/GFPGAN/releases/download/v1.3.0/GFPGANv1.4.pth", "e2cd4703ab14f4d01fd1383a8a8b266f9a5833dacee8e6a79d3bf21a1b6be5ad"),
    ("detection_Resnet50_Final.pth", "https://github.com/xinntao/facexlib/releases/download/v0.1.0/detection_Resnet50_Final.pth", "6d1de9c2944f2ccddca5f5e010ea5ae64a39845a86311af6fdf30841b0a5a16d"),
    ("parsing_parsenet.pth", "https://github.com/xinntao/facexlib/releases/download/v0.2.2/parsing_parsenet.pth", "3d558d8d0e42c20224f13cf5a29c79eba2d59913419f945545d8cf7b72920de2"),
]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def download(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, url, expected in MODELS:
        destination = directory / name
        if destination.exists():
            if sha256(destination) != expected:
                raise ValueError(f"{name} has the wrong SHA-256. Move it aside before downloading verified weights.")
            print(f"Verified {name}")
            continue
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=directory, prefix=".face-download-", delete=False) as sink:
                temporary = Path(sink.name)
                with urllib.request.urlopen(url, timeout=60) as source:
                    total = 0
                    while chunk := source.read(1024 * 1024):
                        total += len(chunk)
                        if total > 512 * 1024 * 1024:
                            raise ValueError("Unexpected model file size.")
                        sink.write(chunk)
            if sha256(temporary) != expected:
                raise ValueError(f"Checksum mismatch for {name}.")
            temporary.replace(destination)
            print(f"Downloaded and verified {name}")
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    download(parser.parse_args().directory.expanduser().resolve())
