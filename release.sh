#!/usr/bin/env bash
# Build the signed release APK and publish it as a GitHub release named after the app version.
# Usage: ./release.sh "What changed in this release"
set -euo pipefail
cd "$(dirname "$0")"
[[ -d $HOME/Android/jdk ]] && export JAVA_HOME="$HOME/Android/jdk"
export ANDROID_HOME="$HOME/Android/Sdk"

CHANGES="${1:?Pass a short description of what changed.}"
VERSION=$(sed -nE 's/^val appVersion = "(.*)"/\1/p' app/build.gradle.kts)
[[ -z $(git status --porcelain) ]] || { echo "Commit your changes first."; exit 1; }
if gh release view "$VERSION" >/dev/null 2>&1; then
    echo "Release $VERSION already exists. Raise appVersion in app/build.gradle.kts."
    exit 1
fi

./gradlew assembleRelease --console=plain -q
APK="app/build/outputs/apk/release/StatSlop-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
# A debug-signed APK could never be updated by later releases, so refuse to publish one.
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs "$APK" | grep -q "CN=StatSlop" \
    || { echo "APK is not signed with the release key (~/.config/statslop)."; exit 1; }

git push -q
gh release create "$VERSION" "$APK" --title "StatSlop $VERSION" --notes "$CHANGES

### Install
1. Download \`StatSlop-$VERSION.apk\` below on your phone (Android 12 or newer) and open it.
2. Allow installing apps from that source when asked.
3. For per-app screen time: open the Apps tab in StatSlop and tap **Allow usage access**.

SHA-256: \`$(sha256sum "$APK" | cut -d' ' -f1)\`"
