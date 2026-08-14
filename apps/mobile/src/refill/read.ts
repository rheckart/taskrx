import type { Reminder, RefillProjection } from "@taskrx/shared";
import { projectRefill, reconcileRefills } from "@taskrx/shared";

import { generateId } from "../db/repos/ids";
import * as remindersRepo from "../db/repos/reminders";

/**
 * The Refill read: project every medication, then make the Backlog agree.
 *
 * Every rule lives elsewhere and is gated at 100% there. `projectRefill` says
 * how long a supply lasts and whether that is close enough to act on,
 * `planRefill` says what a Refill Reminder is, and `reconcileRefills` says which
 * ones the Backlog should gain and lose. This file decides none of it: it reads
 * two tables, hands them over, and writes down the answer.
 *
 * ## Why the raise happens here rather than on the screen
 *
 * "A Refill Reminder is raised ahead of running out" is the ticket, and a
 * warning that exists only while its own screen is open is not one. Raising it
 * as a Backlog task is what puts it in front of someone who came to the app for
 * something else. Doing it inside the read, the way `readAdherence` owns the
 * sweep, is what makes it impossible for a second refill surface to forget:
 * there is no way to obtain the projection that skips it.
 *
 * ## What this does not yet do — worth knowing before trusting the number
 *
 * **Nothing consumes the supply.** No path decrements `pillsRemaining` when a
 * dose is recorded — the backend has a `consumePills` mutation with no callers —
 * so the projection only moves when somebody edits the count by hand. Thirty
 * pills at one a day reads "30 days left" tomorrow as well as today. What is
 * shown is therefore an honest projection of the numbers it was given, and
 * #17's acceptance criteria are about exactly that; but "know before you run
 * out" is only fully true once a taken dose spends a pill. That is its own
 * change — it has to decide what a skipped or missed Occurrence costs, and it
 * touches every path that answers one.
 *
 * The same gap is why this read has one caller. Until the supply moves on its
 * own, the only thing that changes a projection is the edit made on the surface
 * this read feeds, so reconciling there is sufficient. Once doses spend pills,
 * this needs a foreground trigger of its own, beside the missed sweep.
 *
 * ## Why a failed write does not fail the read
 *
 * Unlike Adherence, the figure on screen is correct with or without it. A
 * projection is computed from the medication in front of it; the Backlog entry
 * is a courtesy to a future moment. So the writes are reported rather than
 * thrown — the timeline takes the same view of its own sweep.
 */

export interface RefillRow {
  readonly medication: Reminder;
  readonly projection: RefillProjection;
}

export interface RefillReading {
  /** Every active medication, soonest to run out first. */
  readonly rows: readonly RefillRow[];
  /** Names of the Refill Reminders this pass added to the Backlog. */
  readonly raised: readonly string[];
  /** Names of the Refill Reminders this pass withdrew as no longer needed. */
  readonly retired: readonly string[];
}

/**
 * Sort key for a row: how many days until the supply runs out.
 *
 * A medication with no projection sorts last rather than first, which is the
 * only ordering that reads correctly — "nothing recorded" is not urgent, and a
 * missing number is not a zero. Same for a supply that outlasts the walk.
 */
function daysUntilEmpty(row: RefillRow): number {
  if (!row.projection.projected) return Number.POSITIVE_INFINITY;
  return row.projection.daysRemaining ?? Number.POSITIVE_INFINITY;
}

/**
 * Project this user's medications and reconcile the Backlog with the result.
 *
 * `now` is a parameter rather than a call to `Date.now()` inside so a caller can
 * state the instant it means, matching `readAdherence` and `runMissedSweep`.
 */
export async function readRefills(
  userId: string,
  now: number = Date.now()
): Promise<RefillReading> {
  // Stopped medications included, because `reconcileRefills` withdraws their
  // standing warnings — a Refill Reminder for a treatment somebody came off is
  // an instruction to buy medicine they were told to stop taking. They are
  // filtered out of `rows` below: withdrawing a warning about a medication is
  // not a reason to list it among the ones still being taken.
  const medications = await remindersRepo.list(userId, "medication", {
    includeInactive: true,
  });
  // Active tasks only, which is what `list` gives by default — a withdrawn
  // Refill Reminder must not count as one already standing, or a medication
  // that runs low a second time would never be warned about again.
  const standing = await remindersRepo.list(userId, "task");

  const plan = reconcileRefills({
    medications,
    standing,
    now,
    mintId: generateId,
  });

  for (const refillReminder of plan.raise) {
    await remindersRepo.create({
      id: refillReminder.id,
      userId: refillReminder.userId,
      kind: refillReminder.kind,
      name: refillReminder.name,
      schedule: refillReminder.schedule,
      priority: refillReminder.priority,
    });
  }
  for (const standingReminder of plan.retire) {
    await remindersRepo.softDelete(standingReminder.id);
  }

  const rows: readonly RefillRow[] = medications
    .filter((medication) => medication.active)
    .map((medication) => ({
      medication,
      projection: projectRefill(medication, now),
    }))
    .sort((a, b) => daysUntilEmpty(a) - daysUntilEmpty(b));

  return {
    rows,
    raised: plan.raise.map((row) => row.name),
    retired: plan.retire.map((row) => row.name),
  };
}
