DROP TABLE IF EXISTS dose_logs;
DROP TABLE IF EXISTS medications;

DELETE FROM sync_queue WHERE entity_type IN ('medication', 'dose_log');

DROP TABLE IF EXISTS sync_queue_009;

CREATE TABLE sync_queue_009 (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type     TEXT NOT NULL CHECK (entity_type IN ('reminder','reminder_instance')),
  entity_id       TEXT NOT NULL,
  operation       TEXT NOT NULL CHECK (operation IN ('create','update','delete')),
  payload         TEXT NOT NULL,
  attempts        INTEGER NOT NULL DEFAULT 0,
  last_error      TEXT,
  created_at      INTEGER NOT NULL,
  dead_letter     INTEGER NOT NULL DEFAULT 0
);

INSERT INTO sqlite_sequence (name, seq)
SELECT 'sync_queue_009', seq FROM sqlite_sequence WHERE name = 'sync_queue';

INSERT INTO sync_queue_009 (
  id, entity_type, entity_id, operation, payload,
  attempts, last_error, created_at, dead_letter
)
SELECT
  id, entity_type, entity_id, operation, payload,
  attempts, last_error, created_at, dead_letter
FROM sync_queue;

DROP TABLE sync_queue;
ALTER TABLE sync_queue_009 RENAME TO sync_queue;

CREATE INDEX IF NOT EXISTS idx_sync_queue_created ON sync_queue(created_at);
CREATE INDEX IF NOT EXISTS idx_sync_queue_dead_letter ON sync_queue(dead_letter);
CREATE INDEX IF NOT EXISTS idx_sync_queue_entity
  ON sync_queue(entity_type, entity_id, dead_letter);
