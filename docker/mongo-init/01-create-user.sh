#!/bin/bash
# Runs once, on first start of an empty data volume. Creates the least-privilege user Race Manager
# connects as (readWrite on the racemgr database only). The root user stays internal to this container.
set -euo pipefail
mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin admin <<JS
db.createUser({
  user: "$RM_MONGO_USER",
  pwd: "$RM_MONGO_PASS",
  roles: [ { role: "readWrite", db: "racemgr" } ]
});
JS
