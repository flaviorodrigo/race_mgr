#!/bin/sh
# Backs up Race Manager's database from the compose stack to a compressed archive, then keeps only the newest N
# backups. Run it from anywhere (a cron job works well):
#
#   docker/backup-mongo.sh [backup-folder] [how-many-to-keep]     (defaults: ./backups and 14)
#
# Another compose file (docker/docker-compose.external-server.yml): export COMPOSE_FILE=... first.
# Restore with docker/restore-mongo.sh.
set -eu
cd "$(dirname "$0")/.."

dir=${1:-./backups}
keep=${2:-14}
case $keep in
  ''|*[!0-9]*) echo "How many to keep must be a number" >&2; exit 2 ;;
esac

mkdir -p "$dir"
file="$dir/racemgr-$(date +%Y%m%d-%H%M%S).archive.gz"
trap 'rm -f "$file.partial"' EXIT

# Connects as Race Manager's own database user, whose password is already inside the container
docker compose exec -T mongo sh -c 'mongodump --quiet --archive --gzip --db racemgr -u "$RM_MONGO_USER" -p "$RM_MONGO_PASS" --authenticationDatabase admin' > "$file.partial"
if [ ! -s "$file.partial" ]; then
  echo "The backup came out empty: is the mongo service running?" >&2
  exit 1
fi
mv "$file.partial" "$file"

# Prune: everything after the newest $keep
ls -1t "$dir"/racemgr-*.archive.gz | tail -n +$((keep + 1)) | while read -r old; do
  rm -f "$old"
done
echo "$file"
