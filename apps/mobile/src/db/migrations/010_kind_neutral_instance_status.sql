-- ADR 0001: a Reminder Instance's stored vocabulary becomes Kind-neutral.
-- The status 'taken' becomes 'completed', and the taken_at column becomes
-- completed_at.
--
-- Both Kinds share this status list and a task is never "taken", so keeping the
-- medication spelling would push a Kind check into every surface that displays
-- a status. A medication is still SHOWN as taken; only storage changes.
--
-- The table is rebuilt rather than altered. SQLite can rename a column in place
-- but cannot alter a CHECK constraint, and this migration needs both -- the
-- constraint names the very value being renamed. Renaming without the rebuild
-- leaves a table whose CHECK forbids the value its own rows now hold.
--
-- NOTE: no backticks anywhere in this file. It is inlined verbatim into a
-- JavaScript template literal in src/db/index.ts, and a backtick there closes
-- the template mid-SQL. The migration verifier's Part 0 catches the mismatch,
-- but the file stops compiling first.
--
-- Order matters: create the new table, copy with the value translated, drop the
-- old, rename in. reminder_instances is referenced by no foreign key (it points
-- AT reminders, not the other way), so nothing cascades.

DROP TABLE IF EXISTS reminder_instances_010;

CREATE TABLE reminder_instances_010 (
  id              TEXT PRIMARY KEY,
  reminder_id     TEXT NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,
  user_id         TEXT NOT NULL,
  -- Denormalized from reminders.kind so the missed-instance sweep and the
  -- adherence calculator can filter medication rows without a join. The C#
  -- schema carries the same denormalization.
  reminder_kind   TEXT NOT NULL CHECK (reminder_kind IN ('medication', 'task')),
  -- The instant this instance occupied on the timeline (ADR 0002).
  scheduled_time  INTEGER NOT NULL,
  -- 'snoozed' is distinct from 'skipped' and 'missed': a snooze is not a
  -- miss, and adherence counts them differently. 'completed' is what a
  -- medication-only model called 'taken' (ADR 0001).
  status          TEXT NOT NULL
                    CHECK (status IN ('pending', 'completed', 'snoozed', 'skipped', 'missed')),
  completed_at    INTEGER,
  snoozed_until   INTEGER,
  snooze_count    INTEGER NOT NULL DEFAULT 0,
  notes           TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  sync_status     TEXT NOT NULL DEFAULT 'pending'
                    CHECK (sync_status IN ('pending', 'synced', 'error')),
  convex_id       TEXT
);

-- The one value translation. Every other status carries across unchanged.
INSERT INTO reminder_instances_010 (
  id, reminder_id, user_id, reminder_kind, scheduled_time,
  status, completed_at, snoozed_until, snooze_count, notes,
  created_at, updated_at, sync_status, convex_id
)
SELECT
  id, reminder_id, user_id, reminder_kind, scheduled_time,
  CASE status WHEN 'taken' THEN 'completed' ELSE status END,
  taken_at, snoozed_until, snooze_count, notes,
  created_at, updated_at, sync_status, convex_id
FROM reminder_instances;

DROP TABLE reminder_instances;
ALTER TABLE reminder_instances_010 RENAME TO reminder_instances;

-- Indexes do not survive a table drop, so all five are recreated: the four
-- 004 declared, plus the sync_status index 008 added. The partial index still keys on 'pending', which
-- the rename did not touch.
CREATE INDEX IF NOT EXISTS idx_reminder_instances_reminder
  ON reminder_instances(reminder_id);
CREATE INDEX IF NOT EXISTS idx_reminder_instances_user_scheduled
  ON reminder_instances(user_id, scheduled_time);
CREATE INDEX IF NOT EXISTS idx_reminder_instances_convex_id
  ON reminder_instances(convex_id);
CREATE INDEX IF NOT EXISTS idx_reminder_instances_pending
  ON reminder_instances(user_id, scheduled_time)
  WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_reminder_instances_sync_status
  ON reminder_instances(sync_status);

-- The queue carries the OLD vocabulary too, and nothing else would fix it.
--
-- sync_queue.payload is a JSON snapshot frozen at enqueue time; the row it
-- describes has already moved on. A device that upgrades with an un-drained
-- queue -- offline when a dose was logged, which is the case the queue exists
-- for -- still holds {"status":"taken","takenAt":...}. On the next drain the
-- worker finds no 'taken' arm and dead-letters the entry after five attempts,
-- so a dose the user recorded never reaches the server. Silent, and it looks
-- like a sync fault rather than a rename.
--
-- Only reminder_instance entries carry a status. json_valid guards a payload
-- some earlier failure left unparseable: json_set on invalid JSON errors and
-- would abort the whole migration.
UPDATE sync_queue
SET payload = json_remove(
      json_set(
        json_set(
          payload,
          '$.status',
          CASE json_extract(payload, '$.status')
            WHEN 'taken' THEN 'completed'
            ELSE json_extract(payload, '$.status')
          END
        ),
        '$.completedAt',
        json_extract(payload, '$.takenAt')
      ),
      '$.takenAt'
    )
WHERE entity_type = 'reminder_instance'
  AND json_valid(payload)
  AND json_type(payload, '$.status') IS NOT NULL;
