-- Migration 004: the unified reminder tables.
--
-- The POC schema in `001_init.sql` can only express medications: a
-- `medications` row has no `kind`, no priority, no tags, no caregivers, and
-- its `schedule_type` CHECK admits only 'daily' and 'interval'. The unified
-- domain in `packages/shared/src/domain/reminder.ts` needs all of those, plus
-- a third 'weekly' schedule variant.
--
-- This migration WIDENS rather than replaces. It creates `reminders` and
-- `reminder_instances` alongside `medications` and `dose_logs`; migration 005
-- copies the existing rows across. `medications` and `dose_logs` are
-- deliberately left in place — they are still read by the sync worker
-- (`apps/mobile/src/sync/worker.ts`) and they are the rollback path if the
-- cutover has to be reverted on a device that already holds dose history.
--
-- Column names are snake_case, matching `001_init.sql`; the repositories
-- translate to the camelCase domain shape. Instants are unix ms INTEGERs and
-- booleans are 0/1 INTEGERs, both as established in `001_init.sql`.
--
-- Shape reference: `src/TaskRx.Data/Migrations/M0001_InitialSchema.cs` on the
-- `archive/dotnet-maui` branch. Postgres jsonb columns become TEXT here and
-- hold the same JSON; `pills_per_dose` is REAL because the C# column is a
-- double (half a pill is a real dosage).

CREATE TABLE IF NOT EXISTS reminders (
  id                    TEXT PRIMARY KEY,
  user_id               TEXT NOT NULL,
  kind                  TEXT NOT NULL CHECK (kind IN ('medication', 'task')),
  name                  TEXT NOT NULL,
  -- RxNorm concept id, carried forward from `medications.rxcui`. It is not
  -- part of the domain `Reminder` (see `packages/shared/src/domain/reminder.ts`)
  -- and no repository surfaces it, but `reminders.rxcui` exists server-side in
  -- `packages/convex/convex/schema.ts` for exactly this reason: if the local
  -- table had no column for it, migration 005 would drop the RxNorm code and
  -- the sync worker would have nothing to push once `medications` is retired.
  rxcui                 TEXT,
  dosage                TEXT,
  instructions          TEXT,
  -- JSON, the `ScheduleTimes` DOMAIN shape (lowercase `type` discriminator):
  --   {"type":"daily","times":["08:00"]}
  --   {"type":"interval","hours":8,"startAt":1750000000000}
  --   {"type":"weekly","daysOfWeek":[1,3],"times":["08:00"]}
  -- NOT the PascalCase `$type` wire shape that `serializeScheduleTimes`
  -- emits; that one exists only for talking to the archived C# API.
  schedule              TEXT NOT NULL,
  priority              TEXT NOT NULL DEFAULT 'normal'
                          CHECK (priority IN ('critical', 'high', 'normal', 'low')),
  tags                  TEXT NOT NULL DEFAULT '[]',
  pills_remaining       INTEGER,
  pills_per_dose        REAL,
  refill_reminder_days  INTEGER,
  caregiver_ids         TEXT NOT NULL DEFAULT '[]',
  active                INTEGER NOT NULL DEFAULT 1,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL,
  -- Sync columns, mirroring what 001 and 002 established for `medications`.
  sync_status           TEXT NOT NULL DEFAULT 'pending'
                          CHECK (sync_status IN ('pending', 'synced', 'error')),
  convex_id             TEXT
);

CREATE INDEX IF NOT EXISTS idx_reminders_user_active ON reminders(user_id, active);
CREATE INDEX IF NOT EXISTS idx_reminders_user_kind ON reminders(user_id, kind);
CREATE INDEX IF NOT EXISTS idx_reminders_convex_id ON reminders(convex_id);

CREATE TABLE IF NOT EXISTS reminder_instances (
  id              TEXT PRIMARY KEY,
  reminder_id     TEXT NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,
  user_id         TEXT NOT NULL,
  -- Denormalized from `reminders.kind` so the missed-instance sweep and the
  -- adherence calculator can filter medication rows without a join. The C#
  -- schema carries the same denormalization.
  reminder_kind   TEXT NOT NULL CHECK (reminder_kind IN ('medication', 'task')),
  scheduled_time  INTEGER NOT NULL,
  -- 'snoozed' is the status `dose_logs` could not express. It is distinct
  -- from 'skipped' and 'missed': a snooze is not a miss.
  status          TEXT NOT NULL
                    CHECK (status IN ('pending', 'taken', 'snoozed', 'skipped', 'missed')),
  taken_at        INTEGER,
  snoozed_until   INTEGER,
  snooze_count    INTEGER NOT NULL DEFAULT 0,
  notes           TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  sync_status     TEXT NOT NULL DEFAULT 'pending'
                    CHECK (sync_status IN ('pending', 'synced', 'error')),
  convex_id       TEXT
);

CREATE INDEX IF NOT EXISTS idx_reminder_instances_reminder
  ON reminder_instances(reminder_id);
CREATE INDEX IF NOT EXISTS idx_reminder_instances_user_scheduled
  ON reminder_instances(user_id, scheduled_time);
CREATE INDEX IF NOT EXISTS idx_reminder_instances_convex_id
  ON reminder_instances(convex_id);

-- Partial index for the pending-instance lookups that run on every app
-- foreground and on every notification tap. Restricting it to `status =
-- 'pending'` keeps it small: taken/missed rows accumulate forever, pending
-- rows do not.
CREATE INDEX IF NOT EXISTS idx_reminder_instances_pending
  ON reminder_instances(user_id, scheduled_time)
  WHERE status = 'pending';
