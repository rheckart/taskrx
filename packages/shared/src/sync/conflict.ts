import type { Reminder, ReminderInstance } from "../index";

/**
 * Pure sync-conflict decisions, used when applying a remote pull to the local
 * SQLite store.
 *
 * Two rules, and only the second is interesting: last-write-wins with the LOCAL
 * row winning ties, and — for an Instance — a locally `completed` row is never
 * overwritten at all.
 *
 * A medication/dose-log pair used to sit alongside these, written first and
 * copied here deliberately rather than generalized, on the reasoning that the
 * legacy pair was scheduled for deletion and ADR 0001 would rename `taken` to
 * `completed` on the unified side alone. Both have now happened, leaving these
 * as the only copy — which is what that reasoning predicted, and why there was
 * never an abstraction to unpick.
 */

export type ReminderWithTimestamps = Pick<Reminder, "updatedAt">;

export type ReminderInstanceWithConflictFields = Pick<
  ReminderInstance,
  "updatedAt" | "status"
>;

/**
 * Decide whether an incoming remote reminder should overwrite the local row.
 * Last-write-wins, with the local row winning ties.
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
 * A locally `completed` instance is NEVER overwritten by a remote pull — the
 * safety-critical rule, and the whole reason this is a separate function from
 * `shouldApplyRemoteReminder`. A taking the person recorded on this device must
 * not be erased by a stale copy arriving from another one. Everything else is
 * last-write-wins with the local row winning ties.
 *
 * `snoozed` gets no such protection: a deferral is not an answer, so a later
 * remote answer legitimately replaces it.
 *
 * This guard is the one place in the sync layer where the status string is
 * load-bearing rather than merely carried, which is why ADR 0001's rename of
 * `taken` to `completed` had to move this line with it.
 */
export function shouldApplyRemoteReminderInstance(
  local: ReminderInstanceWithConflictFields | null,
  remote: ReminderInstanceWithConflictFields
): boolean {
  if (local === null) {
    return true;
  }
  if (local.status === "completed") {
    return false;
  }
  return remote.updatedAt > local.updatedAt;
}
