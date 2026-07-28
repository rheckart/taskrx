-- Migration 005: backfill the unified tables from the POC tables.
--
-- Every `medications` row becomes a `reminders` row with kind='medication',
-- and every `dose_logs` row becomes a `reminder_instances` row with
-- reminder_kind='medication'. The source tables are NOT touched: this is a
-- copy, not a move. `medications` and `dose_logs` remain the rollback path
-- and are still read by `apps/mobile/src/sync/worker.ts`.
--
-- Primary keys are preserved verbatim (reminders.id = medications.id,
-- reminder_instances.id = dose_logs.id), and so is `convex_id`. That matters:
-- the sync worker's id bridge resolves a Convex document id either from the
-- local `convex_id` column or by querying `getByLocalId` with the local UUID.
-- Reusing the ids means both halves of that bridge keep resolving after the
-- cutover; minting fresh ids here would orphan every already-synced document.
--
-- IDEMPOTENCY: both statements are `INSERT OR IGNORE` keyed on the preserved
-- primary key, so a second run inserts nothing. The runner's `_migrations`
-- guard should already prevent that, but a migration that was interrupted
-- partway is exactly the case where the guard did not fire.
--
-- Requires SQLite's JSON1 functions (compiled in by default since 3.38, and
-- expo-sqlite ships well past that). A missing JSON1 fails loudly inside the
-- transaction and rolls back, leaving the source tables untouched.

-- ── medications -> reminders ─────────────────────────────────────────────
--
-- The schedule mapping is the only non-trivial part. `medications` splits the
-- schedule across a `schedule_type` column and a `schedule_times` JSON blob
-- holding a bare `{"times":[...]}` or `{"hours":N,"startAt":N}`. The unified
-- `schedule` column holds a single tagged union with the discriminator inside
-- the JSON.
--
-- The nested `json_type(...)` checks are guards against a malformed blob: a
-- bare `json_extract` on invalid JSON raises and aborts the whole migration.
-- The `sched` subquery column normalizes an unparseable blob to '{}' first so
-- the `json_type` calls below always run against valid JSON. A row that fails
-- the guards degrades to an empty schedule rather than failing the boot — the
-- same degrade `medications.ts`'s own `parseScheduleTimes` already applies,
-- and the original blob is still readable in `medications.schedule_times`.
--
-- Fields the POC has no source for (instructions, pill counts, refill days,
-- caregivers) are NULL or the domain default. `priority` is 'normal', which
-- is the default `createReminder` applies rather than the C# enum's zero
-- value. `medications.rxcui` is copied across even though the unified domain
-- type does not model it: `reminders.rxcui` is storage-only, mirroring the
-- server-side field, so the RxNorm code survives the retirement of
-- `medications`.
INSERT OR IGNORE INTO reminders (
  id, user_id, kind, name, rxcui, dosage, instructions, schedule,
  priority, tags, pills_remaining, pills_per_dose, refill_reminder_days,
  caregiver_ids, active, created_at, updated_at, sync_status, convex_id
)
SELECT
  src.id,
  src.user_id,
  'medication',
  src.name,
  src.rxcui,
  src.dosage,
  NULL,
  CASE src.schedule_type
    WHEN 'interval' THEN json_object(
      'type', 'interval',
      'hours', CASE
        WHEN json_type(src.sched, '$.hours') IN ('integer', 'real')
          THEN json_extract(src.sched, '$.hours')
        ELSE 0
      END,
      'startAt', CASE
        WHEN json_type(src.sched, '$.startAt') IN ('integer', 'real')
          THEN json_extract(src.sched, '$.startAt')
        ELSE 0
      END
    )
    ELSE json_object(
      'type', 'daily',
      'times', json(CASE
        WHEN json_type(src.sched, '$.times') = 'array'
          THEN json_extract(src.sched, '$.times')
        ELSE '[]'
      END)
    )
  END,
  'normal',
  '[]',
  NULL,
  NULL,
  NULL,
  '[]',
  src.active,
  src.created_at,
  src.updated_at,
  src.sync_status,
  src.convex_id
FROM (
  SELECT
    m.*,
    CASE WHEN json_valid(m.schedule_times) THEN m.schedule_times ELSE '{}' END AS sched
  FROM medications m
) AS src;

-- ── dose_logs -> reminder_instances ──────────────────────────────────────
--
-- Column-for-column, with the two fields `dose_logs` never had defaulted:
-- `snoozed_until` NULL and `snooze_count` 0. Every status in the old CHECK
-- ('pending','taken','skipped','missed') is also in the new one, so no status
-- needs remapping.
--
-- The EXISTS guard keeps the foreign key satisfiable. `medications` rows are
-- only ever soft-deleted (active = 0), so an orphaned dose log should not
-- exist; if one does, skipping it here is strictly safer than aborting the
-- migration on every boot, and the row is NOT lost — it stays in `dose_logs`
-- and a later re-run picks it up once the parent exists.
INSERT OR IGNORE INTO reminder_instances (
  id, reminder_id, user_id, reminder_kind, scheduled_time,
  status, taken_at, snoozed_until, snooze_count, notes,
  created_at, updated_at, sync_status, convex_id
)
SELECT
  d.id,
  d.medication_id,
  d.user_id,
  'medication',
  d.scheduled_time,
  d.status,
  d.taken_at,
  NULL,
  0,
  d.notes,
  d.created_at,
  d.updated_at,
  d.sync_status,
  d.convex_id
FROM dose_logs d
WHERE EXISTS (SELECT 1 FROM reminders r WHERE r.id = d.medication_id);
