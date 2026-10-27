#!/usr/bin/env bash
# CI-only disposable key: verifies the owner-signing path without owner credentials.
set -euo pipefail
retro_key_dir="$(mktemp -d)"
trap 'rm -rf "$retro_key_dir"' EXIT
mkdir -p signing-validation
keytool -genkeypair -noprompt -keystore "$retro_key_dir/validation.jks" -storepass android -keypass android -alias validation -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Disposable CI Validation" >/dev/null 2>&1
RETRO_RELEASE_STORE_FILE="$retro_key_dir/validation.jks" RETRO_RELEASE_STORE_PASSWORD=android RETRO_RELEASE_KEY_ALIAS=validation RETRO_RELEASE_KEY_PASSWORD=android ./gradlew --no-daemon assembleRelease
retro_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is required}}"
retro_apk=app/build/outputs/apk/release/app-release.apk
"$retro_sdk/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$retro_apk" > signing-validation/apksigner.txt
"$retro_sdk/cmdline-tools/latest/bin/apkanalyzer" manifest print "$retro_apk" > signing-validation/manifest.xml
python3 tools/check_apk.py --apk "$retro_apk" --manifest signing-validation/manifest.xml --output signing-validation/apk.json --expect-release
keytool -exportcert -keystore "$retro_key_dir/validation.jks" -storepass android -alias validation -file "$retro_key_dir/certificate.der" >/dev/null 2>&1
python3 - "$retro_key_dir/certificate.der" <<'PY'
import hashlib
from pathlib import Path
import sys
expected = hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()
assert 'Signer #1 certificate SHA-256 digest: ' + expected in Path('signing-validation/apksigner.txt').read_text()
print('The signed release certificate matches the supplied validation keystore.')
PY
