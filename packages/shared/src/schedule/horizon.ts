import type { Reminder } from "../domain/reminder";
import {
  entersMedicationHorizon,
  entersTaskHorizon,
} from "../notifications/tier";

import { nextOccurrences } from "./occurrences";
import type { ScheduledOccurrence } from "./types";

/**
 * The soonest `maxTotal` occurrences across every active Reminder the Tier
 * predicate admits, soonest first.
 *
 * Both Horizons are this function with a different predicate, and that is the
 * point rather than a saving: a shared selector cannot develop one ordering
 * rule for doses and another for tasks, and neither call can see a Reminder the
 * other owns. What differs between the two is which Kinds enter and how many
 * fit — everything the OS is then asked for differs too, and that is decided by
 * the caller in `apps/mobile`, not here.
 */
function selectHorizon(
  reminders: readonly Reminder[],
  fromMs: number,
  maxTotal: number,
  admits: (reminder: Reminder) => boolean
): readonly ScheduledOccurrence[] {
  if (maxTotal <= 0) {
    return [];
  }

  const candidates: ScheduledOccurrence[] = [];
  for (const reminder of reminders) {
    if (!reminder.active || !admits(reminder)) {
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
  return selectHorizon(reminders, fromMs, maxTotal, entersMedicationHorizon);
}

/**
 * The task Horizon: the next `maxTotal` occurrences across all active **task**
 * reminders, soonest first.
 *
 * A separate selection over a disjoint set of Reminders, which is what makes
 * ADR 0003's guarantee structural. Nothing here can affect what
 * `selectMedicationHorizon` returns — not the number of tasks, not how soon
 * they are, not their priority — because the two functions never consider the
 * same Reminder.
 *
 * The ordering rules are the medication Horizon's, for the same reasons: the
 * soonest occurrences win globally rather than per reminder, so one chatty task
 * cannot crowd out a quieter one that is due first, and the `reminderId`
 * tie-break keeps a re-run byte-identical so the caller's diff finds nothing to
 * do.
 *
 * `maxTotal` is the caller's, and it is a much smaller number than the
 * medication 64 — see `MAX_TASK_TRIGGERS` in `apps/mobile`, and ADR 0007 for
 * why a bound exists at all when the mechanism underneath imposes none.
 *
 * Pure, so it can be unit-tested without a native module.
 */
export function selectTaskHorizon(
  reminders: readonly Reminder[],
  fromMs: number,
  maxTotal: number
): readonly ScheduledOccurrence[] {
  return selectHorizon(reminders, fromMs, maxTotal, entersTaskHorizon);
}
