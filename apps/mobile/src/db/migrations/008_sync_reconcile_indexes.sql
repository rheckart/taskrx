-- Migration 008: indexes for the sync reconciliation pass.
--
-- `reconcilePending()` in `../repos/reminders.ts` runs at the head of every
-- sync drain, and a drain fires on every NetInfo connectivity change — a walk
-- past a dead spot is several drains. It looks for rows the queue lost:
--
--   SELECT r.id FROM reminders r
--   WHERE r.sync_status = 'pending'
--     AND NOT EXISTS (
--       SELECT 1 FROM sync_queue q
--       WHERE q.entity_type = 'reminder' AND q.entity_id = r.id
--         AND q.dead_letter = 0
--     )
--
-- Neither side of that had an index. `004_unified_reminders.sql` indexes
-- `reminders` on (user_id, active), (user_id, kind), and (convex_id);
-- `006_widen_sync_queue.sql` indexes `sync_queue` on (created_at) and
-- (dead_letter). None of them is usable here, so the plan was a full scan of
-- `reminders` driving a correlated full scan of `sync_queue` per pending row —
-- O(pending × queue) on the main thread, on every connectivity change. The
-- reconcile pass is meant to be a cheap no-op in the common case where the
-- queue already holds everything; without these two indexes it was the most
-- expensive thing the drain did.
--
-- ## `sync_queue(entity_type, entity_id, dead_letter)`
--
-- All three predicates in the subquery are equalities, so SQLite can consume
-- all three index columns as a single seek and the correlated scan collapses
-- to a lookup. The column order is chosen, not incidental:
--
--   * `entity_type, entity_id` leads because that prefix is the natural
--     "queue rows for this entity" key, usable by lookups that do not
--     constrain `dead_letter` (the instance-side reconcile, and any future
--     de-dup check before an enqueue). Leading with `dead_letter` would give
--     the same seek here and nothing else.
--   * `dead_letter` still belongs in the index rather than being left to a
--     row fetch: with it present every column the subquery names is in the
--     index, so `EXISTS` is answered index-only and never touches the table.
--   * `idx_sync_queue_dead_letter` (006) already covers dead_letter-only
--     scans, so there is no reason to duplicate that leading column.
--
-- ## `reminders(sync_status)` and `reminder_instances(sync_status)`
--
-- Plain rather than partial (`... WHERE sync_status = 'pending'`), unlike
-- `idx_reminder_instances_pending` in 004. That one is partial because
-- taken/missed instances accumulate forever while pending ones do not, so the
-- restriction buys a permanently small index. Here the column is a three-value
-- enum on a table that is bounded by how many reminders a person actually
-- keeps, the index is one entry per row either way at that scale, and staying
-- unrestricted also serves the `sync_status = 'error'` lookups that a retry or
-- diagnostics path would want. `markSynced`/`markError` pay one index update
-- per write, which is the cost of not scanning the table on every drain.
--
-- `reminder_instances` gets the same index as `reminders` because the
-- instance-side reconcile lands with the identical shape — same
-- `sync_status = 'pending'` driver, same `NOT EXISTS` against `sync_queue`
-- with entity_type='reminder_instance'. Splitting it into a later migration
-- would leave that path full-scanning for exactly as long as it took to write
-- the follow-up.
--
-- IDEMPOTENCY: `CREATE INDEX IF NOT EXISTS` is the whole migration. There is
-- no DDL here that a second run could observe, and no data is read or written.

CREATE INDEX IF NOT EXISTS idx_sync_queue_entity
  ON sync_queue(entity_type, entity_id, dead_letter);

CREATE INDEX IF NOT EXISTS idx_reminders_sync_status
  ON reminders(sync_status);

CREATE INDEX IF NOT EXISTS idx_reminder_instances_sync_status
  ON reminder_instances(sync_status);
