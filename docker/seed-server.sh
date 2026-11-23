#!/bin/bash
# Used by images built with the game server inside (target with-server). The mounted game server folder is where
# Race Manager and the game server read and write their files, so the copy in the image is put there:
#   - first start (no DedicatedServerCmd.exe yet): everything is copied, and server.cfg comes from the sample
#   - RM_REFRESH_SERVER=true (after rebuilding the image to get a newer game server): the game files are replaced,
#     but your settings are kept: server.cfg, ban list, whitelist, balance-of-performance files, lua_config/ (the
#     stats addons' data) and the rotation file Race Manager wrote
#   - any other start: nothing is touched
set -euo pipefail

src="${RM_SEED_SERVER_FROM:?}"
dest="${RM_PATH_TO_DEDICATED_SERVER:?}"
sample="${RM_SEED_SERVER_SAMPLE_CFG:-}"
mkdir -p "$dest"

if [ ! -f "$dest/DedicatedServerCmd.exe" ]; then
  echo "Copying the game server from the image into $dest ..."
  cp -a "$src/." "$dest/"
elif [ "${RM_REFRESH_SERVER:-}" = "true" ]; then
  echo "Replacing the game server files in $dest with the ones in the image (your settings are kept) ..."
  tar -C "$src" \
    --exclude=./server.cfg --exclude=./server.cfg.bak \
    --exclude=./blacklist.cfg --exclude=./whitelist.cfg \
    --exclude='./driver_*.json' --exclude='./driver_*.json.bak' \
    --exclude=./lua_config \
    --exclude=./lua/sms_rotate/sms_rotate_default_config.json \
    -cf - . | tar -C "$dest" -xf -
else
  exit 0
fi

if [ ! -f "$dest/server.cfg" ] && [ -n "$sample" ] && [ -f "$sample" ]; then
  cp "$sample" "$dest/server.cfg"
  echo "Created $dest/server.cfg from the sample: edit the name and password before racing."
fi
