#!/bin/bash
# CI check of docker/game-server/entrypoint.sh with a stand-in for the game server (no Wine, no Steam):
# a restart signal restarts it, "reset-rotation" also clears the rotation position, a server that ignores the polite
# stop is killed, docker stop stops it, and a server that ends by itself ends the container with its exit code.
# Runs inside a plain Linux container:  docker/ci/game-server-entrypoint-test.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
docker run --rm -v "$here/..:/d:ro" --entrypoint bash debian:stable-slim -c '
set -u
mkdir -p /srv/lua_config
export SERVER_DIR=/srv SERVER_CMD="bash /d/ci/fake-game-server.sh" SKIP_DOWNLOAD=1 SKIP_WINE_INIT=1 STOP_GRACE_SECONDS=2
fail() { echo "FAIL: $*"; exit 1; }
# Is a stand-in server still running? (the slim image has no pgrep)
running() {
  for f in /proc/[0-9]*/cmdline; do
    [ "$(tr "\0" " " 2>/dev/null < "$f")" = "bash /d/ci/fake-game-server.sh " ] && return 0
  done
  return 1
}

echo "{\"index\":3}" > /srv/lua_config/sms_rotate_data.json
bash /d/game-server/entrypoint.sh > /tmp/log1 2>&1 & ep=$!
sleep 3; echo restart > /srv/.racemanager-restart
sleep 5; echo reset-rotation > /srv/.racemanager-restart
sleep 5; kill -TERM $ep; wait $ep || fail "docker stop should end cleanly"
cat /tmp/log1
[ "$(grep -c "fake server .* up" /tmp/log1)" = 3 ] || fail "expected three starts"
[ ! -f /srv/lua_config/sms_rotate_data.json ] || fail "reset-rotation should clear the rotation position"
[ ! -f /srv/.racemanager-restart ] || fail "the signal should be removed"
! running || fail "a server is left running"

IGNORE_TERM=1 bash /d/game-server/entrypoint.sh > /tmp/log2 2>&1 & ep=$!
sleep 3; echo restart > /srv/.racemanager-restart; sleep 7; kill -TERM $ep; wait $ep || true
[ "$(grep -c "fake server .* up" /tmp/log2)" = 2 ] || fail "a server ignoring the stop should be killed and restarted"
! running || fail "a server is left running"

set +e; EXIT_AFTER=2 bash /d/game-server/entrypoint.sh > /tmp/log3 2>&1; code=$?; set -e
[ "$code" = 3 ] || fail "the server exit code should be the container exit code (got $code)"
echo "game server entrypoint: all checks passed"
'
