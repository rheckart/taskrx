/**
 * The missed sweep: which unanswered moments have run out of time.
 *
 * This is the second pure rule `docs/specs/fused-timeline.md` names — "given the
 * same inputs plus a Grace Period and a lookback bound, it returns the Instances
 * that should be minted as Missed. The caller persists them; the planner decides
 * them."
 *
 * ## Why it has to exist at all
 *
 * A Reminder Instance is minted lazily, only when someone acts on a moment. An
 * Occurrence nobody answers therefore produces no row, and once it falls behind
 * the forward-looking Occurrence window it is not merely unanswered — it is
 * gone. Adherence counts Instances and excludes `pending` ones, so before this
 * planner a person who took one dose out of thirty scored 100%: the one they
 * took was the only moment that ever became a row.
 *
 * ## The two verdicts
 *
 * A moment out of time is in one of two states, and the caller does different
 * things with them:
 *
 * - **No Instance exists.** One must be minted, already `missed`. That is the
 *   common case, because minting happens only on an answer.
 * - **A `pending` Instance exists.** Someone opened the answer screen and left
 *   without answering. The row is already there and moves to `missed`.
 *
 * Both are reported as a `MissedVerdict`; `instanceId` is what separates them.
 *
 * ## What it will not touch
 *
 * Any Instance that is not `pending` is an answer, and an answer is never
 * overruled — including `snoozed`, which is a deliberate deferral rather than
 * an absence, and `missed`, which is already the verdict. A moment with an
 * answer also blocks the Occurrence behind it from being minted a second time,
 * on the same Reminder-and-scheduled-time pairing the timeline deduplicates on.
 *
 * The Grace Period and the lookback bound are arguments rather than constants
 * read from inside, so a test states the boundary it is pinning. The values the
 * app passes are the exported constants below, settled in ADR 0005.
 *
 * Pure: no clock, no database, no globals. The caller supplies `now`.
 */

import type { ReminderKind } from "../domain/enums";
import type { Reminder, ReminderInstance } from "../domain/reminder";
import { nextOccurrences } from "../schedule/occurrences";
import { timelineRowKey } from "../timeline/projection";

/**
 * How long an Occurrence stays answerable after its moment passes — ADR 0005.
 *
 * Two hours is the width of "I took it with lunch instead of at breakfast".
 * Shorter converts ordinary lateness into a permanent `missed` row; longer
 * leaves the morning still unsettled when the evening comes due.
 */
export const GRACE_PERIOD_MS = 2 * 3_600_000;

/**
 * How far back one sweep reaches — ADR 0005.
 *
 * A week is long enough to cover a phone left flat over a long weekend, and
 * short enough that returning after months does not materialise thousands of
 * rows on one foreground.
 */
export const SWEEP_LOOKBACK_MS = 7 * 86_400_000;

/**
 * The most Occurrences one Reminder may contribute to one sweep.
 *
 * A runaway guard, not a product rule, and explicitly not the row-cap ADR 0005
 * rejects as a way of bounding the sweep — that ADR says why the two are
 * different. The Occurrence engine counts rather than ranges, so some count has
 * to be passed; this is what it is set to, and it stops a `schedule` column
 * holding an interval of seconds from asking for a row every few seconds across
 * the whole window. Reaching it needs more than one Occurrence every ten minutes
 * for seven days straight — well outside anything the app can create.
 *
 * When it does bind, the Occurrences kept are the oldest in the window, because
 * that is the order the engine generates in.
 */
export const MAX_MISSED_PER_REMINDER = 1000;

/**
 * One moment the sweep judged Missed.
 *
 * `instanceId` is the stored row to move, or `undefined` when no row exists and
 * one must be minted at this pairing. `reminderKind` travels with the verdict
 * because it is denormalized onto the Instance and decides whether the row ever
 * counts toward Adherence — the caller must not have to go and find it.
 */
export interface MissedVerdict {
  readonly reminderId: string;
  readonly reminderKind: ReminderKind;
  readonly scheduledTime: number;
  readonly instanceId: string | undefined;
}

export interface PlanMissedSweepInput {
  /**
   * Every Reminder that could contribute. Only `active` ones predict moments;
   * an inactive one's stored Instances are still settled, because stopping a
   * medication does not unmake what was already recorded against it.
   */
  readonly reminders: readonly Reminder[];
  /**
   * The stored Instances over at least the lookback window. Anything outside it
   * is ignored, so a caller may hand over more without changing the result.
   */
  readonly instances: readonly ReminderInstance[];
  readonly now: number;
  readonly gracePeriodMs: number;
  readonly lookbackMs: number;
}

export function planMissedSweep(
  input: PlanMissedSweepInput
): readonly MissedVerdict[] {
  const { reminders, instances, now, gracePeriodMs, lookbackMs } = input;

  // The half-open window `[floor, deadline)` of moments that are out of time.
  // Inclusive at the floor, matching the timeline's inclusive window start;
  // exclusive at the deadline, because a moment exactly one Grace Period old is
  // still answerable — that is what the Grace Period means.
  const floor = now - lookbackMs;
  const deadline = now - gracePeriodMs;

  const outOfTime = (moment: number): boolean =>
    moment >= floor && moment < deadline;

  const verdicts: MissedVerdict[] = [];

  // Stored rows first, so a moment that already has one is never also minted.
  // Every Instance claims its pairing, whatever its status: an answered one
  // blocks the Occurrence behind it, and a pending one is settled here.
  const claimed = new Set<string>();
  for (const stored of instances) {
    if (!outOfTime(stored.scheduledTime)) continue;

    claimed.add(timelineRowKey(stored.reminderId, stored.scheduledTime));
    if (stored.status !== "pending") continue;

    verdicts.push({
      reminderId: stored.reminderId,
      // Off the Instance, not off its parent: the row carries its own kind, and
      // it is settled even when the reminder is not in `reminders` at all.
      reminderKind: stored.reminderKind,
      scheduledTime: stored.scheduledTime,
      instanceId: stored.id,
    });
  }

  for (const reminder of reminders) {
    if (!reminder.active) continue;

    // Never before the Reminder existed. `daily` and `weekly` generation has no
    // start of its own — it walks calendar days from wherever it is anchored —
    // so without this a medication added this morning would be swept back across
    // the whole lookback and recorded as seven days of missed doses nobody could
    // have taken, driving Adherence down for a treatment that had not started.
    // An occurrence landing exactly on `createdAt` counts: setting a reminder up
    // for a moment is not the same as missing that moment.
    const from = Math.max(floor, reminder.createdAt) - 1;

    // `nextOccurrences` excludes a moment landing exactly on its anchor, hence
    // the millisecond above: it keeps both bounds inclusive.
    const occurrences = nextOccurrences(
      reminder,
      from,
      MAX_MISSED_PER_REMINDER
    );

    for (const occurrence of occurrences) {
      if (occurrence.fireAt >= deadline) break;
      if (claimed.has(timelineRowKey(reminder.id, occurrence.fireAt))) continue;

      verdicts.push({
        reminderId: reminder.id,
        reminderKind: reminder.kind,
        scheduledTime: occurrence.fireAt,
        instanceId: undefined,
      });
    }
  }

  // Chronological, tie-broken on the reminder, for the reason the projection
  // gives at length: `Array.sort` is stable only with respect to the input
  // array, and the input order here is the caller's.
  return verdicts.sort((a, b) => {
    if (a.scheduledTime !== b.scheduledTime) {
      return a.scheduledTime - b.scheduledTime;
    }
    return a.reminderId < b.reminderId ? -1 : 1;
  });
}
