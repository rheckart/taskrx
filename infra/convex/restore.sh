#!/usr/bin/env bash
# Restore a dump over the Convex database. Stops the backend, drops and
# recreates the database, loads the dump, starts the backend.
# Usage: restore.sh backups/<file>.dump
set -euo pipefail
[ $# -eq 1 ] && [ -f "$1" ] || { echo "usage: $0 backups/<file>.dump" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
set -a; . ./.env; set +a
DB="${INSTANCE_NAME_DB:-taskrx}"
docker compose stop backend dashboard
docker compose exec -T postgres psql -U convex -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE)" -c "CREATE DATABASE \"$DB\""
docker compose exec -T postgres pg_restore -U convex -d "$DB" --no-owner < "$1"
docker compose up -d
