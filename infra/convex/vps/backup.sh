#!/usr/bin/env bash
# Offsite backup of the TaskRx Convex database, run on the VPS (installed at /docker/taskrx/backup.sh).
#
#   backup.sh frequent        pg_dump offsite under taskrx/frequent/   (hourly)
#   backup.sh nightly         pg_dump offsite under taskrx/nightly/, then prune
#   backup.sh restore-check   last night's dump restored into a scratch database and counted
#
# It follows /docker/caballus/backup.sh on the same box and sends to the same S4 bucket, under its own
# `taskrx/` prefix. It reads that stack's backup.env for the bucket and keys, so it never touches that
# stack's files. Caballus's restore-check fails on any file in the bucket older than 31 days, so the prune
# here keeps 28: a prune that stalls for a few days must not trip that alert.
#
# A job that succeeds writes a stamp into backup-state/, after the upload or the check, never before.
# A failure leaves the old stamp to go stale, and `systemctl --failed` lists the unit.
#
# The dumps hold medication names and doses, and are not encrypted before they leave the box.
set -euo pipefail

MODE="${1:-}"
STACK="${TASKRX_STACK:-/docker/taskrx}"
STATE="$STACK/backup-state"
WORK="$STACK/backup-work"
ENV_FILE="${TASKRX_BACKUP_ENV:-/docker/caballus/backup.env}"
PG=caballus-postgres-1
DB=taskrx
SCRATCH_DB=taskrx_restore_check
RETENTION=28d
RCLONE_IMAGE=rclone/rclone:1.74.0

say() { printf '==> %s\n' "$*"; }
die() { printf 'taskrx backup: %s\n' "$*" >&2; exit 1; }

case "$MODE" in
  frequent | nightly | restore-check) ;;
  *) die "usage: backup.sh frequent|nightly|restore-check" ;;
esac

umask 077

[ -r "$ENV_FILE" ] || die "cannot read $ENV_FILE"
BUCKET=$(sed -nE 's/^CABALLUS_BACKUP_BUCKET=(.*)$/\1/p' "$ENV_FILE" | tail -n 1)
[ -n "$BUCKET" ] || die "CABALLUS_BACKUP_BUCKET is empty in $ENV_FILE"
REMOTE="s4:$BUCKET/taskrx"

mkdir -p "$WORK" "$STATE"
chmod 755 "$STATE"

# One job at a time: a restore check must not download a file the prune is deleting.
exec 9>"$STACK/backup.lock"
flock -w 900 9 || die "another backup job has held the lock for fifteen minutes"

rclone() {
  docker run --rm --env-file "$ENV_FILE" -e RCLONE_CONFIG=/dev/null -v "$WORK:/work" "$RCLONE_IMAGE" "$@"
}

psql_in() { docker exec -i "$PG" psql -U caballus -v ON_ERROR_STOP=1 -At "$@"; }

stamp() {
  date -u +%Y-%m-%dT%H:%M:%SZ > "$STATE/.$1.next"
  chmod 644 "$STATE/.$1.next"
  mv "$STATE/.$1.next" "$STATE/$1"
}

NOW=$(date -u +%Y%m%dT%H%M%SZ)

# A dump, proved readable before it leaves the box: `pg_restore --list` reads the whole table of
# contents, so a truncated file fails here rather than on the day it is needed.
dump() {
  local target="$1"
  docker exec "$PG" pg_dump -U caballus -d "$DB" --format=custom > "$target"
  [ -s "$target" ] || die "pg_dump wrote nothing"
  docker exec -i "$PG" pg_restore --list < "$target" > /dev/null || die "the dump does not read back, nothing was uploaded"
}

cleanup() { rm -f "$WORK"/*.dump; }
trap cleanup EXIT

case "$MODE" in
  frequent)
    dump "$WORK/$NOW.dump"
    rclone copyto "/work/$NOW.dump" "$REMOTE/frequent/$NOW.dump"
    stamp last-backup
    say "frequent/$NOW.dump is offsite"
    ;;

  nightly)
    dump "$WORK/$NOW.dump"
    rclone copyto "/work/$NOW.dump" "$REMOTE/nightly/$NOW.dump"
    stamp last-backup
    say "nightly/$NOW.dump is offsite"
    rclone delete --min-age "$RETENTION" "$REMOTE/frequent"
    rclone delete --min-age "$RETENTION" "$REMOTE/nightly"
    say "pruned everything older than $RETENTION"
    ;;

  restore-check)
    LATEST=$(rclone lsf --files-only --include '*.dump' "$REMOTE/nightly" | sort | tail -n 1)
    [ -n "$LATEST" ] || die "there is no nightly dump under $REMOTE/nightly"

    # An old file restoring cleanly proves the restore works and says nothing about whether backups do.
    TAKEN="${LATEST:0:4}-${LATEST:4:2}-${LATEST:6:2}T${LATEST:9:2}:${LATEST:11:2}:${LATEST:13:2}Z"
    AGE_HOURS=$(( ( $(date -u +%s) - $(date -u -d "$TAKEN" +%s) ) / 3600 ))
    [ "$AGE_HOURS" -le 26 ] || die "the newest nightly, $LATEST, is $AGE_HOURS hours old"

    rclone copyto "$REMOTE/nightly/$LATEST" "/work/$LATEST"

    drop_scratch() { psql_in -d postgres -c "drop database if exists $SCRATCH_DB" > /dev/null || true; }
    trap 'drop_scratch; cleanup' EXIT
    drop_scratch
    psql_in -d postgres -c "create database $SCRATCH_DB" > /dev/null
    docker exec -i "$PG" pg_restore -U caballus -d "$SCRATCH_DB" --no-owner --no-privileges --exit-on-error < "$WORK/$LATEST"

    # Convex keeps every document, of every table, in `documents`. A restore with none is a lost dump.
    # The counts are not compared further: Convex trims old document versions, so live can fall below
    # last night's count.
    LIVE=$(psql_in -d "$DB" -c "select count(*) from documents")
    RESTORED=$(psql_in -d "$SCRATCH_DB" -c "select count(*) from documents")
    say "documents: live $LIVE, restored $RESTORED"
    if [ "$LIVE" -gt 0 ] && [ "$RESTORED" -eq 0 ]; then
      die "documents has $LIVE rows live and none in the restore of $LATEST"
    fi

    stamp last-restore-check
    say "$LATEST restored and counted"
    ;;
esac
