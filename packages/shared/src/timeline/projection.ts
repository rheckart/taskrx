/**
 * The timeline projection: what a person's day looks like, as one ordered list.
 *
 * This is the pure rule `docs/specs/fused-timeline.md` names — "given the
 * person's Reminders, their stored Reminder Instances, a window, and the current
 * instant, it returns the rows the timeline renders". The screen renders what
 * this returns and decides nothing itself.
 *
 * ## Why it is a union and not a query
 *
 * The two halves of a day are different kinds of thing. An **Occurrence** is
 * computed from a Schedule, has no identity, and is never stored. An
 * **Instance** is a stored record of what became of one. A day therefore has to
 * be assembled from both: moments the Schedule predicts and nobody has answered
 * yet, plus moments that have a recorded answer.
 *
 * Both can describe the same moment, so they are deduplicated on the pairing of
 * **Reminder and scheduled time**, which is the key the lazy-lookup path in
 * `reminderInstancesRepo.findPendingForOccurrence` already uses. Where both
 * exist the Instance wins: a stored answer is a fact, and the Occurrence is only
 * a prediction that it would come due — including an Instance at a moment the
 * Schedule does not predict at all, such as one recorded after the fact.
 *
 * Pairing on the pair matters in both directions. On the Reminder alone, a
 * second Occurrence of the same Reminder would swallow the first. On the moment
 * alone, one Reminder's answer would settle another Reminder's Occurrence.
 *
 * ## What this deliberately does not decide
 *
 * It does not mint Instances, and it does not decide that an unanswered
 * Occurrence has become Missed — that is the sweep planner's job
 * (`../sweep/planner.ts`), under the Grace Period ADR 0005 settled. An
 * Occurrence whose moment has passed with no answer therefore stays `pending`
 * here and simply is not "next", until a sweep has run and left a `missed`
 * Instance for this projection to find. Nothing in this file applies a Grace
 * Period of its own.
 *
 * It is also Kind-neutral. Tasks have no screen until the fusion lands, but the
 * rule is the fused one, so that step is a new caller rather than a rewrite.
 *
 * Pure: no clock, no database, no globals. The caller supplies `now`.
 */

import type { InstanceStatus, ReminderKind } from "../domain/enums";
import type { Reminder, ReminderInstance } from "../domain/reminder";
import { nextOccurrences } from "../schedule/occurrences";

/**
 * The span the timeline covers, both bounds **inclusive**.
 *
 * Inclusive at the lower bound is the load-bearing half: an Occurrence at 00:00
 * belongs to the day beginning then, and an exclusive bound would drop it from
 * every day it could appear on.
 */
export interface TimelineWindow {
  readonly start: number;
  readonly end: number;
}

export interface TimelineRow {
  readonly reminderId: string;
  /** `undefined` until the moment has been answered and an Instance exists. */
  readonly instanceId: string | undefined;
  readonly name: string;
  readonly dosage: string | undefined;
  readonly kind: ReminderKind;
  readonly scheduledTime: number;
  /** `pending` for an Occurrence with no Instance behind it. */
  readonly status: InstanceStatus;
  /**
   * The one entry the person should act on now: the soonest unanswered moment
   * at or after `now`. At most one row carries it, and none does on a day whose
   * remaining moments are all answered.
   */
  readonly isNext: boolean;
}

export interface ProjectTimelineInput {
  /**
   * Every Reminder that could contribute to the window. Only `active` ones
   * predict new moments; an inactive one still supplies the name and dosage for
   * moments already recorded against it, because stopping a medication does not
   * unmake the Instances already recorded.
   */
  readonly reminders: readonly Reminder[];
  readonly instances: readonly ReminderInstance[];
  readonly window: TimelineWindow;
  readonly now: number;
}

/**
 * The most moments one Reminder may contribute to one window.
 *
 * A bound is needed because `nextOccurrences` counts rather than ranges, and an
 * `interval` schedule has no horizon of its own. 64 is far past anything a real
 * day holds — one every 22 minutes — so a window is never truncated in practice.
 * A caller projecting a span long enough to exceed it would see the tail
 * missing, which is why this is stated rather than buried.
 *
 * It coincides with the engine's own `DEFAULT_COUNT` without being tied to it:
 * that one is "how many to return when the caller does not say", this one is
 * "how many can fit in a day". They answer different questions and should be
 * free to diverge.
 */
const MAX_OCCURRENCES_PER_REMINDER = 64;

/**
 * Reminder and scheduled time together — the deduplication key, and the same
 * pairing a caller needs to key a rendered list. Exported so the two cannot
 * drift into disagreeing about what makes a row unique.
 */
export function timelineRowKey(
  reminderId: string,
  scheduledTime: number
): string {
  return `${reminderId}@${String(scheduledTime)}`;
}

function withinWindow(moment: number, window: TimelineWindow): boolean {
  return moment >= window.start && moment <= window.end;
}

/**
 * Which of two Instances claiming the same pairing is the one to show.
 *
 * Two should not exist. One does, today: answering mints a duplicate `pending`
 * Instance beside the `completed` one — recorded in `docs/CHANGELOG.md` on
 * 2026-08-03, still open, and found here because a device rendered the same
 * moment twice and React raised "two children with the same key".
 *
 * The pairing is what identifies a moment on the timeline, so it has to hold
 * across stored rows as well as between stored and predicted ones. Resolving it
 * is not a fix for the duplicate — the rows are still both written and both
 * counted by adherence — but the timeline showing a taken medication as
 * simultaneously taken and outstanding is a defect of its own.
 *
 * An answer beats `pending`, because a recorded answer is a fact and `pending`
 * is only the absence of one. Between two answers the later `updatedAt` wins,
 * and an exact tie falls to the instance id so the row cannot flicker between
 * renders of identical data.
 */
function preferredInstance(
  a: ReminderInstance,
  b: ReminderInstance
): ReminderInstance {
  const aAnswered = a.status !== "pending";
  const bAnswered = b.status !== "pending";
  if (aAnswered !== bAnswered) return aAnswered ? a : b;
  if (a.updatedAt !== b.updatedAt) return a.updatedAt > b.updatedAt ? a : b;
  return a.id < b.id ? a : b;
}

export function projectTimeline(
  input: ProjectTimelineInput
): readonly TimelineRow[] {
  const { reminders, instances, window, now } = input;

  const byId = new Map<string, Reminder>();
  for (const reminder of reminders) {
    byId.set(reminder.id, reminder);
  }

  // Stored answers first, so they own their moment before any prediction is
  // considered for it. Keyed by the pairing rather than appended, so two
  // Instances claiming one moment collapse to the one that should be shown.
  const claimed = new Map<
    string,
    { readonly instance: ReminderInstance; readonly reminder: Reminder }
  >();
  for (const instance of instances) {
    if (!withinWindow(instance.scheduledTime, window)) continue;

    const reminder = byId.get(instance.reminderId);
    // Nothing to render it with. Inventing a name would put a row on the
    // timeline that names nothing.
    if (reminder === undefined) continue;

    const key = timelineRowKey(instance.reminderId, instance.scheduledTime);
    const held = claimed.get(key);
    claimed.set(key, {
      reminder,
      instance:
        held === undefined
          ? instance
          : preferredInstance(held.instance, instance),
    });
  }

  const rows: TimelineRow[] = [];
  for (const { instance, reminder } of claimed.values()) {
    rows.push({
      reminderId: reminder.id,
      instanceId: instance.id,
      name: reminder.name,
      dosage: reminder.dosage,
      kind: reminder.kind,
      scheduledTime: instance.scheduledTime,
      status: instance.status,
      isNext: false,
    });
  }

  for (const reminder of reminders) {
    if (!reminder.active) continue;

    // `nextOccurrences` excludes a moment landing exactly on `fromMs`, so the
    // anchor sits one millisecond before the window to keep `start` inclusive.
    const occurrences = nextOccurrences(
      reminder,
      window.start - 1,
      MAX_OCCURRENCES_PER_REMINDER
    );

    for (const occurrence of occurrences) {
      if (occurrence.fireAt > window.end) break;
      if (claimed.has(timelineRowKey(reminder.id, occurrence.fireAt))) continue;

      rows.push({
        reminderId: reminder.id,
        instanceId: undefined,
        name: reminder.name,
        dosage: reminder.dosage,
        kind: reminder.kind,
        scheduledTime: occurrence.fireAt,
        status: "pending",
        isNext: false,
      });
    }
  }

  // Chronological, with an explicit tie-break on `reminderId` for the reason
  // `selectUpcomingOccurrences` gives at length: `Array.sort` is stable only
  // with respect to the input array, and the input order here is the caller's.
  rows.sort((a, b) => {
    if (a.scheduledTime !== b.scheduledTime) {
      return a.scheduledTime - b.scheduledTime;
    }
    return a.reminderId < b.reminderId ? -1 : 1;
  });

  const nextIndex = rows.findIndex(
    (row) => row.status === "pending" && row.scheduledTime >= now
  );
  if (nextIndex === -1) {
    return rows;
  }

  return rows.map((row, index) =>
    index === nextIndex ? { ...row, isNext: true } : row
  );
}
