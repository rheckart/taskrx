-- Migration 003: dead-letter state for the sync queue.
--
-- Prior to this migration, syncQueueRepo.pending() returned every row in
-- the queue with no attempts filter. The worker treated MAX_ATTEMPTS = 5
-- as terminal for the SOURCE row (marking sync_status = 'error') but left
-- the queue entry, so every subsequent drain re-processed it — pinning
-- the sync-status badge to 'error' forever and re-incurring backoff.
--
-- This adds an explicit dead-letter flag:
--   * 0 (default) — normal pending entry, returned by pending().
--   * 1           — terminal failure, NOT returned by pending(), still
--                   readable via deadLettered() for inspection and can
--                   be re-armed via requeue().
--
-- We NEVER delete dead-lettered rows automatically: a dropped medication
-- dose log is unacceptable data loss. The row must remain inspectable.
ALTER TABLE sync_queue ADD COLUMN dead_letter INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_sync_queue_dead_letter ON sync_queue(dead_letter);
