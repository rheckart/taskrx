import type { DoseLog, Medication } from "../index";

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
