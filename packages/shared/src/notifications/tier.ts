/**
 * Tier — the delivery guarantee a Reminder's notifications get.
 *
 * ADR 0003 settles two things this file exists to make executable:
 *
 *   * **Tier is derived from Kind.** A medication is armed with the strongest
 *     exact-alarm primitive the platform offers and selected into the bounded
 *     global Horizon; a task gets an ordinary notification on a separate budget.
 *   * **A task can never displace a dose**, by construction rather than by
 *     policy. It is not in the medication Horizon at all, so there is no
 *     ordering in which a dozen routine task reminders leave an evening insulin
 *     dose unarmed.
 *
 * Tier and Kind coincide today, which is exactly why the mapping is a named
 * function rather than an `=== "medication"` at each call site: the reason a
 * task is excluded from the Horizon is its Tier, and the day the two stop
 * coinciding there is one place to say so.
 *
 * It is emphatically **not** derived from `priority`. That field is stored,
 * validated and parsed from words like "urgent", and read by no scheduling
 * decision anywhere — ADR 0003 rejected sorting by it, because a critical dose
 * four days out would displace an ordinary one arriving in ten minutes. The two
 * priority cases below pin that: a critical task stays out of the Horizon and a
 * low-priority medication stays in it.
 *
 * Pure: no clock, no database, no globals.
 */

import type { ReminderKind } from "../domain/enums";
import type { Reminder } from "../domain/reminder";

export const TIERS = ["medication", "task"] as const;

export type Tier = (typeof TIERS)[number];

/** The Tier a Reminder of `kind` is delivered on. */
export function tierFor(kind: ReminderKind): Tier {
  switch (kind) {
    case "medication":
      return "medication";
    case "task":
      return "task";
  }
}

/**
 * Whether a Reminder is eligible for the bounded medication Horizon.
 *
 * Eligibility only — the Horizon still applies its own rules about which
 * eligible Reminders make the cut, and `active` is one of them.
 */
export function entersMedicationHorizon(reminder: Reminder): boolean {
  return tierFor(reminder.kind) === "medication";
}

/**
 * Whether a Reminder is eligible for the task Horizon — the separate, smaller
 * budget the task Tier is armed from.
 *
 * The two predicates partition the Reminders rather than ranking them. That is
 * the whole of ADR 0003's "by construction": a Kind is admitted to exactly one
 * budget, so there is no ordering, no count of tasks, and no priority value in
 * which a task occupies a slot a dose needed. Filling the task budget entirely
 * costs the medication Horizon nothing, because the two selections never see
 * the same Reminder.
 */
export function entersTaskHorizon(reminder: Reminder): boolean {
  return tierFor(reminder.kind) === "task";
}
