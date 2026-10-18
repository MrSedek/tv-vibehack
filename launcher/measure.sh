#!/usr/bin/env bash
# Timing and memory numbers for the launcher on the TV. Run it before and after a change and keep
# both sets; a slow TV punishes guesses.
#
#   TV_HOST=192.168.1.50 [AWAY=org.smarttube.beta] [N=5] ./measure.sh
#
# AWAY is the app to come back from for the warm-return number. Individual runs go to stderr,
# medians to stdout.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
PY=../tools/venv/bin/python
TVPY=../tools/tv.py
APP=com.gathin.tvhome
AWAY="${AWAY:-org.smarttube.beta}"
N="${N:-5}"

tv() { "$PY" -I "$TVPY" "$@"; }
median() { sort -n | awk '{a[NR]=$1} END {if (NR == 0) print "n/a"; else if (NR % 2) print a[(NR+1)/2]; else print (a[NR/2] + a[NR/2+1]) / 2}'; }
home_ms() { tv 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME' | awk '/TotalTime/ {print $2}'; }

echo "== cold start, $N runs (ms)"
for _ in $(seq "$N"); do tv "am force-stop $APP; sleep 1" >/dev/null; home_ms; done | tee /dev/stderr | median | sed 's/^/median: /'

echo "== warm return from $AWAY, $N runs (ms)"
for _ in $(seq "$N"); do
  tv "monkey -p $AWAY -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; sleep 5" >/dev/null
  home_ms
done | tee /dev/stderr | median | sed 's/^/median: /'

echo "== frames over 20 D-pad moves on the dock"
tv "dumpsys gfxinfo $APP reset >/dev/null; for i in 1 2 3 4 5 6 7 8 9 10; do input keyevent KEYCODE_DPAD_RIGHT; input keyevent KEYCODE_DPAD_LEFT; done; sleep 1; dumpsys gfxinfo $APP" \
  | grep -E 'Total frames|Janky frames|95th percentile'

echo "== memory"
tv "dumpsys meminfo $APP" | grep -E 'TOTAL PSS'

echo "== dexopt state (speed = compiled ahead of time)"
tv "dumpsys package $APP" | grep -E '\[status=' | head -3
