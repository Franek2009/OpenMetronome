#!/usr/bin/env bash
# Load into the current Bash session with: source scripts/dev-env.sh
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    printf '%s\n' 'Wczytaj środowisko: source scripts/dev-env.sh' >&2
    exit 1
fi

openmetronome_jdk="${JAVA_HOME:-$HOME/.local/share/openmetronome/jdk-21}"
if [[ ! -x "$openmetronome_jdk/bin/java" && -z "${JAVA_HOME:-}" ]]; then
    openmetronome_jdk=/usr/lib/jvm/java-21-openjdk
fi
openmetronome_sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"

if [[ ! -x "$openmetronome_jdk/bin/java" ]]; then
    printf '%s\n' 'Brak JDK 21. Zainstaluj narzędzia według README lub ustaw JAVA_HOME.' >&2
    unset openmetronome_jdk openmetronome_sdk
    return 1
fi
if [[ ! -x "$openmetronome_sdk/cmdline-tools/latest/bin/sdkmanager" ]]; then
    printf '%s\n' 'Brak Android SDK Command Line Tools. Sprawdź README lub ANDROID_HOME.' >&2
    unset openmetronome_jdk openmetronome_sdk
    return 1
fi
if [[ -n "${ANDROID_SDK_ROOT:-}" && "$ANDROID_SDK_ROOT" != "$openmetronome_sdk" ]]; then
    printf '%s\n' 'ANDROID_SDK_ROOT i ANDROID_HOME wskazują różne SDK. Uzgodnij te ścieżki.' >&2
    unset openmetronome_jdk openmetronome_sdk
    return 1
fi

export JAVA_HOME="$openmetronome_jdk"
export ANDROID_HOME="$openmetronome_sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
unset openmetronome_jdk openmetronome_sdk
