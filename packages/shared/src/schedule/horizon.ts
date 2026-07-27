import type { Medication } from "../index";

import { upcomingDosesFor } from "./engine";
import type { ScheduledDose } from "./types";

/**
 * Global rolling-horizon selector for the notification scheduler.
 *
 * Two constraints force this to be a global (not per-medication) selection:
 *
 * 1. **iOS enforces a hard cap of 64 pending local notifications per app.**
 *    Scheduling 10 doses per medication (the pre-todo-7 behaviour) blows
 *    through that limit at 7 meds and iOS silently drops the rest.
 * 2. **Android has no hard cap but "the closest 64 doses regardless of
 *    medication" is the objectively correct set to have armed.** If med A
 *    has a dose in 2 hours and med B has one in 10 minutes, med B's dose
 *    must be scheduled even if med A already has 10 alarms armed.
 *
 * The function is pure so it can be unit-tested without a native module.
 * It returns doses time-sorted, oldest first, capped at `maxTotal`.
 */

export interface GlobalDose {
  readonly medicationId: string;
  readonly scheduledTime: number;
}

/**
 * Selects the next `maxTotal` doses across all active medications, oldest
 * first. Skips inactive medications entirely.
 *
 * Each medication contributes at most `maxTotal` candidate doses (its own
 * next-N horizon), which guarantees the global top-K is a subset of the
 * union — a med whose Nth dose is farther than another med's (N+1)th
 * cannot be in the global top-K anyway, since we've already sampled at
 * least N doses from it.
 */
export function selectUpcomingDoses(
  medications: readonly Medication[],
  fromMs: number,
  maxTotal: number
): readonly GlobalDose[] {
  if (maxTotal <= 0) {
    return [];
  }

  const candidates: GlobalDose[] = [];
  for (const med of medications) {
    if (!med.active) {
      continue;
    }
    const doses: readonly ScheduledDose[] = upcomingDosesFor(
      med,
      fromMs,
      maxTotal
    );
    for (const dose of doses) {
      candidates.push({
        medicationId: med.id,
        scheduledTime: dose.scheduledTime,
      });
    }
  }

  candidates.sort((a, b) => {
    if (a.scheduledTime !== b.scheduledTime) {
      return a.scheduledTime - b.scheduledTime;
    }
    return a.medicationId < b.medicationId ? -1 : 1;
  });

  return candidates.slice(0, maxTotal);
}
