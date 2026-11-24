#!/bin/sh
# Turnaround: git pull -> gradle build -> stop server -> start server
set -e

cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
PIDFILE="$ROOT/brouter.pid"
LOGFILE="$ROOT/brouter.log"

git pull
sh "$ROOT/misc/scripts/generate_profile_variants.sh"
./gradlew clean build

# stop running server (if any)
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "Stopping BRouter (pid $(cat "$PIDFILE"))"
  kill "$(cat "$PIDFILE")"
  for i in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 "$(cat "$PIDFILE")" 2>/dev/null || break
    sleep 1
  done
  kill -9 "$(cat "$PIDFILE")" 2>/dev/null || true
fi
rm -f "$PIDFILE"

# start server in background
echo "Starting BRouter"
nohup sh "$ROOT/misc/scripts/server.sh" >> "$LOGFILE" 2>&1 &
echo $! > "$PIDFILE"
echo "Started (pid $(cat "$PIDFILE")), log: $LOGFILE"

