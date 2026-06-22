import type { DoseLog, Medication } from "@taskrx/shared";

/**
 * Conflict resolution helpers.
 *
 * Medications use last-write-wins by `updatedAt`. Dose logs are append-only
 * history: a locally "taken" dose is never overwritten by a remote pull.
 */

export interface Timestamped {
  readonly updatedAt: number;
}

export function resolveConflict<T extends Timestamped>(local: T, remote: T): T {
  if (local.updatedAt > remote.updatedAt) {
    console.warn(
      `[sync] conflict detected; keeping local (updatedAt ${String(local.updatedAt)} > ${String(remote.updatedAt)})`
    );
    return local;
  }
  if (remote.updatedAt > local.updatedAt) {
    console.warn(
      `[sync] conflict detected; keeping remote (updatedAt ${String(remote.updatedAt)} > ${String(local.updatedAt)})`
    );
  }
  return remote;
}

export function mergeMeds(
  localMeds: readonly Medication[],
  remoteMeds: readonly Medication[]
): Medication[] {
  const byId = new Map<string, Medication>();

  for (const local of localMeds) {
    byId.set(local.id, local);
  }

  for (const remote of remoteMeds) {
    const local = byId.get(remote.id);
    if (local === undefined) {
      byId.set(remote.id, remote);
    } else {
      byId.set(remote.id, resolveConflict(local, remote));
    }
  }

  return Array.from(byId.values());
}

export function mergeDoseLogs(
  localLogs: readonly DoseLog[],
  remoteLogs: readonly DoseLog[]
): DoseLog[] {
  const byId = new Map<string, DoseLog>();

  for (const local of localLogs) {
    byId.set(local.id, local);
  }

  for (const remote of remoteLogs) {
    const local = byId.get(remote.id);
    if (local === undefined) {
      byId.set(remote.id, remote);
      continue;
    }

    if (local.status === "taken") {
      console.warn(
        `[sync] dose log ${local.id} is taken locally; skipping remote overwrite`
      );
      continue;
    }

    byId.set(remote.id, resolveConflict(local, remote));
  }

  return Array.from(byId.values());
}
