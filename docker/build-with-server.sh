#!/bin/bash
# Builds the Race Manager image with the AMS2 dedicated server downloaded into it, and starts the stack.
# SteamCMD logs in anonymously. If you give a Steam user name, it asks for the password (and Steam Guard code)
# without echoing them; they reach the build as secrets and are not stored in the image.
#
#   ./docker/build-with-server.sh [steam-username] [build | up]     (default: anonymous, up = build then start)
#
# To update the game server later, run it again with RM_REFRESH_SERVER=true in front.
set -euo pipefail

STEAM_USERNAME="${1:-anonymous}"
action="${2:-up}"

cd "$(dirname "$0")/.."
if [ ! -f .env ]; then
  echo "Create .env first: cp .env.example .env, then fill it in." >&2
  exit 1
fi

STEAM_PASSWORD=""
STEAM_GUARD_CODE=""
if [ "$STEAM_USERNAME" != anonymous ]; then
  read -r -s -p "Steam password for $STEAM_USERNAME: " STEAM_PASSWORD; echo
  read -r -s -p "Steam Guard code (empty if your account has none): " STEAM_GUARD_CODE; echo
fi
export STEAM_USERNAME STEAM_PASSWORD STEAM_GUARD_CODE

files=(-f docker-compose.yml -f docker/docker-compose.with-server.yml)
case "$action" in
  build) docker compose "${files[@]}" build ;;
  up) docker compose "${files[@]}" up -d --build ;;
  *) echo "Unknown action: $action" >&2; exit 1 ;;
esac
