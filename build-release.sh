#!/usr/bin/env bash
# Builds the signed release APK at app/build/outputs/apk/release/app-release.apk.
# The signing key is secrets/remo-release.jks with its password in secrets/remo-release.env (not in git).
# Keep that key: Android only installs an update over the app if it's signed with the same key.
# Usage: ./build-release.sh [versionName]   (versionCode = build time in minutes)
set -euo pipefail
cd "$(dirname "$0")"
set -a; . secrets/remo-release.env; set +a
export REMO_KEYSTORE_FILE="$PWD/secrets/remo-release.jks"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew --console=plain -q :app:assembleRelease -PversionName="${1:-1.0.0}" -PversionCode="$(( $(date +%s) / 60 ))"
ls -l app/build/outputs/apk/release/app-release.apk
