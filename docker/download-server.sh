#!/bin/bash
# Downloads/updates the AMS2 dedicated server (Steam app 1338040) into ./ams2-server using SteamCMD in a throwaway
# container. SteamCMD logs in anonymously, which is enough for the dedicated server. If that ever stops working,
# pass a Steam user name: SteamCMD then asks for the password (and Steam Guard code) itself; nothing is stored.
#
#   ./docker/download-server.sh [steam-username]
set -euo pipefail

STEAM_USERNAME="${1:-anonymous}"

cd "$(dirname "$0")/.."
mkdir -p ams2-server

docker run -it --rm \
  -v "$PWD/ams2-server:/server" \
  steamcmd/steamcmd:latest \
  +force_install_dir /server \
  +login "$STEAM_USERNAME" \
  +@sSteamCmdForcePlatformType windows \
  +app_update 1338040 validate \
  +quit || true

if [ ! -f ams2-server/DedicatedServerCmd.exe ]; then
  echo "The download failed: see the SteamCMD output above. (A first attempt sometimes fails with 'Missing configuration': run it again.)" >&2
  exit 1
fi

# SteamCMD runs as root; hand the files to the container's user (UID 1000)
docker run --rm -v "$PWD/ams2-server:/server" alpine chown -R 1000:1000 /server

if [ ! -f ams2-server/server.cfg ]; then
  cp sample_files/server.cfg ams2-server/server.cfg
  echo "Copied sample_files/server.cfg to ams2-server/server.cfg - edit the name/password before racing."
fi
echo "Done."
