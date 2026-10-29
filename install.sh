#!/usr/bin/env bash
# Build (if needed) and install StatSlop on the phone connected via USB debugging.
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$HOME/Downloads/android-platform-tools/platform-tools/adb}"
APK=app/build/outputs/apk/release/app-release.apk
PKG=app.statslop

# Use the portable JDK from ~/Android if present (the system Java has no compiler).
[[ -d $HOME/Android/jdk ]] && export JAVA_HOME="$HOME/Android/jdk"

if [[ ! -f $APK || "${1:-}" == "--build" ]]; then
    ANDROID_HOME="$HOME/Android/Sdk" ./gradlew assembleRelease --console=plain -q
fi

"$ADB" get-state >/dev/null || { echo "No phone found. Plug it in and allow USB debugging."; exit 1; }
"$ADB" install -r "$APK"
# Usage access for the Apps tab (same as Settings > Usage access > StatSlop).
"$ADB" shell appops set "$PKG" GET_USAGE_STATS allow
# No Doze exemption on purpose: in deep sleep Android batches the sampler into its
# maintenance windows instead of waking the phone every 15 minutes.
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
echo "StatSlop installed and opened."
