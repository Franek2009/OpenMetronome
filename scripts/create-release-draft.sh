#!/usr/bin/env bash
# Assemble already verified artifacts. Never builds, signs, publishes or overwrites.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/release-info.sh"
include_dev="${RELEASE_INCLUDE_DEV:-true}"
[[ "$include_dev" == true || "$include_dev" == false ]] || { echo 'Expected RELEASE_INCLUDE_DEV=true|false' >&2; exit 2; }
[[ $# == 2 ]] || { echo 'Usage: create-release-draft.sh prod-assets dev-assets' >&2; exit 2; }
prod_dir="$(cd -- "$1" && pwd)"
(cd -- "$prod_dir" && sha256sum --check SHA256SUMS)
mkdir -p "$release_script_root/app/build"
assets_dir="$(mktemp -d "$release_script_root/app/build/draft-assets.XXXXXXXX")"
trap 'rm -rf -- "$assets_dir"' EXIT
cp -- "$prod_dir/$release_apk_name" "$prod_dir/SIGNING-CERT-SHA256.txt" "$assets_dir/"
files=("$release_apk_name" SIGNING-CERT-SHA256.txt)
if [[ "$include_dev" == true ]]; then
    dev_dir="$(cd -- "$2" && pwd)"
    (cd -- "$dev_dir" && sha256sum --check SHA256SUMS)
    cp -- "$dev_dir/$release_dev_apk_name" "$dev_dir/DEV-SIGNING-CERT-SHA256.txt" "$assets_dir/"
    files+=("$release_dev_apk_name" DEV-SIGNING-CERT-SHA256.txt)
fi
cd -- "$assets_dir"
sha256sum "${files[@]}" > SHA256SUMS
sha256sum --check SHA256SUMS
channel=()
[[ "$release_version" != *-* ]] || channel=(--prerelease)
gh release create "$release_tag" "${files[@]}" SHA256SUMS --verify-tag --draft "${channel[@]}" \
    --title "OpenMetronome $release_version" --notes-file "$release_script_root/docs/releases/$release_version.md"
