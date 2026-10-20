#!/usr/bin/env bash
# Validate the exact signed APK before making it a public download.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/release-info.sh"
cd -- "$release_script_root"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
release_input="${1:-app/build/outputs/apk/release/app-release.apk}"
release_tools="$ANDROID_HOME/build-tools/36.0.0"
release_cert_output="$($release_tools/apksigner verify --verbose --print-certs "$release_input")"
printf '%s\n' "$release_cert_output"
release_badging="$($release_tools/aapt dump badging "$release_input")"
grep -q "^package: name='org.openmetronome' versionCode='$release_code' versionName='$release_version'" <<< "$release_badging"
if grep -q 'application-debuggable' <<< "$release_badging"; then
    echo 'Refusing a debuggable APK' >&2; exit 1
fi
if grep -qi 'CN=Android Debug' <<< "$release_cert_output"; then
    echo 'Refusing a debug signing certificate' >&2; exit 1
fi
: "${OM_CERT_SHA256:?Set the expected public signing certificate SHA-256}"
release_actual_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$release_cert_output")"
[[ "${release_actual_cert,,}" == "${OM_CERT_SHA256,,}" ]] || {
    echo 'Signing certificate mismatch' >&2; exit 1;
}
release_output="app/build/official-release"
mkdir -p -- "$release_output"
cp -- "$release_input" "$release_output/$release_apk_name"
(
    cd -- "$release_output"
    sha256sum "$release_apk_name" > SHA256SUMS
    sha256sum --check SHA256SUMS
)
printf '%s\n' "$release_actual_cert" > "$release_output/SIGNING-CERT-SHA256.txt"
