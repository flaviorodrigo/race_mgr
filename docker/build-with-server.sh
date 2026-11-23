#!/bin/bash
# Builds the Race Manager image with the AMS2 dedicated server downloaded into it, and starts the stack.
# Asks for your Steam password (and Steam Guard code, if your account has one) without echoing them; they are
# given to the build as secrets and are not stored in the image. Needs a Steam account that owns the game.
#
#   ./docker/build-with-server.sh <steam-username> [build | up]     (default: up, which builds then starts)
#
# To update the game server later, run it again with RM_REFRESH_SERVER=true in front.
set -euo pipefail

STEAM_USERNAME="${1:-}"
if [ -z "$STEAM_USERNAME" ]; then
  echo "Usage: $0 <steam-username> [build | up]" >&2
  exit 1
fi
action="${2:-up}"

cd "$(dirname "$0")/.."
if [ ! -f .env ]; then
  echo "Create .env first: cp .env.example .env, then fill it in." >&2
  exit 1
fi

read -r -s -p "Steam password for $STEAM_USERNAME: " STEAM_PASSWORD; echo
read -r -s -p "Steam Guard code (empty if your account has none): " STEAM_GUARD_CODE; echo
export STEAM_USERNAME STEAM_PASSWORD STEAM_GUARD_CODE

files=(-f docker-compose.yml -f docker/docker-compose.with-server.yml)
case "$action" in
  build) docker compose "${files[@]}" build ;;
  up) docker compose "${files[@]}" up -d --build ;;
  *) echo "Unknown action: $action" >&2; exit 1 ;;
esac
