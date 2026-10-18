#!/usr/bin/env bash
# Build the launcher and install it on the TV as the default home screen, together with the bridge
# app that fills the continue-watching shelf and the clock screensaver.
#
#   TV_HOST=192.168.1.50 [FLAVOR=generic|stick] [BRIDGE=1] [DREAM=1] [STOCK_LAUNCHER=...] ./build-and-install.sh
#
# With TVHOME_KEYSTORE set (see ../tools/make-keystore.sh) it builds signed, shrunk release APKs;
# otherwise debug ones signed with this machine's debug key.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
FLAVOR="${FLAVOR:-generic}"
BRIDGE="${BRIDGE:-1}"
DREAM="${DREAM:-1}"
if [ -n "${TVHOME_KEYSTORE:-}" ]; then TYPE=release; else TYPE=debug; fi

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

cap() { printf '%s%s' "$(printf '%s' "${1:0:1}" | tr '[:lower:]' '[:upper:]')" "${1:1}"; }
./gradlew ":app:assemble$(cap "$FLAVOR")$(cap "$TYPE")" ":bridge:assemble$(cap "$TYPE")" -q --console=plain

PY=../tools/venv/bin/python
TV=../tools/tv.py
LAUNCHER_APK="app/build/outputs/apk/$FLAVOR/$TYPE/app-$FLAVOR-$TYPE.apk"
BRIDGE_APK="bridge/build/outputs/apk/$TYPE/bridge-$TYPE.apk"
[ -f "$LAUNCHER_APK" ] || { echo "no APK at $LAUNCHER_APK"; exit 1; }

# pm prints "Failure [...]" and keeps going; a different signing key is the usual reason.
push_install() {   # <local apk> <remote name> <commands after install...>
  local apk="$1" name="$2"; shift 2
  "$PY" -I ../tools/b64push.py "$apk" "/data/local/tmp/$name"
  local out
  out=$(printf '%s\n' "pm install -r /data/local/tmp/$name" "rm -f /data/local/tmp/$name" "$@" | "$PY" -I "$TV" --batch)
  echo "$out"
  if grep -q 'Failure' <<<"$out"; then
    echo "Install failed. INSTALL_FAILED_UPDATE_INCOMPATIBLE means a different signing key: pm uninstall both apps first (see README)."
    exit 1
  fi
}

if [ "$BRIDGE" = 1 ]; then
  # First, so its signature permission exists when the launcher that asks for it is installed.
  push_install "$BRIDGE_APK" tvbridge.apk \
    "cmd notification allow_listener com.gathin.tvbridge/com.gathin.tvbridge.MediaWatchService; echo bridge-listener-allowed"
fi

push_install "$LAUNCHER_APK" tvhome.apk \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome" \
  "pm compile -m speed -f com.gathin.tvhome >/dev/null 2>&1; echo compiled"

if [ "$DREAM" = 1 ]; then
  printf '%s\n' \
    "settings put secure screensaver_enabled 1" \
    "settings put secure screensaver_components com.gathin.tvhome/.ClockDream; echo screensaver-set" \
    | "$PY" -I "$TV" --batch
fi

# Take the stock launcher away only once Home resolves to ours; otherwise the TV would be left with
# no home screen and no way to get one back without adb.
HOME_NOW=$("$PY" -I "$TV" 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -1 | tr -d '\r')
case "$HOME_NOW" in
  com.gathin.tvhome/*) "$PY" -I "$TV" "pm disable-user --user 0 $STOCK_LAUNCHER" ;;
  *) echo "Home resolves to '$HOME_NOW', not to com.gathin.tvhome; leaving $STOCK_LAUNCHER enabled."; exit 1 ;;
esac
echo "Installed ($FLAVOR, $TYPE). Press Home on the remote to see it."
