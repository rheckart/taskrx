/**
 * The Refill Reminder: what to raise, and when.
 *
 * Ported from `RefillPlanner.Plan` in `TaskRx.Core/RefillPlanner.cs` on the
 * `archive/dotnet-maui` branch (paired with `RefillDecision.cs`). Three
 * differences from the C# source, all deliberate.
 *
 * - **Result shape.** The C# returns a `RefillDecision(bool NeedsRefill,
 *   Reminder? RefillReminder)` record, where `RefillReminder` is `null`
 *   whenever `NeedsRefill` is `false`. Here that pairing is a discriminated
 *   union on `needsRefill`, so a caller that has checked `needsRefill` never
 *   has to null-check `refillReminder` afterwards — the `false` variant has no
 *   `refillReminder` property at all.
 * - **Reminder construction is a caller input, not a side effect.** The C#
 *   builds the refill `Reminder` with `Id = Guid.Empty` and leaves
 *   `CreatedAt`/`UpdatedAt` at their C# default (the zero `DateTimeOffset`).
 *   This port has no equivalent implicit zero value, and being pure, must not
 *   call `crypto.randomUUID()` or `Date.now()` itself — so the id and
 *   timestamp for a newly planned refill reminder are supplied by the caller
 *   via `PlanRefillOptions` rather than invented here.
 * - **The threshold is gone**, and with it the `dosesInLookaheadWindow`
 *   parameter. `RefillPlanner.cs:27` raised a refill when `pillsRemaining <=
 *   dosesInLookaheadWindow * 3` — a comparison of pills against doses, which
 *   ignores `pillsPerDose` entirely (the C# only ever checks that field for
 *   being positive) and never has a date to show. Issue #17 rules both out.
 *   `projectRefill` now decides, from the supply, the Schedule and the warning
 *   window the Reminder itself carries; this function asks it and builds the
 *   Reminder. Keeping the old threshold beside the new projection would have
 *   left two rules that disagree, one of them on screen.
 */

import type { Reminder } from "../domain/reminder";
import { createReminder } from "../domain/reminder";
import { noneTimes } from "../domain/schedule-times";

import { projectRefill } from "./projection";

export interface PlanRefillOptions {
  /** Id for the generated refill `Reminder`; C#'s `Guid.Empty` (RefillPlanner.cs:37). */
  readonly refillReminderId: string;
  /**
   * The instant the plan is made: both the `createdAt`/`updatedAt` of the
   * generated refill `Reminder` and the point the supply is projected forward
   * from, in unix ms. One value, so the Reminder cannot be stamped at a moment
   * other than the one it was judged at.
   */
  readonly now: number;
}

export type RefillDecision =
  | { readonly needsRefill: false }
  | { readonly needsRefill: true; readonly refillReminder: Reminder };

/**
 * What a Refill Reminder for `medication` is called.
 *
 * Exported because it is also the only handle a caller has on one it raised
 * earlier: the raise has to be idempotent — a screen that can be opened twice
 * must not leave two — and this name is what identifies the Reminder already
 * standing. Stated here so the raiser and the check cannot drift apart.
 */
export function refillReminderName(medication: Reminder): string {
  return `Refill ${medication.name}`;
}

/**
 * The Refill Reminder to raise for `medication`, if one is due.
 *
 * A `task`, not a `medication`: collecting a prescription is an errand, and
 * ADR 0003 keeps tasks out of the medication notification Horizon so that
 * raising one can never cost a dose its alarm.
 */
export function planRefill(
  medication: Reminder,
  options: PlanRefillOptions
): RefillDecision {
  const projection = projectRefill(medication, options.now);
  if (!projection.projected || !projection.needsRefill) {
    return { needsRefill: false };
  }

  const refillReminder = createReminder({
    id: options.refillReminderId,
    userId: medication.userId,
    kind: "task",
    name: refillReminderName(medication),
    createdAt: options.now,
    updatedAt: options.now,
    priority: "high",
    active: true,
    // `noneTimes()`, where the C# had a daily schedule with no times. That
    // variant did not exist when this was ported, and an empty daily schedule
    // is precisely the misconfiguration `none` was added to replace: it
    // produces no Occurrences while claiming to be a daily schedule, so the
    // raised Reminder would reach neither the timeline nor the Backlog and
    // nobody would ever see it. `none` is what the Backlog is keyed on, and the
    // Backlog is where an errand with no fixed moment belongs.
    schedule: noneTimes(),
    tags: [],
    caregiverIds: [],
  });

  return { needsRefill: true, refillReminder };
}
