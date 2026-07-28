-- Migration 006: widen `sync_queue.entity_type` for the unified entities.
--
-- `001_init.sql` pinned the queue to the POC domain:
--
--   entity_type TEXT NOT NULL CHECK (entity_type IN ('medication','dose_log'))
--
-- so a `reminders` or `reminder_instances` write had nowhere to go. That is why
-- `repos/reminders.ts` and `repos/reminderInstances.ts` shipped with no enqueue
-- at all: an INSERT with entity_type='reminder' raises a CHECK failure, and the
-- repositories chose a local-only write over a runtime throw. The cost was that
-- every reminder create/update/delete after the 004/005 cutover would never
-- reach Convex. This migration removes that cost; the repositories enqueue
-- from here on.
--
-- SQLite cannot ALTER a CHECK constraint, so this is the 12-step table rebuild
-- from https://sqlite.org/lang_altertable.html#otheralter. Rebuilding
-- `sync_queue` is acceptable in a way that rebuilding `medications`,
-- `dose_logs`, `reminders`, or `reminder_instances` would not be: the queue is
-- a transient operation log, not dose history. Every pending row is still
-- copied across by value — a dropped queue row is a mutation that silently
-- never syncs.
--
-- FOREIGN KEYS: the reference procedure opens with `PRAGMA foreign_keys=OFF`,
-- which this migration deliberately does not emit. Two reasons. First, the
-- pragma is a no-op inside a transaction, and `runMigrations` in
-- `../index.ts` wraps every migration in `withTransactionAsync` — emitting it
-- would read as protection that is not there. Second, it is not needed:
-- `sync_queue` declares no foreign keys and no other table references it, so
-- neither the DROP nor the RENAME can touch a constraint. The only FK in the
-- schema is `reminder_instances.reminder_id -> reminders.id`, and neither
-- table is named here.
--
-- IDEMPOTENCY: pure SQL has no conditional DDL, so "rebuild only if the CHECK
-- is still narrow" is not expressible in a `.sql` file. Instead the rebuild is
-- written to be idempotent by construction, and re-running it is a no-op in
-- terms of observable state:
--
--   * `DROP TABLE IF EXISTS sync_queue_006` first, rather than
--     `CREATE TABLE IF NOT EXISTS`. A leftover scratch table from an aborted
--     run would otherwise be reused and the copy would collide on the primary
--     key; this way the scratch table is always fresh and empty.
--   * The copy always reads whatever `sync_queue` currently is, narrow CHECK
--     or wide, and reproduces it row for row.
--   * The scratch name never survives the migration, so a second run starts
--     from exactly the same state as the first.
--
-- The runner's `_migrations` guard makes a second run unlikely in the first
-- place, and the transaction makes a half-applied rebuild impossible, but the
-- above holds even with both bypassed. `verify-reminder-migrations.mjs` Part F
-- asserts it by value.

DROP TABLE IF EXISTS sync_queue_006;

-- Column set, order, types, and defaults are `001_init.sql`'s `sync_queue`
-- plus the `dead_letter` column that `003_sync_queue_dead_letter.sql` appended
-- — that ALTER is folded in here because a rebuild has to reproduce the table
-- as it stands today, not as 001 left it. The `operation` CHECK is copied
-- verbatim: this migration widens `entity_type` only, and a queue row with a
-- bogus operation must still be rejected.
--
-- The two new values match `SyncEntityType` in `../repos/syncQueue.ts` and the
-- snake_case naming of every other enum in this schema.
CREATE TABLE sync_queue_006 (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type     TEXT NOT NULL CHECK (entity_type IN ('medication','dose_log','reminder','reminder_instance')),
  entity_id       TEXT NOT NULL,
  operation       TEXT NOT NULL CHECK (operation IN ('create','update','delete')),
  payload         TEXT NOT NULL,
  attempts        INTEGER NOT NULL DEFAULT 0,
  last_error      TEXT,
  created_at      INTEGER NOT NULL,
  dead_letter     INTEGER NOT NULL DEFAULT 0
);

-- Carry the AUTOINCREMENT high-water mark across. Copying the rows alone would
-- only restore `max(id)`; on a queue that has drained, `max(id)` is far below
-- the real watermark (or the table is empty and the counter would restart at
-- 1). AUTOINCREMENT's contract is that an id is never reused, and the sync
-- worker holds queue ids in memory across awaits while it drains, so the
-- watermark is worth preserving rather than resetting.
--
-- `sqlite_sequence` exists from 001 onward (SQLite creates it with the first
-- AUTOINCREMENT table). It has no unique index on `name`, so this must not be
-- an upsert; the preceding DROP guarantees there is no `sync_queue_006` row to
-- collide with. If `sync_queue` never took an insert it has no row here and
-- this SELECT is empty, which is the correct outcome.
INSERT INTO sqlite_sequence (name, seq)
SELECT 'sync_queue_006', seq FROM sqlite_sequence WHERE name = 'sync_queue';

-- Every pending row, by value, columns named explicitly rather than `SELECT *`
-- so a future column addition fails loudly here instead of shifting values one
-- column to the left. Rows that violate the new CHECK cannot exist: the old
-- CHECK was strictly narrower.
INSERT INTO sync_queue_006 (
  id, entity_type, entity_id, operation, payload,
  attempts, last_error, created_at, dead_letter
)
SELECT
  id, entity_type, entity_id, operation, payload,
  attempts, last_error, created_at, dead_letter
FROM sync_queue;

DROP TABLE sync_queue;
ALTER TABLE sync_queue_006 RENAME TO sync_queue;

-- The DROP took `idx_sync_queue_created` (001) and `idx_sync_queue_dead_letter`
-- (003) with it. Recreated after the RENAME so they carry their original names
-- rather than a name derived from the scratch table.
CREATE INDEX IF NOT EXISTS idx_sync_queue_created ON sync_queue(created_at);
CREATE INDEX IF NOT EXISTS idx_sync_queue_dead_letter ON sync_queue(dead_letter);
