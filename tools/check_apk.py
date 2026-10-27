"""Inspect the actual built APK, manifest and 64-bit native page alignment.

Use apkanalyzer manifest print to decode Android binary XML before invoking this.
Signature verification remains the job of the SDK's apksigner, not this parser.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"
PAGE = 16384


def elf_load_segments(data):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("Invalid ELF header")
    bits = 64 if data[4] == 2 else 32
    order = "<" if data[5] == 1 else ">"
    if bits == 64:
        phoff = struct.unpack_from(order + "Q", data, 32)[0]
        entry_size, count = struct.unpack_from(order + "HH", data, 54)
        expected = 56
    else:
        phoff = struct.unpack_from(order + "I", data, 28)[0]
        entry_size, count = struct.unpack_from(order + "HH", data, 42)
        expected = 32
    if entry_size < expected or not count or phoff + entry_size * count > len(data):
        raise ValueError("Invalid ELF program table")
    segments = []
    for index in range(count):
        offset = phoff + index * entry_size
        if struct.unpack_from(order + "I", data, offset)[0] != 1:
            continue
        if bits == 64:
            file_offset, address, _, file_size, memory_size, alignment = struct.unpack_from(order + "QQQQQQ", data, offset + 8)
        else:
            _, file_offset, address, _, file_size, memory_size, _, alignment = struct.unpack_from(order + "IIIIIIII", data, offset)
        if file_offset + file_size > len(data) or memory_size < file_size:
            raise ValueError("ELF segment extends past file")
        segments.append({"offset": file_offset, "virtual_address": address, "alignment": alignment})
    if not segments:
        raise ValueError("ELF contains no load segments")
    return bits, segments


def inspect_apk(apk, manifest):
    root = ET.fromstring(Path(manifest).read_text())
    application = root.find("application")
    sdk = root.find("uses-sdk")
    if application is None or sdk is None:
        raise ValueError("Decoded manifest is missing application/SDK")
    permissions = sorted(node.attrib[ANDROID + "name"] for node in root.findall("uses-permission"))
    package = root.attrib.get("package", "")
    with Path(apk).open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    report = {
        "apk": Path(apk).name, "bytes": Path(apk).stat().st_size,
        "sha256": digest,
        "application_id": package, "version_name": root.attrib.get(ANDROID + "versionName"),
        "version_code": int(root.attrib[ANDROID + "versionCode"]),
        "min_sdk": int(sdk.attrib[ANDROID + "minSdkVersion"]),
        "target_sdk": int(sdk.attrib[ANDROID + "targetSdkVersion"]),
        "debuggable": application.attrib.get(ANDROID + "debuggable", "false") == "true",
        "permissions": permissions, "native_libraries": [], "errors": [],
    }
    errors = report["errors"]
    if package != "studio.retro.ai" or report["min_sdk"] != 26 or report["target_sdk"] < 35:
        errors.append("Unexpected app ID or SDK boundaries")
    if application.attrib.get(ANDROID + "allowBackup") != "false":
        errors.append("App-data backup must remain disabled")
    if application.attrib.get(ANDROID + "usesCleartextTraffic") != "false":
        errors.append("Cleartext networking must remain disabled")
    # WorkManager's normal scheduler/foreground-service declarations are expected.
    allowed = {"android.permission." + name for name in ("INTERNET", "ACCESS_NETWORK_STATE", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED", "FOREGROUND_SERVICE")}
    allowed.add(package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    unexpected = set(permissions) - allowed
    if unexpected:
        errors.append("Unexpected permissions: " + ", ".join(sorted(unexpected)))
    with zipfile.ZipFile(apk) as archive, Path(apk).open("rb") as raw:
        for item in archive.infolist():
            if not (item.filename.startswith("lib/") and item.filename.endswith(".so")):
                continue
            bits, segments = elf_load_segments(archive.read(item))
            raw.seek(item.header_offset)
            header = raw.read(30)
            if header[:4] != b"PK\x03\x04":
                raise ValueError("Invalid ZIP local header")
            name_size, extra_size = struct.unpack_from("<HH", header, 26)
            data_offset = item.header_offset + 30 + name_size + extra_size
            elf_aligned = all(segment["alignment"] >= PAGE and segment["alignment"] % PAGE == 0 and (segment["virtual_address"] - segment["offset"]) % PAGE == 0 for segment in segments)
            zip_aligned = item.compress_type == zipfile.ZIP_STORED and data_offset % PAGE == 0
            report["native_libraries"].append({"path": item.filename, "bits": bits, "load_segments": segments, "zip_data_offset": data_offset, "uncompressed": item.compress_type == zipfile.ZIP_STORED, "elf_16k_aligned": elf_aligned, "zip_16k_aligned": zip_aligned})
            if bits == 64 and not (elf_aligned and zip_aligned):
                errors.append("64-bit native library is not 16 KB aligned: " + item.filename)
    abis = {item["path"].split("/")[1] for item in report["native_libraries"]}
    report["native_abis"] = sorted(abis)
    if not {"arm64-v8a", "x86_64"}.issubset(abis):
        errors.append("Missing expected 64-bit native ABI")
    report["static_16k_check_passed"] = not errors
    report["notice"] = "Static ELF/ZIP packaging verification. This does not establish native inference or media export on a physical ARM/16 KB device. Model weights are not bundled."
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expect-release", action="store_true")
    args = parser.parse_args()
    report = inspect_apk(args.apk, args.manifest)
    if args.expect_release and report["debuggable"]:
        report["errors"].append("Release APK is debuggable")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("apk", "bytes", "sha256", "version_name", "native_abis", "errors")}))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
