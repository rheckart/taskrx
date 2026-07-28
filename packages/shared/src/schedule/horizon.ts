import type { Reminder } from "../domain/reminder";
import type { Medication } from "../index";

import { upcomingDosesFor } from "./engine";
import { nextOccurrences } from "./occurrences";
import type { ScheduledDose, ScheduledOccurrence } from "./types";

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

/**
 * The unified sibling of `selectUpcomingDoses`: the next `maxTotal`
 * occurrences across all active reminders, soonest first.
 *
 * Same two constraints, same answer. iOS still caps pending local
 * notifications at 64 per app, and "the closest 64 occurrences regardless of
 * reminder" is still the objectively correct set to arm — a reminder due in 10
 * minutes must displace another reminder's alarm 10 hours out, no matter how
 * many alarms that other reminder already has armed.
 *
 * What changes is the source: this delegates to `nextOccurrences`, so it
 * covers `weekly` (which the POC `engine.ts` has no shape for) and `task`
 * reminders, and it carries the display fields the notification payload needs.
 * `selectUpcomingDoses` stays for the medication-only POC paths until the
 * cutover completes.
 *
 * Each reminder contributes at most `maxTotal` candidates (its own next-N
 * horizon), which guarantees the global top-K is a subset of the union — a
 * reminder whose Nth occurrence is farther out than another reminder's (N+1)th
 * cannot be in the global top-K anyway, since we have already sampled at least
 * N occurrences from it.
 *
 * Equal `fireAt` values are ordered by `reminderId` ascending, mirroring the
 * `medicationId` tie-break in `selectUpcomingDoses`. `Array.prototype.sort` is
 * only guaranteed stable with respect to the input array, and the input order
 * here is the caller's reminder list, so an explicit tie-break is what makes
 * re-running the scheduler produce a byte-identical set of alarms. `reminderId`
 * is a total order over the candidates because a single reminder never yields
 * two occurrences at the same instant.
 */
export function selectUpcomingOccurrences(
  reminders: readonly Reminder[],
  fromMs: number,
  maxTotal: number
): readonly ScheduledOccurrence[] {
  if (maxTotal <= 0) {
    return [];
  }

  const candidates: ScheduledOccurrence[] = [];
  for (const reminder of reminders) {
    if (!reminder.active) {
      continue;
    }
    candidates.push(...nextOccurrences(reminder, fromMs, maxTotal));
  }

  candidates.sort((a, b) => {
    if (a.fireAt !== b.fireAt) {
      return a.fireAt - b.fireAt;
    }
    return a.reminderId < b.reminderId ? -1 : 1;
  });

  return candidates.slice(0, maxTotal);
}
