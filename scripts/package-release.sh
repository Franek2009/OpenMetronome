#!/usr/bin/env bash
# Validate the exact signed APK before making it a public download.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/release-info.sh"
cd -- "$release_script_root"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
release_expected_version="$release_version"
release_cert_name=SIGNING-CERT-SHA256.txt
release_expected_cert="${OM_CERT_SHA256:-}"
release_output="app/build/official-release"
release_distribution=production
release_default_input=app/build/outputs/apk/prod/release/app-prod-release.apk
if [[ "${1:-}" == --dev ]]; then
    shift
    release_package="$release_dev_package"
    release_expected_version="$release_dev_version"
    release_apk_name="$release_dev_apk_name"
    release_expected_cert="${OM_DEV_CERT_SHA256:-}"
    release_cert_name=DEV-SIGNING-CERT-SHA256.txt
    release_output=app/build/official-dev-release
    release_default_input=app/build/outputs/apk/dev/release/app-dev-release.apk
    release_distribution=DEV
fi
[[ $# -le 1 ]] || { echo 'Usage: package-release.sh [--dev] [signed.apk]' >&2; exit 2; }
release_input="${1:-$release_default_input}"
release_tools="$ANDROID_HOME/build-tools/36.0.0"
release_badging="$($release_tools/aapt dump badging "$release_input")"
grep -q "^package: name='$release_package' versionCode='$release_code' versionName='$release_expected_version'" <<< "$release_badging" || {
    echo "Refusing a non-$release_distribution package or incorrect version" >&2; exit 1;
}
if grep -q 'application-debuggable' <<< "$release_badging"; then
    echo 'Refusing a debuggable APK' >&2; exit 1
fi
release_cert_output="$($release_tools/apksigner verify --verbose --print-certs "$release_input")"
printf '%s\n' "$release_cert_output"
if grep -qi 'CN=Android Debug' <<< "$release_cert_output"; then
    echo 'Refusing a debug signing certificate' >&2; exit 1
fi
: "${release_expected_cert:?Set the expected public signing certificate SHA-256 for this distribution}"
release_actual_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$release_cert_output")"
[[ "$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' <<< "$release_cert_output")" == 1 ]] || {
    echo 'Refusing multiple signing certificates' >&2; exit 1;
}
[[ "${release_actual_cert,,}" == "${release_expected_cert,,}" ]] || {
    echo 'Signing certificate mismatch' >&2; exit 1;
}
mkdir -p -- "$release_output"
cp -- "$release_input" "$release_output/$release_apk_name"
(
    cd -- "$release_output"
    sha256sum "$release_apk_name" > SHA256SUMS
    sha256sum --check SHA256SUMS
)
printf '%s\n' "$release_actual_cert" > "$release_output/$release_cert_name"
