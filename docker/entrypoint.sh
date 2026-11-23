#!/bin/bash
set -e

# Clear stale Wine state left by an unclean stop
rm -rf "${WINEPREFIX}"/*.lock /tmp/.wine-* 2>/dev/null || true
wineserver -k 2>/dev/null || true

# Image built with the game server inside (target with-server): put its files into the mounted folder
if [ -n "${RM_SEED_SERVER_FROM:-}" ]; then
  /app/seed-server.sh
fi

if [ ! -f "${RM_PATH_TO_DEDICATED_SERVER}/DedicatedServerCmd.exe" ]; then
  echo "ERROR: ${RM_PATH_TO_DEDICATED_SERVER}/DedicatedServerCmd.exe not found." >&2
  echo "Download the server files first (./docker/download-server.sh) or build the image with them (./docker/build-with-server.sh)." >&2
  exit 1
fi

# The game server's HTTP API must stay on the container's loopback (it is never published)
if grep -Eq '^\s*httpApiInterface\s*:\s*""' "${RM_PATH_TO_DEDICATED_SERVER}/server.cfg" 2>/dev/null; then
  echo 'WARNING: server.cfg has httpApiInterface "" (all interfaces). Use "127.0.0.1" unless you know you need otherwise.' >&2
fi

exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=60} -jar /app/racemgr.jar
