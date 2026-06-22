CREATE TABLE IF NOT EXISTS medications (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL,
  name            TEXT NOT NULL,
  rxcui           TEXT,
  dosage          TEXT,
  schedule_type   TEXT NOT NULL CHECK (schedule_type IN ('daily', 'interval')),
  schedule_times  TEXT NOT NULL,
  active          INTEGER NOT NULL DEFAULT 1,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  sync_status     TEXT NOT NULL DEFAULT 'pending' CHECK (sync_status IN ('pending','synced','error'))
);
CREATE INDEX IF NOT EXISTS idx_medications_user ON medications(user_id);

CREATE TABLE IF NOT EXISTS dose_logs (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL,
  medication_id   TEXT NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
  scheduled_time  INTEGER NOT NULL,
  taken_at        INTEGER,
  status          TEXT NOT NULL CHECK (status IN ('pending','taken','skipped','missed')),
  notes           TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  sync_status     TEXT NOT NULL DEFAULT 'pending'
);
CREATE INDEX IF NOT EXISTS idx_dose_logs_medication ON dose_logs(medication_id);
CREATE INDEX IF NOT EXISTS idx_dose_logs_user_time ON dose_logs(user_id, scheduled_time);

CREATE TABLE IF NOT EXISTS sync_queue (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type     TEXT NOT NULL CHECK (entity_type IN ('medication','dose_log')),
  entity_id       TEXT NOT NULL,
  operation       TEXT NOT NULL CHECK (operation IN ('create','update','delete')),
  payload         TEXT NOT NULL,
  attempts        INTEGER NOT NULL DEFAULT 0,
  last_error      TEXT,
  created_at      INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sync_queue_created ON sync_queue(created_at);
