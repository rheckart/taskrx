import { getDb } from "../index";

import * as remindersRepo from "./reminders";

/**
 * Keeps the unified `reminders` table in step with writes to the POC
 * `medications` table.
 *
 * Why this exists: `notifications/scheduler.ts` now reads `reminders`, because
 * that is the only table with a shape for a `weekly` schedule. Migration 005
 * backfills it once, at upgrade time. Without this mirror a medication created
 * *after* that boot would have no `reminders` row and would therefore never
 * produce an alarm — a silent, total failure of the app's one job.
 *
 * Why not just move the UI to `remindersRepo`: that is the real cutover, and
 * it touches the med list, the dose log, and the dose history alongside the
 * write path. This module is the reversible half — `medications` stays the
 * source of truth the screens read, `reminders` becomes a derived projection
 * of it, and deleting this file plus repointing the scheduler is a complete
 * rollback.
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
 * The cost is that one medication write now pushes twice: a `medication` entry
 * and a `reminder` entry, creating a document in each Convex table. That is
 * deliberate dual-write. It is the price of having the unified model populated
 * and current *before* the cutover, so the cutover is a read-path change
 * rather than a big-bang server migration. It ends when `medications` is
 * retired.
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
 * `sync_status` is forced to `'pending'` rather than inherited: the projection
 * has just changed and has its own unsynced edit to push, independent of
 * whatever the medication's own queue entry is doing.
 *
 * Fields the POC has no source for (`instructions`, pill counts, refill days,
 * caregivers, `tags`, `priority`) are left untouched on update rather than
 * reset to the migration's defaults, so a value set through the unified path
 * survives a subsequent medication edit.
 */
export async function mirrorMedication(medicationId: string): Promise<void> {
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
       updated_at  = excluded.updated_at,
       sync_status = 'pending'`,
    medicationId
  );

  await remindersRepo.enqueueExternalWrite(medicationId);
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
