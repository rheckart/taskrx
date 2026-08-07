import type { Reminder } from "../domain/reminder";
import { entersMedicationHorizon } from "../notifications/tier";

import { nextOccurrences } from "./occurrences";
import type { ScheduledOccurrence } from "./types";

/**
 * The medication Horizon: the next `maxTotal` occurrences across all active
 * **medication** reminders, soonest first.
 *
 * The name says medication because the exclusion is the point. ADR 0003 makes
 * the Horizon medication-only by construction — a task armed here would consume
 * a slot in a bounded set that a dose might have needed, and the caller arms
 * every member of this set with `SET_ALARM_CLOCK`, which is not a primitive
 * "walk the dog" has any business firing. `entersMedicationHorizon` is the rule
 * and `../notifications/tier.ts` is where it is stated; this function only
 * obeys it. Tasks are delivered on their own Tier, on their own budget.
 *
 * It was called `selectUpcomingOccurrences` while medications were the only
 * Kind that could exist, and the old name would now be a lie in the one place
 * it matters most.
 *
 * Two constraints force this to be a global selection rather than a per-
 * reminder one:
 *
 * 1. **iOS enforces a hard cap of 64 pending local notifications per app.**
 *    Scheduling 10 per reminder (the pre-todo-7 behaviour) blows through that
 *    limit at 7 reminders and iOS silently drops the rest.
 * 2. **Android has no hard cap, but "the closest 64 regardless of reminder" is
 *    the objectively correct set to have armed.** A reminder due in 10 minutes
 *    must displace another reminder's alarm 10 hours out, however many alarms
 *    that other reminder already has.
 *
 * Pure, so it can be unit-tested without a native module.
 *
 * It delegates to `nextOccurrences`, which is what lets it cover `weekly`
 * schedules and carry the display fields the notification payload needs. A
 * medication-only twin, `selectUpcomingDoses`, used to sit beside this over the
 * POC `engine.ts`; both went with the legacy generation.
 *
 * Each reminder contributes at most `maxTotal` candidates (its own next-N
 * horizon), which guarantees the global top-K is a subset of the union — a
 * reminder whose Nth occurrence is farther out than another reminder's (N+1)th
 * cannot be in the global top-K anyway, since we have already sampled at least
 * N occurrences from it.
 *
 * Equal `fireAt` values are ordered by `reminderId` ascending.
 * `Array.prototype.sort` is only guaranteed stable with respect to the input
 * array, and the input order here is the caller's reminder list, so an explicit
 * tie-break is what makes re-running the scheduler produce a byte-identical set
 * of alarms. `reminderId` is a total order over the candidates because a single
 * reminder never yields two occurrences at the same instant.
 */
export function selectMedicationHorizon(
  reminders: readonly Reminder[],
  fromMs: number,
  maxTotal: number
): readonly ScheduledOccurrence[] {
  if (maxTotal <= 0) {
    return [];
  }

  const candidates: ScheduledOccurrence[] = [];
  for (const reminder of reminders) {
    if (!reminder.active || !entersMedicationHorizon(reminder)) {
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
