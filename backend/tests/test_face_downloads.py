import hashlib
import io

import pytest

from tools import download_face_models as downloader


def test_explicit_downloader_verifies_bytes_and_reuses_matching_files(tmp_path, monkeypatch):
    content = b"test model download bytes"
    expected = hashlib.sha256(content).hexdigest()
    monkeypatch.setattr(downloader, "MODELS", [("test.pth", "https://model.invalid/test.pth", expected)])
    calls = []
    monkeypatch.setattr(downloader.urllib.request, "urlopen", lambda *a, **k: calls.append(a[0]) or io.BytesIO(content))
    downloader.download(tmp_path)
    downloader.download(tmp_path)
    assert calls == ["https://model.invalid/test.pth"]
    assert (tmp_path / "test.pth").read_bytes() == content


def test_bad_checksum_is_not_installed_and_existing_files_are_not_overwritten(tmp_path, monkeypatch):
    monkeypatch.setattr(downloader, "MODELS", [("test.pth", "https://model.invalid/test.pth", "0" * 64)])
    monkeypatch.setattr(downloader.urllib.request, "urlopen", lambda *a, **k: io.BytesIO(b"invalid"))
    with pytest.raises(ValueError, match="Checksum mismatch"):
        downloader.download(tmp_path)
    assert list(tmp_path.iterdir()) == []
    (tmp_path / "test.pth").write_bytes(b"existing")
    with pytest.raises(ValueError, match="wrong SHA-256"):
        downloader.download(tmp_path)
    assert (tmp_path / "test.pth").read_bytes() == b"existing"
