#!/usr/bin/env bash
set -euo pipefail
mkdir -p apk-verification
retro_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK is required}}"
retro_build_tools="$retro_sdk/build-tools/35.0.0"
retro_analyzer="$retro_sdk/cmdline-tools/latest/bin/apkanalyzer"
for retro_variant in debug release; do
  for retro_apk in app/build/outputs/apk/"$retro_variant"/*.apk; do
    test -f "$retro_apk"
    retro_name="$(basename "$retro_apk" .apk)"
    "$retro_analyzer" manifest print "$retro_apk" > "apk-verification/$retro_name-manifest.xml"
    retro_flags=()
    if [ "$retro_variant" = release ]; then retro_flags+=(--expect-release); fi
    python3 tools/check_apk.py --apk "$retro_apk" --manifest "apk-verification/$retro_name-manifest.xml" --output "apk-verification/$retro_name.json" "${retro_flags[@]}"
    "$retro_build_tools/zipalign" -c -P 16 -v 4 "$retro_apk" > "apk-verification/$retro_name-zipalign.txt"
    if [[ "$retro_apk" != *-unsigned.apk ]]; then
      "$retro_build_tools/apksigner" verify --verbose --print-certs "$retro_apk" > "apk-verification/$retro_name-signature.txt"
    fi
  done
done
