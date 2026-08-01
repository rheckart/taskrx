import type { DoseLog, Medication, Reminder, ReminderInstance } from "../index";

/**
 * Pure sync-conflict decisions used by the mobile repos when applying a
 * remote pull to the local SQLite store.
 *
 * These helpers exist because the same rules were previously implemented
 * twice: once in a `mergeMeds`/`mergeDoseLogs` helper module that had zero
 * call sites, and once inline in each repo's `upsertMany`. The inline
 * repo behaviour is authoritative and is what these helpers preserve —
 * critically, the "local wins on tie" rule and the "never overwrite a
 * locally taken dose" rule.
 */

export type MedicationWithTimestamps = Pick<Medication, "updatedAt">;

export type DoseLogWithConflictFields = Pick<DoseLog, "updatedAt" | "status">;

/**
 * Decide whether an incoming remote medication should overwrite the local
 * row. Returns `false` when the local row is at least as fresh as the
 * remote (local wins ties). Returns `true` when there is no local row or
 * when the remote is strictly newer.
 */
export function shouldApplyRemoteMedication(
  local: MedicationWithTimestamps | null,
  remote: MedicationWithTimestamps
): boolean {
  if (local === null) {
    return true;
  }
  return remote.updatedAt > local.updatedAt;
}

/**
 * Decide whether an incoming remote dose log should overwrite the local
 * row. A locally `taken` dose is NEVER overwritten by a remote pull — a
 * patient's recorded dose must not be erased by a stale sync. Otherwise
 * the same last-write-wins rule as medications applies, with local
 * winning ties.
 */
export function shouldApplyRemoteDoseLog(
  local: DoseLogWithConflictFields | null,
  remote: DoseLogWithConflictFields
): boolean {
  if (local === null) {
    return true;
  }
  if (local.status === "taken") {
    return false;
  }
  return remote.updatedAt > local.updatedAt;
}

export type ReminderWithTimestamps = Pick<Reminder, "updatedAt">;

export type ReminderInstanceWithConflictFields = Pick<
  ReminderInstance,
  "updatedAt" | "status"
>;

/**
 * The unified counterparts of the two rules above, for the pull path onto
 * `reminders` and `reminder_instances`.
 *
 * They are deliberate copies rather than a generalization of their legacy
 * twins. The two pairs are the same decision today only by coincidence of
 * timing: the legacy pair is deleted with `medications` and `dose_logs`, and
 * ADR 0001 renames `taken` to `completed` on the unified side alone. A shared
 * implementation would have to be un-shared at both of those moments, and the
 * second one is a rename of the very field that carries the safety-critical
 * rule. Duplication that is scheduled to become the only copy is cheaper than
 * an abstraction that is scheduled to break.
 */

/**
 * Decide whether an incoming remote reminder should overwrite the local row.
 * Last-write-wins with the local row winning ties, exactly as for medications.
 */
export function shouldApplyRemoteReminder(
  local: ReminderWithTimestamps | null,
  remote: ReminderWithTimestamps
): boolean {
  if (local === null) {
    return true;
  }
  return remote.updatedAt > local.updatedAt;
}

/**
 * Decide whether an incoming remote instance should overwrite the local row.
 *
 * A locally `taken` instance is NEVER overwritten by a remote pull — the
 * safety-critical rule, and the whole reason this is a separate function from
 * `shouldApplyRemoteReminder`. A taking the person recorded on this device must
 * not be erased by a stale copy arriving from another one. Everything else is
 * last-write-wins with the local row winning ties.
 *
 * `snoozed` gets no such protection: a deferral is not an answer, so a later
 * remote answer legitimately replaces it.
 *
 * When ADR 0001 renames the status to `completed`, this guard is the line that
 * has to move with it. It is the one place in the sync layer where the status
 * string is load-bearing rather than merely carried.
 */
export function shouldApplyRemoteReminderInstance(
  local: ReminderInstanceWithConflictFields | null,
  remote: ReminderInstanceWithConflictFields
): boolean {
  if (local === null) {
    return true;
  }
  if (local.status === "taken") {
    return false;
  }
  return remote.updatedAt > local.updatedAt;
}
