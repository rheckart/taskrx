/**
 * The Backlog completion rule: what Instance should exist when someone acts on
 * a Reminder that has no Schedule.
 *
 * A Backlog Reminder produces no Occurrences, so there is no due-time to key its
 * Reminder Instance by — and yet completing one has to record something. ADR
 * 0002 settled that: the Instance is minted at the moment the person acts, with
 * `scheduledTime` equal to the completion time, so `scheduledTime` means "the
 * instant this Instance occupied on the timeline" for both halves of the domain.
 * It stays non-null, which is what leaves the four time-ordered indexes over it
 * valid.
 *
 * The decision is here rather than in the screen that renders the Backlog for
 * the reason `docs/specs/fused-timeline.md` gives: the on-device repositories
 * persist what the pure functions decide, and this is the only place the rule is
 * written down.
 *
 * Pure: no clock, no ids, no database. The caller supplies the instant and
 * generates the id.
 */

import type { ReminderKind } from "../domain/enums";
import type { Reminder } from "../domain/reminder";

/**
 * The Instance a completed Backlog Reminder should produce.
 *
 * Deliberately not a whole `ReminderInstance`: identity and the bookkeeping
 * timestamps belong to whoever writes the row, and inventing them here would
 * make this function impure for no gain.
 */
export interface BacklogCompletion {
  readonly reminderId: string;
  readonly reminderKind: ReminderKind;
  readonly scheduledTime: number;
  readonly status: "completed";
  readonly completedAt: number;
}

/**
 * Whether `reminder` belongs to the Backlog.
 *
 * Only the `none` variant counts. A `daily` schedule with an empty times array
 * also produces no Occurrences, but it is a misconfiguration rather than a
 * commitment with no moment attached — telling the two apart in the type is what
 * `none` exists for, and treating them alike here would put the accident back.
 */
export function isBacklog(reminder: Reminder): boolean {
  return reminder.schedule.type === "none";
}

/**
 * The Instance to mint when a Backlog Reminder is completed at `completedAt`.
 *
 * Throws for a Reminder that has a Schedule. Such a Reminder's moments are
 * predicted, and an Instance minted at "now" instead would sit at a moment no
 * Occurrence claims: it would dodge the Reminder-and-scheduled-time pairing the
 * timeline and the sweep both deduplicate on, appear as a second row beside the
 * moment it was meant to answer, and be counted twice by Adherence. A throw
 * rather than a silent correction, because there is no sensible moment to
 * substitute — only the caller knows which Occurrence it meant.
 */
export function planBacklogCompletion(
  reminder: Reminder,
  completedAt: number
): BacklogCompletion {
  if (!isBacklog(reminder)) {
    throw new Error(
      `Reminder ${reminder.id} has a ${reminder.schedule.type} schedule, so it is not in the Backlog; complete the occurrence it came due at instead.`
    );
  }

  return {
    reminderId: reminder.id,
    reminderKind: reminder.kind,
    scheduledTime: completedAt,
    status: "completed",
    completedAt,
  };
}
