#!/bin/bash
# CI check of the NAS setup with the real external-server image: Race Manager runs as a Synology-like user
# (1029:100), the game server folder is mounted, and server.cfg is a separate single-file mount (as on a NAS
# where the game server's compose file mounts it on its own). It signs in and saves settings through the API, and
# checks the files changed on the "host".
#   docker/ci/nas-smoke.sh <image>
set -euo pipefail
image="$1"
work="$(mktemp -d)"
port=18081
cleanup() {
  docker logs smoke-rm 2>&1 | tail -40 || true
  docker rm -f smoke-rm smoke-mongo >/dev/null 2>&1 || true
  sudo rm -rf "$work"
}
trap cleanup EXIT

# The game server's files as the NAS has them: the real server.cfg outside data/, an empty placeholder inside
mkdir -p "$work/ams2/data/lua_config"
cp sample_files/server.cfg "$work/ams2/server.cfg"
: > "$work/ams2/data/server.cfg"
printf '// sample blacklist\n[\n]\n' > "$work/ams2/data/blacklist.cfg"
sudo chown -R 1029:100 "$work/ams2"
inode_before="$(stat -c %i "$work/ams2/server.cfg")"

docker run -d --name smoke-mongo -p 127.0.0.1:27117:27017 mongo:7.0 >/dev/null
password="smoke-$(openssl rand -hex 8)"
docker run -d --name smoke-rm --network host --user 1029:100 \
  -e RM_PORT=$port -e RM_ADMIN_USER=admin -e RM_ADMIN_PASSWORD="$password" -e RM_JWT_SECRET="$(openssl rand -hex 32)" \
  -e SPRING_MONGODB_URI=mongodb://127.0.0.1:27117/racemgr \
  -e RM_GAMESERVER_HOST=127.0.0.1 -e RM_GAMESERVER_PORT=19999 \
  -v "$work/ams2/data:/data/ams2-server" \
  -v "$work/ams2/server.cfg:/data/ams2-server/server.cfg" \
  "$image" >/dev/null

for _ in $(seq 1 60); do curl -fs -o /dev/null "http://127.0.0.1:$port/api" && break; sleep 2; done
base="http://127.0.0.1:$port"
jar="$work/cookies"
curl -fs -c "$jar" -o /dev/null "$base/api"
xsrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar" | tail -1; }
call() { curl -fs -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' "$@"; }

call -X POST "$base/api/v1/authenticate" -d "{\"username\":\"admin\",\"password\":\"$password\"}" >/dev/null
echo "signed in"

# The settings form reads the real server.cfg through the single-file mount, and saves it in place
call "$base/api/v1/admin/gameserver/config" | grep -q '"name"' || { echo "server.cfg was not read"; exit 1; }
call -X PUT "$base/api/v1/admin/gameserver/config" -d '{"name":"Smoke Test Server"}' >/dev/null
sudo grep -q '"Smoke Test Server"' "$work/ams2/server.cfg" || { echo "server.cfg on the host did not change"; exit 1; }
[ "$(stat -c %i "$work/ams2/server.cfg")" = "$inode_before" ] || { echo "server.cfg was replaced instead of edited"; exit 1; }
[ ! -s "$work/ams2/data/server.cfg" ] || { echo "the placeholder was written instead"; exit 1; }
echo "server.cfg edited in place"

# Files in the folder are written as the folder's owner
call -X POST "$base/api/v1/admin/gameserver/banlist" -d '{"steamId":"76561198000000005","note":"smoke"}' >/dev/null
sudo grep -q '76561198000000005 // smoke' "$work/ams2/data/blacklist.cfg" || { echo "the ban list did not change"; exit 1; }
[ "$(stat -c %u:%g "$work/ams2/data/blacklist.cfg")" = "1029:100" ] || { echo "the ban list has the wrong owner"; exit 1; }
echo "ban list saved, owner 1029:100"
echo "NAS smoke test passed"
