#!/usr/bin/env bash
# Generates secrets, never prints their contents, and never overwrites a key.
set -euo pipefail
umask 077
release_key_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
release_key_dir="${OM_SIGNING_DIR:-$release_key_root/.tooling/signing}"
if [[ -e "$release_key_dir" ]]; then
    echo 'Signing directory already exists. Refusing to overwrite any signing material.' >&2
    exit 1
fi
command -v keytool >/dev/null
command -v openssl >/dev/null
mkdir -m 700 -- "$release_key_dir"
openssl rand -base64 48 > "$release_key_dir/store.password"
keytool -genkeypair -keystore "$release_key_dir/openmetronome.p12" -storetype PKCS12 \
    -storepass:file "$release_key_dir/store.password" -keypass:file "$release_key_dir/store.password" \
    -alias openmetronome -keyalg RSA -keysize 4096 -validity 10000 \
    -dname 'CN=OpenMetronome' -noprompt
chmod 600 "$release_key_dir/openmetronome.p12"
echo 'Key created in .tooling/signing (excluded from Git).'
echo 'Back up BOTH openmetronome.p12 and store.password securely outside this computer.'
echo 'Do not publish a release until the owner confirms that backup.'
