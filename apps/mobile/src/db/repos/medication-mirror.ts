import { getDb } from "../index";

import * as remindersRepo from "./reminders";

/**
 * Keeps the unified `reminders` table in step with writes to the POC
 * `medications` table.
 *
 * Why this exists: `notifications/scheduler.ts` reads `reminders`, because that
 * is the only table with a shape for a `weekly` schedule. Migration 005
 * backfills it once, at upgrade time. Without this mirror a medication written
 * to `medications` *after* that boot would have no `reminders` row and would
 * therefore never produce an alarm — a silent, total failure of the app's one
 * job.
 *
 * ## What still writes `medications`, after both cutovers
 *
 * Nothing does. The write cutover took the UI — `meds/new.tsx` and the
 * stress-seed screen call `remindersRepo.create`, so a medication a person adds
 * here never produces a `medications` row. The sync cutover then took the pull:
 * `pull.ts` no longer calls `useMedicationsSync`, so nothing hands
 * `medicationsRepo.upsertMany` anything either.
 *
 * This module therefore survives for exactly one job: projecting rows that a
 * PRE-CUTOVER build already left in the local `medications` table, so an
 * upgraded install does not lose their alarms. That happens through
 * `repairMissingProjections` below, which the sync drain runs and which is now
 * `mirrorMedication`'s only reachable caller — its other two call sites are
 * still there in `medications.ts` (`create`, `update`), but those two have no
 * callers themselves.
 *
 * `mirrorMedicationRow` is unreachable: its sole caller is
 * `medications.upsertMany`, which the sync cutover retired.
 * `mirrorMedicationDelete` is likewise reachable only through
 * `medications.softDelete`, which nothing calls.
 *
 * ## Parity with migration 005
 *
 * The mapping is NOT re-derived here. `MEDICATION_TO_REMINDER_SELECT` is the
 * `SELECT` from `005_backfill_reminders.sql` verbatim, with one added `WHERE`
 * on the medication id. A mirrored row and a backfilled row are therefore the
 * same row by construction: there is no second copy of the schedule mapping to
 * drift, and a fix to the JSON guards in 005 applies to both. The two callers
 * of that SQL are this file and the migration, and `scripts/verify-reminder-
 * migrations.mjs` asserts the migration half.
 *
 * ## Why raw SQL rather than `remindersRepo.create` / `update`
 *
 * Only to keep the mapping identical to the migration, per the section above.
 * The row write is SQL; the sync enqueue is not. Each write ends by calling
 * `remindersRepo.enqueueExternalWrite`, which builds the canonical reminder
 * payload from the persisted row.
 *
 * ## Sync identity: the projection is its own entity
 *
 * This module previously copied `medications.sync_status` and
 * `medications.convex_id` into the projection, on the reasoning that the
 * mirror was local-only and the row should "report the truth about a sync the
 * medication entry is performing". That was wrong in a way that mattered.
 *
 * `convex_id` is not a shared identifier. A medication's `convex_id` is an
 * `Id<"medications">` — a document id in a *different Convex table*. The sync
 * worker's `resolveReminderId` reads `reminders.convex_id` and hands it to
 * `api.reminders.update` and to `api.reminderInstances.create` as an
 * `Id<"reminders">`. Copying the column across therefore aimed a medications
 * document id at the reminders table. It was latent only because nothing
 * enqueued reminder entries, so `resolveReminderId` never ran; enqueuing here
 * is exactly what would have armed it.
 *
 * So the projection now owns its sync lifecycle: it is written `pending` with
 * a NULL `convex_id`, and `convex_id` is set only by `remindersRepo.
 * setConvexId` after a genuine `api.reminders.create`. The conflict clause
 * does not mention `convex_id` at all — the mirror has no correct value to
 * offer, and must not disturb the one a real sync resolved.
 *
 * The dual-write this used to describe — one medication write pushing both a
 * `medication` entry and a `reminder` entry — is gone from the local path with
 * the write cutover, because nothing local writes `medications` any more. What
 * remains is one push per *pulled* legacy medication, and only when the
 * projection was missing: `mirrorMedicationRow` does not enqueue, and
 * `repairMissingProjections` is enqueuing a row the server has genuinely never
 * seen. It ends when `medications` is retired.
 */

/**
 * The `SELECT` half of migration 005's `medications -> reminders` insert.
 *
 * Kept byte-identical to the migration (see the file header). The `src`
 * subquery normalizes an unparseable `schedule_times` blob to `'{}'` so the
 * `json_type` guards below always run against valid JSON; a row that fails the
 * guards degrades to an empty schedule rather than throwing, matching both the
 * migration and `medications.ts`'s own `parseScheduleTimes`.
 */
const MEDICATION_TO_REMINDER_SELECT = `
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
    'pending',
    NULL
  FROM (
    SELECT
      m.*,
      CASE WHEN json_valid(m.schedule_times) THEN m.schedule_times ELSE '{}' END AS sched
    FROM medications m
  ) AS src
  WHERE src.id = ?
`;

/**
 * Appended to the conflict clause on the local-write path only.
 *
 * Concatenated rather than interpolated so the statement above stays a single
 * unbroken template literal: `scripts/verify-reminder-migrations.mjs` extracts
 * that literal and prepares it against real SQLite, and a `${...}` conditional
 * inside it would land in the extracted text verbatim and fail to parse.
 */
const REPEND_CLAUSE = `,
       sync_status = 'pending'`;

/**
 * Upserts the `reminders` projection of a single medication.
 *
 * Call this *after* the `medications` write has committed — the statement
 * reads the medication row back, so it always projects the persisted state
 * rather than the caller's intent.
 *
 * The conflict clause updates only the fields a medication can actually
 * change. Two are deliberately excluded:
 *
 *   * `kind` — a mirrored row is always `medication`, and rewriting it would
 *     clobber the row if a user ever converts a reminder to a task.
 *   * `convex_id` — the mirror has no `Id<"reminders">` to offer (see the
 *     header), and must not disturb one an earlier sync resolved. It is
 *     absent from the clause entirely rather than defaulted.
 *
 * `sync_status` is never *inherited* from the medication — see the header. When
 * `repend` is set it is forced to `'pending'`, because the projection has its
 * own unsynced edit to push independent of whatever the medication's queue
 * entry is doing. `mirrorMedicationRow` clears the flag for the pull path,
 * where the content arrived from the server and re-pending it would push the
 * server's own data back.
 *
 * Fields the POC has no source for (`instructions`, pill counts, refill days,
 * caregivers, `tags`, `priority`) are left untouched on update rather than
 * reset to the migration's defaults, so a value set through the unified path
 * survives a subsequent medication edit.
 *
 * Private: callers pick `mirrorMedication` (local write — refresh and push) or
 * `mirrorMedicationRow` (remote pull — refresh only). Making that choice
 * explicit at the call site is the point; the previous single entry point
 * enqueued from `markSynced`, so recording a *successful* sync re-pended the
 * projection and queued more work.
 */
async function upsertProjection(
  medicationId: string,
  repend: boolean
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `INSERT INTO reminders (
       id, user_id, kind, name, rxcui, dosage, instructions, schedule,
       priority, tags, pills_remaining, pills_per_dose, refill_reminder_days,
       caregiver_ids, active, created_at, updated_at, sync_status, convex_id
     )
     ${MEDICATION_TO_REMINDER_SELECT}
     ON CONFLICT(id) DO UPDATE SET
       user_id     = excluded.user_id,
       name        = excluded.name,
       rxcui       = excluded.rxcui,
       dosage      = excluded.dosage,
       schedule    = excluded.schedule,
       active      = excluded.active,
       updated_at  = excluded.updated_at` + (repend ? REPEND_CLAUSE : ""),
    medicationId
  );
}

export async function mirrorMedication(medicationId: string): Promise<void> {
  await upsertProjection(medicationId, true);
  await remindersRepo.enqueueExternalWrite(medicationId);
}

/**
 * Refreshes the projection without claiming the change needs pushing.
 *
 * For the pull path. `useMedicationsSync` hands `upsertMany` whatever the live
 * `api.medications.list` subscription last emitted, and the projection must
 * follow or the scheduler arms alarms from stale rows. But that content
 * *arrived from the server*, so enqueuing it would push the server's own data
 * straight back — one entry per medication in the account, on every
 * subscription fire.
 *
 * `sync_status` is likewise left alone on conflict. A row already `synced` is
 * still synced: under dual-write, whichever device wrote the medication also
 * wrote the reminder, so the server's reminder document already carries this
 * content. A projection row that does not exist yet is still INSERTed
 * `pending` by the SELECT, because that one genuinely has never been pushed —
 * `reconcilePending` collects it on the next drain, and the server's create is
 * an upsert on `localId`, so a duplicate is absorbed rather than doubled.
 */
export async function mirrorMedicationRow(medicationId: string): Promise<void> {
  await upsertProjection(medicationId, false);
}

/**
 * Mirrors a medication soft-delete.
 *
 * `medications.softDelete` flips `active = 0` and leaves the row in place, so
 * the projection does the same: `selectUpcomingOccurrences` skips inactive
 * reminders, which is what actually stops the alarms. The row is retained so
 * dose history that references it still resolves.
 *
 * A no-op when the reminder is absent — a medication created before migration
 * 005 ran and deleted before it was ever mirrored has nothing to deactivate,
 * and that is not an error. `enqueueExternalWrite` is likewise a no-op for a
 * missing row, so the trailing call is safe in that case.
 *
 * The deactivation propagates as an `update` carrying `active: false`, not as
 * a `delete`; see `enqueueExternalWrite` for why.
 */
export async function mirrorMedicationDelete(
  medicationId: string,
  deletedAt: number
): Promise<void> {
  const db = await getDb();
  await db.runAsync(
    `UPDATE reminders
     SET active = 0, updated_at = ?, sync_status = 'pending'
     WHERE id = ?`,
    deletedAt,
    medicationId
  );

  await remindersRepo.enqueueExternalWrite(medicationId);
}

/**
 * Re-project every medication that has no `reminders` row.
 *
 * The reminders-side counterpart to
 * `dose-log-mirror.repairMissingProjections`, and it exists for the same
 * reason: a mirror that throws is a projection lost for good, because
 * `shouldApplyRemoteMedication` rejects any remote row that is not strictly
 * newer than the local one and `reconcilePending` only re-enqueues reminders
 * that already exist. Since the read cutover `meds/index.tsx` reads
 * `remindersRepo.list`, so a missing projection is a medication absent from
 * the user's own list — and, because `selectUpcomingOccurrences` schedules off
 * `reminders`, one whose alarms never fire.
 *
 * This runs ahead of the dose-log pass in the drain. A dose log cannot be
 * projected while its medication has no reminder to point at, so repairing
 * medications first turns what would be a skipped instance into a repaired one
 * within the same drain.
 *
 * Soft deletes are safe from it. `mirrorMedicationDelete` flips
 * `reminders.active = 0` and leaves the row in place, so a deleted
 * medication's reminder still exists and is never selected here. Only a
 * projection that was never written at all is.
 *
 * Returns the number of medications successfully re-projected.
 */
export async function repairMissingProjections(): Promise<number> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ readonly id: string }>(
    `SELECT m.id
     FROM medications m
     WHERE NOT EXISTS (
       SELECT 1 FROM reminders r WHERE r.id = m.id
     )`
  );

  let repaired = 0;
  for (const row of rows) {
    try {
      await mirrorMedication(row.id);
      repaired += 1;
    } catch (error: unknown) {
      // Per row: one unprojectable medication must not strand the backlog
      // behind it, which is the same failure this pass exists to undo.
      console.error(
        `[medicationMirror] repair failed for medication ${row.id}:`,
        error
      );
    }
  }

  return repaired;
}
