#!/usr/bin/env bash
# Gradle convenience entry point. For offline Windows SDK builds use build-apk.ps1.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew assembleDebug "$@"
version=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk "dist/Afterglow-${version}.apk"
printf 'Built: dist/Afterglow-%s.apk\n' "$version"
