#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen, together with the
# bridge app that fills the continue-watching shelf (skip it with BRIDGE=0).
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
BRIDGE="${BRIDGE:-1}"

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
  elif [ -d "/Library/Java/JavaVirtualMachines/jdk-18.0.1.1.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-18.0.1.1.jdk/Contents/Home"
  elif [ -d "/opt/homebrew/opt/openjdk@17" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
  fi
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [ -d "/opt/homebrew/share/android-commandlinetools" ]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
  fi
fi

export PATH="${JAVA_HOME:-/usr}/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=${ANDROID_HOME:-}" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
if [ "$BRIDGE" = 1 ]; then
  # First, so its signature permission exists when the launcher that asks for it is installed.
  "$PY" -I ../tools/b64push.py bridge/build/outputs/apk/debug/bridge-debug.apk /data/local/tmp/tvbridge.apk
  printf '%s\n' \
    "pm install -r /data/local/tmp/tvbridge.apk" \
    "rm -f /data/local/tmp/tvbridge.apk" \
    "cmd notification allow_listener com.gathin.tvbridge/com.gathin.tvbridge.MediaWatchService; echo bridge-listener-allowed" \
    | "$PY" -I ../tools/tv.py --batch
fi
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote to see it."
