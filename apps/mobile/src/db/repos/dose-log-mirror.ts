import { getDb } from "../index";

import * as instancesRepo from "./reminderInstances";

/**
 * Keeps the unified `reminder_instances` table in step with writes to the POC
 * `dose_logs` table.
 *
 * The instance-side counterpart to `medication-mirror.ts`, and it exists for
 * the same reason, one table over. Migration 005 copies `dose_logs` into
 * `reminder_instances` once, at upgrade time. Nothing kept them together
 * afterwards: every dose logged *after* that boot landed in `dose_logs` alone,
 * so the unified table stopped being a projection of the dose history and
 * became a snapshot of it, accurate only as of the upgrade.
 *
 * That divergence is quieter than the medications one and worse. A missing
 * `reminders` row means an alarm never fires, which a user notices. A missing
 * `reminder_instances` row changes nothing the user can see on the device —
 * the screens read `dose_logs` — while `api.reminderInstances` is what the
 * server computes adherence from. So the failure is a server-side history that
 * silently stops growing at the cutover boot, with the device showing a
 * complete one.
 *
 * ## Parity with migration 005
 *
 * As in `medication-mirror.ts`, the mapping is NOT re-derived here.
 * `DOSE_LOG_TO_INSTANCE_SELECT` is the `SELECT` from `005_backfill_reminders.
 * sql` verbatim, with one added `AND d.id = ?`. A mirrored row and a
 * backfilled row are the same row by construction, and
 * `scripts/verify-reminder-migrations.mjs` diffs the two texts so the two
 * copies cannot drift.
 *
 * ## The EXISTS guard, and why a skip is loud
 *
 * The guard is the migration's, kept because `reminder_instances.reminder_id`
 * is a foreign key into `reminders` and an insert that violates it aborts the
 * caller's write. It should be unreachable: 005 backfills a reminder for every
 * medication, `mirrorMedication` projects every later one, and a dose log can
 * only be created against a medication that already exists.
 *
 * If it is ever reached the row is not deferred, it is dropped — the
 * projection never exists, so `instancesRepo.reconcilePending` cannot find it
 * and no later write brings it back. That is the one way this module can fail
 * silently, so it is the one thing it logs.
 *
 * ## Sync identity: the projection is its own entity
 *
 * `dose_logs.convex_id` is an `Id<"doseLogs">` — a document id in a *different
 * Convex table* from the `Id<"reminderInstances">` that
 * `resolveReminderInstanceId` expects. Projecting it across would aim a
 * wrong-table id at the sync worker, which is the defect migration 007 was
 * written to clean up after 005 did exactly that. So `convex_id` is written
 * NULL on insert and is absent from the conflict clause entirely: the mirror
 * has no correct value to offer, and must not disturb the one a real
 * `api.reminderInstances.create` resolved.
 *
 * The cost is the same deliberate dual-write `medication-mirror.ts` accepts:
 * one dose log pushes twice, creating a document in each Convex table. It ends
 * when `dose_logs` is retired.
 */

/**
 * The `SELECT` half of migration 005's `dose_logs -> reminder_instances`
 * insert.
 *
 * Kept byte-identical to the migration (see the file header). The added
 * `AND d.id = ?` narrows it to one dose log; the `EXISTS` clause above it is
 * the migration's own.
 */
const DOSE_LOG_TO_INSTANCE_SELECT = `
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
    'pending',
    NULL
  FROM dose_logs d
  WHERE EXISTS (SELECT 1 FROM reminders r WHERE r.id = d.medication_id)
    AND d.id = ?
`;

/**
 * Appended to the conflict clause on the local-write path only.
 *
 * Concatenated rather than interpolated for the reason
 * `medication-mirror.ts`'s twin gives: `scripts/verify-reminder-migrations.mjs`
 * extracts the statement's template literal and prepares it against real
 * SQLite, and a `${...}` conditional inside it would land in the extracted text
 * verbatim and fail to parse.
 */
const REPEND_CLAUSE = `,
       sync_status = 'pending'`;

/**
 * Upserts the `reminder_instances` projection of a single dose log.
 *
 * Call this *after* the `dose_logs` write has committed — the statement reads
 * the dose log row back, so it always projects the persisted state rather than
 * the caller's intent.
 *
 * The conflict clause updates only the fields a dose log can actually change.
 * Three are deliberately excluded:
 *
 *   * `reminder_kind` — a mirrored instance is always `medication`, and
 *     rewriting it would clobber the row if its parent ever became a task.
 *   * `snoozed_until` / `snooze_count` — the POC dose log has no source for
 *     either, so they are left untouched on update rather than reset to the
 *     migration's NULL/0. A snooze recorded through the unified path (see
 *     `notifications/snooze.ts`) therefore survives a later dose-log edit,
 *     which is the same rule `medication-mirror.ts` applies to the fields
 *     `medications` cannot express.
 *   * `convex_id` — see the header. Absent from the clause rather than
 *     defaulted.
 *
 * `sync_status` is never inherited from the dose log. When `repend` is set it
 * is forced to `'pending'`, because the projection has its own unsynced edit to
 * push independent of whatever the dose log's queue entry is doing.
 * `mirrorDoseLogRow` clears the flag for the pull path, where the content
 * arrived from the server and re-pending it would push the server's own data
 * back.
 *
 * Private: callers pick `mirrorDoseLog` (local write — refresh and push) or
 * `mirrorDoseLogRow` (remote pull — refresh only).
 */
async function upsertProjection(
  doseLogId: string,
  repend: boolean
): Promise<void> {
  const db = await getDb();
  const result = await db.runAsync(
    `INSERT INTO reminder_instances (
       id, reminder_id, user_id, reminder_kind, scheduled_time,
       status, taken_at, snoozed_until, snooze_count, notes,
       created_at, updated_at, sync_status, convex_id
     )
     ${DOSE_LOG_TO_INSTANCE_SELECT}
     ON CONFLICT(id) DO UPDATE SET
       reminder_id    = excluded.reminder_id,
       user_id        = excluded.user_id,
       scheduled_time = excluded.scheduled_time,
       status         = excluded.status,
       taken_at       = excluded.taken_at,
       notes          = excluded.notes,
       updated_at     = excluded.updated_at` + (repend ? REPEND_CLAUSE : ""),
    doseLogId
  );

  if (result.changes === 0) {
    // The EXISTS guard dropped it: the parent medication has no `reminders`
    // row. Nothing retries this, so say so rather than lose the dose silently.
    console.error(
      `[doseLogMirror] no reminders row for dose log ${doseLogId}'s medication; instance not projected`
    );
  }
}

/**
 * Refreshes the projection and enqueues it for the server.
 *
 * For the local write path. There is no separate delete variant because a dose
 * log is never deleted — `deleteDoseLog` is an `updateStatus` to `'missed'`, so
 * a removal already arrives here as an ordinary status change.
 */
export async function mirrorDoseLog(doseLogId: string): Promise<void> {
  await upsertProjection(doseLogId, true);
  await instancesRepo.enqueueExternalWrite(doseLogId);
}

/**
 * Refreshes the projection without claiming the change needs pushing.
 *
 * For the pull path, and the reasoning is `mirrorMedicationRow`'s exactly: the
 * content arrived from the server, so enqueuing it would push the server's own
 * data straight back, one entry per dose log in the pull. `sync_status` is
 * likewise left alone on conflict — under dual-write, whichever device wrote
 * the dose log also wrote the instance, so a row already `synced` is still
 * synced. A projection row that does not exist yet is still INSERTed `pending`
 * by the SELECT, because that one genuinely has never been pushed;
 * `instancesRepo.reconcilePending` collects it on the next drain, and the
 * server's create is an upsert on `localId`, so a duplicate is absorbed.
 */
export async function mirrorDoseLogRow(doseLogId: string): Promise<void> {
  await upsertProjection(doseLogId, false);
}
