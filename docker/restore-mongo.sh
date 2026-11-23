#!/bin/sh
# Restores a backup made by docker/backup-mongo.sh. It REPLACES the races, sessions, rotations and settings now in
# the database with what the backup holds. Stop Race Manager first (docker compose stop racemgr), start it after.
#
#   docker/restore-mongo.sh backups/racemgr-20260101-030000.archive.gz
set -eu
cd "$(dirname "$0")/.."

if [ $# -ne 1 ] || [ ! -s "$1" ]; then
  echo "Usage: docker/restore-mongo.sh <backup file>" >&2
  exit 2
fi

printf 'Replace the current data with %s? Type yes to continue: ' "$1"
read -r answer
[ "$answer" = "yes" ] || { echo "Cancelled."; exit 1; }

docker compose exec -T mongo sh -c 'mongorestore --quiet --archive --gzip --drop --nsInclude "racemgr.*" -u "$RM_MONGO_USER" -p "$RM_MONGO_PASS" --authenticationDatabase admin' < "$1"
echo "Restored. Start Race Manager again: docker compose start racemgr"
