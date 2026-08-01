import { getDb } from "../index";

import * as instancesRepo from "./reminderInstances";

/**
 * Keeps the unified `reminder_instances` table in step with writes to the POC
 * `dose_logs` table.
 *
 * The instance-side counterpart to `medication-mirror.ts`, and it exists for
 * the same reason, one table over. Migration 005 copies `dose_logs` into
 * `reminder_instances` once, at upgrade time. Nothing kept them together
 * afterwards, so the unified table stopped being a projection of the dose
 * history and became a snapshot of it, accurate only as of the upgrade.
 *
 * ## What still writes `dose_logs`, after both cutovers
 *
 * Nothing does. The write cutover took the UI — `dose/log.tsx` calls
 * `instancesRepo` directly, so a dose a person records here is an instance and
 * nothing else. The sync cutover then took the pull: `pull.ts` no longer calls
 * `useDoseLogsSync`, so nothing hands `doseLogsRepo.upsertMany` anything, and
 * `mirrorDoseLogRow` — whose sole caller lives inside it — is unreachable.
 *
 * This module survives for one job: projecting rows a PRE-CUTOVER build already
 * left in the local `dose_logs` table, so an upgraded install can still find
 * them in its history. Without it such a row would sit in `dose_logs` while
 * `app/(app)/dose/history.tsx`, which reads `instancesRepo.list`, showed
 * nothing. That runs through `repairMissingProjections` below, now
 * `mirrorDoseLog`'s only reachable caller.
 *
 * Be precise about the stakes, because an earlier version of this comment was
 * not. It claimed the damage was server-side: that `api.reminderInstances` was
 * "what the server computes adherence from". That was false. There is no
 * server-side adherence computation — `computeAdherence` in
 * `packages/shared/src/adherence/calculator.ts` has no call site outside
 * `packages/shared/src`, and nothing anywhere reads the Convex
 * `reminderInstances` table. The damage is local: a pulled dose the user cannot
 * find in their own history.
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
 * and no later write brings it back. It is the one failure this module can
 * detect from the inside, so it is the one thing it logs. It is emphatically
 * not the only way a projection goes missing: a caller that lets
 * `upsertProjection` throw and swallows the error loses the row just as
 * permanently, and logs nothing here. `repairMissingProjections` below is what
 * covers both.
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
 * The dual-write this used to describe — one dose log pushing a document into
 * each Convex table — is gone from the local path with the write cutover, for
 * the reason `medication-mirror.ts` gives. What remains is one push per
 * *pulled* legacy dose log whose projection was missing, via
 * `repairMissingProjections`. It ends when `dose_logs` is retired.
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
 *     migration's NULL/0. This is the same rule `medication-mirror.ts` applies
 *     to the fields `medications` cannot express: a field the source cannot
 *     express is preserved, not overwritten.
 *
 *     It preserves nothing today. Nothing writes either column:
 *     `instancesRepo.markSnoozed` has no callers, and `notifications/snooze.ts`
 *     reschedules the notification without touching the database. An earlier
 *     version of this comment cited `snooze.ts` as the writer whose snooze
 *     "survives a later dose-log edit"; it records no snooze to survive. The
 *     exclusion is right in advance of a writer, not because of one.
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
    // Two different causes reach this branch and the message must not pick
    // one: either the EXISTS guard dropped the row because the parent
    // medication has no `reminders` row, or the SELECT matched nothing at all
    // because there is no such dose log. The harness covers both
    // ("a dose log with no projected medication does not abort the write" and
    // "mirroring an unknown dose log inserts nothing"), and naming only the
    // first sent a maintainer to the wrong table.
    //
    // A no-op update is not one of the causes: SQLite counts a DO UPDATE row
    // as changed even when every value it writes is identical, so this cannot
    // false-fire. `repairMissingProjections` retries the first cause on each
    // drain; the second is not retryable and not repairable.
    console.error(
      `[doseLogMirror] instance not projected for dose log ${doseLogId}: either its medication has no reminders row, or the dose log does not exist`
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

/**
 * Re-project every dose log that has no `reminder_instances` row.
 *
 * Every write path mirrors, but a mirror is still losable. `upsertProjection`
 * throws on a database error, and the pull path catches that per row and moves
 * on rather than abandoning the rest of the batch — a deliberate trade that
 * needs something behind it, because nothing else in the system repairs the
 * gap. `shouldApplyRemoteDoseLog` refuses every remote row whose local copy is
 * already `taken`, so re-pulling does not fix it. `reconcilePending` only
 * re-enqueues instances that already exist, so it never sees a row that was
 * never inserted. Left alone, a dose log missing its projection stays missing
 * for the life of the install, and since the read cutover that means a dose
 * the user logged is absent from their history.
 *
 * `mirrorDoseLog` rather than `mirrorDoseLogRow`: an instance the local
 * database has never held is one the server was never told about either, so
 * the repaired row is genuinely pending and wants pushing.
 *
 * Dose logs whose medication still has no `reminders` row are skipped again by
 * the EXISTS guard and logged by `upsertProjection`. Repairing those is
 * `medication-mirror.repairMissingProjections`'s job, which is why the drain
 * runs that pass first.
 *
 * Returns the number of dose logs successfully re-projected.
 */
export async function repairMissingProjections(): Promise<number> {
  const db = await getDb();
  const rows = await db.getAllAsync<{ readonly id: string }>(
    `SELECT d.id
     FROM dose_logs d
     WHERE NOT EXISTS (
       SELECT 1 FROM reminder_instances i WHERE i.id = d.id
     )`
  );

  let repaired = 0;
  for (const row of rows) {
    try {
      await mirrorDoseLog(row.id);
      repaired += 1;
    } catch (error: unknown) {
      // Per row: one unprojectable dose log must not strand the backlog
      // behind it, which is the same failure this pass exists to undo.
      console.error(
        `[doseLogMirror] repair failed for dose log ${row.id}:`,
        error
      );
    }
  }

  return repaired;
}
