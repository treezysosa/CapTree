import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("check_apk", Path(__file__).parents[1] / "check_apk.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="studio.retro.ai" android:versionCode="10" android:versionName="0.10.0"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.INTERNET"/><application android:allowBackup="false" android:usesCleartextTraffic="false"/></manifest>'''


def elf(alignment=16384, address=0):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, address, 0, 120, 120, alignment)
    return data


class ApkChecks(unittest.TestCase):
    def build(self, root, alignment=16384, padded=True, manifest=MANIFEST):
        apk = root / "app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for abi in ("arm64-v8a", "x86_64"):
                entry = zipfile.ZipInfo(f"lib/{abi}/libtest.so")
                if padded:
                    padding = -(archive.fp.tell() + 30 + len(entry.filename.encode())) % 16384
                    if padding < 4:
                        padding += 16384
                    entry.extra = struct.pack("<HH", 0xFFFF, padding - 4) + bytes(padding - 4)
                archive.writestr(entry, elf(alignment))
        xml = root / "manifest.xml"
        xml.write_text(manifest)
        return checker.inspect_apk(apk, xml)

    def test_aligned_package_and_manifest_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            report = self.build(Path(directory))
            self.assertEqual([], report["errors"])
            self.assertEqual(2, len(report["native_libraries"]))

    def test_rejects_4k_elf_even_with_16k_zip(self):
        with tempfile.TemporaryDirectory() as directory:
            report = self.build(Path(directory), alignment=4096)
            self.assertEqual(2, len(report["errors"]))

    def test_rejects_unaligned_zip_even_with_16k_elf(self):
        with tempfile.TemporaryDirectory() as directory:
            report = self.build(Path(directory), padded=False)
            self.assertEqual(2, len(report["errors"]))

    def test_rejects_incongruent_virtual_address_and_truncated_headers(self):
        bits, segments = checker.elf_load_segments(elf(address=4096))
        self.assertEqual(64, bits)
        self.assertNotEqual(0, (segments[0]["virtual_address"] - segments[0]["offset"]) % 16384)
        with self.assertRaises(ValueError):
            checker.elf_load_segments(elf()[:100])

    def test_rejects_new_permission_and_insecure_manifest(self):
        xml = MANIFEST.replace('<application ', '<uses-permission android:name="android.permission.RECORD_AUDIO"/><application ').replace('usesCleartextTraffic="false"', 'usesCleartextTraffic="true"')
        with tempfile.TemporaryDirectory() as directory:
            report = self.build(Path(directory), manifest=xml)
            self.assertEqual(2, len(report["errors"]))


if __name__ == "__main__":
    unittest.main()
