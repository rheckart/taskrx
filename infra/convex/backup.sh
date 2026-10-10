#!/usr/bin/env bash
# pg_dump the Convex database to infra/convex/backups/. File storage (the `data`
# volume) is not covered; this app stores none.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$HERE"
set -a; . ./.env; set +a
DB="${INSTANCE_NAME_DB:-taskrx}"
mkdir -p backups; chmod 700 backups
OUT="backups/${DB}-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T postgres pg_dump -U convex -d "$DB" -Fc > "$OUT"
[ -s "$OUT" ] || { rm -f "$OUT"; echo "empty dump" >&2; exit 1; }
echo "$OUT"
