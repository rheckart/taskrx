-- Migration 007: clear cross-table Convex document ids from the projections.
--
-- `005_backfill_reminders.sql` copied `medications.convex_id` into
-- `reminders.convex_id`, and `dose_logs.convex_id` into
-- `reminder_instances.convex_id`, on the reasoning that reusing ids keeps the
-- sync worker's id bridge resolving across the cutover. For the *local* ids
-- (the primary keys) that reasoning holds and 005 still does it. For
-- `convex_id` it does not, and 005 no longer does it.
--
-- A `convex_id` is a Convex document id, and Convex document ids are scoped to
-- a table. `medications.convex_id` is an `Id<"medications">`. The sync worker's
-- `resolveReminderId` reads `reminders.convex_id` and passes it to
-- `api.reminders.update` and to `api.reminderInstances.create` (as the parent
-- `reminderId`) typed as `Id<"reminders">`. The copy therefore aimed a document
-- id at the wrong table. The same applies to `reminder_instances.convex_id`,
-- which held a `doseLogs` id where an `Id<"reminderInstances">` was expected.
--
-- It was latent: nothing enqueued `reminder`-typed queue entries, so
-- `resolveReminderId` never ran. `medication-mirror.ts` now enqueues them, which
-- is exactly what arms the fault — so the contamination has to be cleared from
-- devices that already applied 005 before that enqueue can be allowed to run.
--
-- Fresh installs apply the corrected 005 and reach this migration with nothing
-- to do.
--
-- ## Why this is safe to re-run
--
-- The guard is `convex_id = the source row's convex_id`, not "this row came
-- from 005". That is the signature of the bug and nothing else: a reminder
-- whose id genuinely resolved through `api.reminders.create` carries an
-- `Id<"reminders">`, which cannot equal the `Id<"medications">` on the
-- medication of the same local id. So:
--
--   * before the fix, every projected row matches and is cleared;
--   * after this migration, no row matches and a re-run changes nothing;
--   * a reminder that has since synced properly is never matched, so a re-run
--     cannot destroy a legitimately resolved document id and force a duplicate
--     create on the server.
--
-- Scoping it as "id IN (SELECT id FROM medications)" would have been simpler
-- and would have satisfied the runner's `_migrations` guard, but it is not
-- idempotent in the sense that matters: run twice with a successful sync in
-- between, it would discard a real `Id<"reminders">`.
--
-- Rows are returned to `sync_status = 'pending'` because that is now true of
-- them — they hold an edit the server has never seen. The reconciliation pass
-- in `repos/reminders.ts` picks them up on the next drain and enqueues a
-- `create`, which is what finally populates the Convex `reminders` table.

UPDATE reminders
SET convex_id   = NULL,
    sync_status = 'pending'
WHERE convex_id IS NOT NULL
  AND convex_id = (
    SELECT m.convex_id FROM medications m WHERE m.id = reminders.id
  );

UPDATE reminder_instances
SET convex_id   = NULL,
    sync_status = 'pending'
WHERE convex_id IS NOT NULL
  AND convex_id = (
    SELECT d.convex_id FROM dose_logs d WHERE d.id = reminder_instances.id
  );
