/**
 * The medication refill planner, ported from `RefillPlanner.Plan` in
 * `TaskRx.Core/RefillPlanner.cs` on the `archive/dotnet-maui` branch (paired
 * with `RefillDecision.cs`).
 *
 * Two differences from the C# source, both deliberate:
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
 *
 * The threshold formula (`dosesInLookaheadWindow * 3`, RefillPlanner.cs:27) is
 * carried over unchanged — deliberate product behaviour, not a bug — and does
 * not depend on `pillsPerDose` at all; that field is only ever checked for
 * being a positive number.
 */

import type { Reminder } from "../domain/reminder";
import { createReminder } from "../domain/reminder";
import { dailyTimes } from "../domain/schedule-times";

/** `dosesInLookaheadWindow * 3` (RefillPlanner.cs:27). */
const THRESHOLD_DOSES_PER_WINDOW_DOSE = 3;

export interface PlanRefillOptions {
  /** Id for the generated refill `Reminder`; C#'s `Guid.Empty` (RefillPlanner.cs:37). */
  readonly refillReminderId: string;
  /** `createdAt`/`updatedAt` for the generated refill `Reminder`, unix ms. */
  readonly now: number;
}

export type RefillDecision =
  | { readonly needsRefill: false }
  | { readonly needsRefill: true; readonly refillReminder: Reminder };

/**
 * Decides whether `medication` needs a refill reminder within the next
 * `dosesInLookaheadWindow` doses, per `RefillPlanner.Plan` (RefillPlanner.cs:5-49).
 *
 * No refill is planned when: `medication.kind` is not `"medication"`;
 * `pillsRemaining` is absent; `pillsPerDose` is absent or not positive; or
 * `dosesInLookaheadWindow` is not positive. Otherwise a refill is needed when
 * `pillsRemaining <= dosesInLookaheadWindow * 3`.
 */
export function planRefill(
  medication: Reminder,
  dosesInLookaheadWindow: number,
  options: PlanRefillOptions
): RefillDecision {
  if (medication.kind !== "medication") return { needsRefill: false };
  if (medication.pillsRemaining === undefined) return { needsRefill: false };
  if (medication.pillsPerDose === undefined || medication.pillsPerDose <= 0) {
    return { needsRefill: false };
  }
  if (dosesInLookaheadWindow <= 0) return { needsRefill: false };

  const threshold = dosesInLookaheadWindow * THRESHOLD_DOSES_PER_WINDOW_DOSE;
  const needsRefill = medication.pillsRemaining <= threshold;

  if (!needsRefill) return { needsRefill: false };

  const refillReminder = createReminder({
    id: options.refillReminderId,
    userId: medication.userId,
    kind: "task",
    name: `Refill ${medication.name}`,
    createdAt: options.now,
    updatedAt: options.now,
    priority: "high",
    active: true,
    schedule: dailyTimes([]),
    tags: [],
    caregiverIds: [],
  });

  return { needsRefill: true, refillReminder };
}
