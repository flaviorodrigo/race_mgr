#!/bin/bash
# Downloads/updates the AMS2 dedicated server (Steam app 1338040) into ./ams2-server using SteamCMD
# in a throwaway container. SteamCMD asks for your Steam password (and Steam Guard code) itself;
# nothing is stored by this script.
set -euo pipefail

STEAM_USERNAME="${1:-}"
if [ -z "$STEAM_USERNAME" ]; then
  echo "Usage: $0 <steam-username>" >&2
  exit 1
fi

cd "$(dirname "$0")/.."
mkdir -p ams2-server

docker run -it --rm \
  -v "$PWD/ams2-server:/server" \
  steamcmd/steamcmd:latest \
  +@sSteamCmdForcePlatformType windows \
  +force_install_dir /server \
  +login "$STEAM_USERNAME" \
  +app_update 1338040 validate \
  +quit

# SteamCMD runs as root; hand the files to the container's user (UID 1000)
docker run --rm -v "$PWD/ams2-server:/server" alpine chown -R 1000:1000 /server

if [ ! -f ams2-server/server.cfg ]; then
  cp sample_files/server.cfg ams2-server/server.cfg
  echo "Copied sample_files/server.cfg to ams2-server/server.cfg - edit the name/password before racing."
fi
echo "Done."
