#!/usr/bin/env bash
# One signing key for the launcher and the bridge, kept outside the repo. Their signature permission
# only works while both are signed with the same key, and a debug key differs from machine to machine.
#
#   tools/make-keystore.sh [path]      default: ~/.tvhome/release.jks
set -euo pipefail
OUT="${1:-$HOME/.tvhome/release.jks}"
ALIAS="${TVHOME_KEY_ALIAS:-tvhome}"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if [ -e "$OUT" ]; then
  echo "$OUT already exists; pick another path or export TVHOME_KEYSTORE=$OUT to use it."
  exit 1
fi
mkdir -p "$(dirname "$OUT")"
read -r -s -p "Keystore password (also used for the key): " PW; echo
"$KEYTOOL" -genkeypair -keystore "$OUT" -storepass "$PW" -keypass "$PW" -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tv-vibehack" >/dev/null
chmod 600 "$OUT"
cat <<EOF

Created $OUT. Add to your shell profile (the password stays out of the repo):

  export TVHOME_KEYSTORE="$OUT"
  export TVHOME_KEYSTORE_PASSWORD='<the password>'
  export TVHOME_KEY_ALIAS="$ALIAS"

launcher/build-and-install.sh then builds signed release APKs. Keep a backup of the file: an APK
signed with another key can't update the installed one.
EOF
