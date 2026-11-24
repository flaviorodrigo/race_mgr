#!/bin/bash
# Entrypoint for an AMS2 dedicated server container (Wine), with a restart Race Manager can ask for.
#
# Same as a plain "exec wine DedicatedServerCmd.exe", plus: the server runs as a child of this script, which
# watches for the file .racemanager-restart in the server folder (the folder Race Manager shares). When Race Manager
# writes it, the server is stopped and started again - a real restart of the process, like a container restart but
# quicker. If the file contains "reset-rotation", the rotation starts again from its first round.
# No network port is opened: the shared folder is the only channel.
set -u

SERVER_DIR="${SERVER_DIR:-/home/steam/ams2_server}"
SERVER_CMD="${SERVER_CMD:-wine DedicatedServerCmd.exe}"
SIGNAL="$SERVER_DIR/.racemanager-restart"
STOP_GRACE_SECONDS="${STOP_GRACE_SECONDS:-15}"
export WINEDEBUG=-all
export XDG_RUNTIME_DIR="/tmp/runtime-steam"

mkdir -p "$SERVER_DIR" "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

if [ ! -f "$SERVER_DIR/DedicatedServerCmd.exe" ] && [ -z "${SKIP_DOWNLOAD:-}" ]; then
  echo "Downloading Automobilista 2 Dedicated Server..."
  /home/steam/steamcmd/steamcmd.sh \
    +force_install_dir "$SERVER_DIR" \
    +login anonymous \
    +@sSteamCmdForcePlatformType windows \
    +app_update 1338040 validate \
    +quit
fi

cd "$SERVER_DIR"

if [ ! -d "/home/steam/.wine" ] && [ -z "${SKIP_WINE_INIT:-}" ]; then
  echo "Initializing Wine prefix..."
  wineboot --init
  wineserver -w
fi

pid=""

# Stops the server: politely first, then by force, and makes sure no Wine process of it is left over
stop_server() {
  [ -n "$pid" ] || return 0
  kill -TERM "$pid" 2>/dev/null
  for _ in $(seq 1 "$STOP_GRACE_SECONDS"); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  kill -KILL "$pid" 2>/dev/null
  command -v wineserver > /dev/null 2>&1 && wineserver -k 2>/dev/null
  wait "$pid" 2>/dev/null
  pid=""
}

# docker stop / restart: stop the server and leave
trap 'echo "Stopping the server..."; stop_server; exit 0' TERM INT

rm -f "$SIGNAL"
while true; do
  echo "Starting Automobilista 2 Dedicated Server..."
  $SERVER_CMD &
  pid=$!

  while kill -0 "$pid" 2>/dev/null; do
    if [ -f "$SIGNAL" ]; then
      request="$(cat "$SIGNAL" 2>/dev/null)"
      rm -f "$SIGNAL"
      echo "Race Manager asked for a restart${request:+ ($request)}"
      stop_server
      if [[ "$request" == *reset-rotation* ]]; then
        rm -f "$SERVER_DIR/lua_config/sms_rotate_data.json"
        echo "The rotation starts again from its first round"
      fi
      continue 2
    fi
    sleep 2
  done

  # The server ended by itself: leave with its exit code, so Docker's restart policy can take over
  wait "$pid"
  code=$?
  echo "The server stopped (exit code $code)"
  exit "$code"
done
