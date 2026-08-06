import { describe, expect, it } from "vitest";

import { computeAdherence } from "../adherence/calculator";
import type {
  InstanceStatus,
  Reminder,
  ReminderInstance,
  ScheduleTimes,
} from "../domain/index";
import {
  createReminder,
  createReminderInstance,
  dailyTimes,
  intervalTimes,
  noneTimes,
  onceTimes,
} from "../domain/index";

import {
  GRACE_PERIOD_MS,
  MAX_MISSED_PER_REMINDER,
  SWEEP_LOOKBACK_MS,
  planMissedSweep,
} from "./planner";

const HOUR = 3_600_000;
const DAY = 86_400_000;

/** A fixed instant to hang every fixture off, so failures read as offsets. */
const NOW = Date.UTC(2026, 7, 6, 12, 0, 0);

function reminder(
  id: string,
  schedule: ScheduleTimes,
  overrides: {
    readonly active?: boolean;
    readonly kind?: "medication" | "task";
    readonly createdAt?: number;
  } = {}
): Reminder {
  return createReminder({
    id,
    userId: "user-1",
    kind: overrides.kind ?? "medication",
    name: `Reminder ${id}`,
    schedule,
    // Long before any window these cases use, so a fixture only constrains the
    // "not before it existed" rule when it says so.
    createdAt: overrides.createdAt ?? NOW - 365 * DAY,
    updatedAt: 0,
    ...(overrides.active === undefined ? {} : { active: overrides.active }),
  });
}

function instance(
  id: string,
  reminderId: string,
  scheduledTime: number,
  status: InstanceStatus
): ReminderInstance {
  return createReminderInstance({
    id,
    reminderId,
    userId: "user-1",
    reminderKind: "medication",
    scheduledTime,
    status,
    createdAt: 0,
    updatedAt: 0,
  });
}

/** A reminder that comes due once, `msAgo` before `NOW`. */
function due(id: string, msAgo: number): Reminder {
  return reminder(id, onceTimes(NOW - msAgo));
}

function sweep(
  reminders: readonly Reminder[],
  instances: readonly ReminderInstance[],
  options: {
    readonly now?: number;
    readonly gracePeriodMs?: number;
    readonly lookbackMs?: number;
  } = {}
): ReturnType<typeof planMissedSweep> {
  return planMissedSweep({
    reminders,
    instances,
    now: options.now ?? NOW,
    gracePeriodMs: options.gracePeriodMs ?? GRACE_PERIOD_MS,
    lookbackMs: options.lookbackMs ?? SWEEP_LOOKBACK_MS,
  });
}

/**
 * ADR 0005 is binding, and these two constants are the only place its numbers
 * live in code. The subject here is conformance to that decision, not the
 * sweep's behaviour — the behaviour is pinned by the boundary cases below,
 * which state their own values rather than reading these.
 */
describe("the values ADR 0005 settled", () => {
  it("keeps an occurrence answerable for two hours", () => {
    expect(GRACE_PERIOD_MS).toBe(2 * HOUR);
  });

  it("reaches seven days back in one sweep", () => {
    expect(SWEEP_LOOKBACK_MS).toBe(7 * DAY);
  });
});

describe("planMissedSweep", () => {
  it("misses an occurrence nobody answered once its grace has elapsed", () => {
    const r = due("r1", 3 * HOUR);

    expect(sweep([r], [])).toEqual([
      {
        reminderId: "r1",
        reminderKind: "medication",
        scheduledTime: NOW - 3 * HOUR,
        instanceId: undefined,
      },
    ]);
  });

  it("leaves an occurrence alone while it is still answerable", () => {
    const r = due("r1", HOUR);

    expect(sweep([r], [])).toEqual([]);
  });

  it("holds the grace boundary: answerable at the edge, missed one ms past it", () => {
    const atEdge = reminder("r1", onceTimes(NOW - 2 * HOUR));
    const pastEdge = reminder("r2", onceTimes(NOW - 2 * HOUR - 1));

    expect(sweep([atEdge], [], { gracePeriodMs: 2 * HOUR })).toEqual([]);
    expect(
      sweep([pastEdge], [], { gracePeriodMs: 2 * HOUR }).map(
        (v) => v.reminderId
      )
    ).toEqual(["r2"]);
  });

  it("leaves an occurrence that has not come due at all", () => {
    const r = reminder("r1", onceTimes(NOW + HOUR));

    expect(sweep([r], [])).toEqual([]);
  });

  it("names the stored instance when one already exists, so it is settled rather than duplicated", () => {
    const r = due("r1", 3 * HOUR);
    const stored = instance("i1", "r1", NOW - 3 * HOUR, "pending");

    expect(sweep([r], [stored])).toEqual([
      {
        reminderId: "r1",
        reminderKind: "medication",
        scheduledTime: NOW - 3 * HOUR,
        instanceId: "i1",
      },
    ]);
  });

  it("holds the grace boundary for a stored instance too", () => {
    const r = reminder("r1", noneTimes());
    const atEdge = instance("i1", "r1", NOW - 2 * HOUR, "pending");
    const pastEdge = instance("i2", "r1", NOW - 2 * HOUR - 1, "pending");

    expect(sweep([r], [atEdge], { gracePeriodMs: 2 * HOUR })).toEqual([]);
    expect(
      sweep([r], [pastEdge], { gracePeriodMs: 2 * HOUR }).map(
        (v) => v.instanceId
      )
    ).toEqual(["i2"]);
  });

  it("holds the lookback boundary for a stored instance too", () => {
    const r = reminder("r1", noneTimes());
    const onBound = instance("i1", "r1", NOW - 7 * DAY, "pending");

    expect(
      sweep([r], [onBound], { lookbackMs: 7 * DAY }).map((v) => v.instanceId)
    ).toEqual(["i1"]);
  });

  it("settles both rows when two pending instances claim one moment", () => {
    // Two should not exist. Two do: answering mints a duplicate `pending`
    // instance beside the answered one, which the timeline projection collapses
    // for display. Each row still has to be settled, or the survivor sits
    // `pending` forever and stays out of adherence.
    const r = due("r1", 3 * HOUR);
    const first = instance("i1", "r1", NOW - 3 * HOUR, "pending");
    const second = instance("i2", "r1", NOW - 3 * HOUR, "pending");

    expect(
      sweep([r], [first, second])
        .map((v) => v.instanceId)
        .sort()
    ).toEqual(["i1", "i2"]);
  });

  it("settles a stored pending instance the schedule no longer predicts", () => {
    // The reminder was rescheduled after the person opened the answer screen,
    // so the moment has an Instance but no Occurrence behind it any more.
    const r = reminder("r1", onceTimes(NOW + DAY));
    const stored = instance("i1", "r1", NOW - 3 * HOUR, "pending");

    expect(sweep([r], [stored]).map((v) => v.instanceId)).toEqual(["i1"]);
  });

  it("never overrules an answer the person gave", () => {
    const answered: readonly InstanceStatus[] = [
      "completed",
      "skipped",
      "snoozed",
      "missed",
    ];

    for (const status of answered) {
      const r = due("r1", 3 * HOUR);
      const stored = instance("i1", "r1", NOW - 3 * HOUR, status);

      expect(sweep([r], [stored])).toEqual([]);
    }
  });

  it("holds the lookback boundary: on the bound is swept, older than it is not", () => {
    const onBound = reminder("r1", onceTimes(NOW - 7 * DAY));
    const older = reminder("r2", onceTimes(NOW - 7 * DAY - 1));

    expect(
      sweep([onBound], [], { lookbackMs: 7 * DAY }).map((v) => v.scheduledTime)
    ).toEqual([NOW - 7 * DAY]);
    expect(sweep([older], [], { lookbackMs: 7 * DAY })).toEqual([]);
  });

  it("bounds a stored instance by the same lookback as an occurrence", () => {
    const r = reminder("r1", noneTimes());
    const older = instance("i1", "r1", NOW - 7 * DAY - 1, "pending");

    expect(sweep([r], [older], { lookbackMs: 7 * DAY })).toEqual([]);
  });

  it("predicts nothing for a reminder the person stopped", () => {
    const r = reminder("r1", onceTimes(NOW - 3 * HOUR), { active: false });

    expect(sweep([r], [])).toEqual([]);
  });

  it("still settles a stopped reminder's stored instance", () => {
    // Stopping a medication does not unmake the moments already recorded
    // against it — the same rule the timeline projection follows.
    const r = reminder("r1", onceTimes(NOW - 3 * HOUR), { active: false });
    const stored = instance("i1", "r1", NOW - 3 * HOUR, "pending");

    expect(sweep([r], [stored]).map((v) => v.instanceId)).toEqual(["i1"]);
  });

  it("never misses a backlog reminder, which has no moments to miss", () => {
    const r = reminder("r1", noneTimes());

    expect(sweep([r], [])).toEqual([]);
  });

  it("carries the kind, so adherence can tell a dose from a chore", () => {
    const r = reminder("r1", onceTimes(NOW - 3 * HOUR), { kind: "task" });

    expect(sweep([r], []).map((v) => v.reminderKind)).toEqual(["task"]);
  });

  it("takes a stored instance's own kind rather than looking for its reminder", () => {
    const stored = createReminderInstance({
      id: "i1",
      reminderId: "r-gone",
      userId: "user-1",
      reminderKind: "task",
      scheduledTime: NOW - 3 * HOUR,
      status: "pending",
      createdAt: 0,
      updatedAt: 0,
    });

    expect(sweep([], [stored])).toEqual([
      {
        reminderId: "r-gone",
        reminderKind: "task",
        scheduledTime: NOW - 3 * HOUR,
        instanceId: "i1",
      },
    ]);
  });

  it("orders chronologically, tie-breaking on the reminder", () => {
    const early = due("r2", 5 * HOUR);
    const lateB = due("b", 3 * HOUR);
    const lateA = due("a", 3 * HOUR);

    expect(sweep([lateB, lateA, early], []).map((v) => v.reminderId)).toEqual([
      "r2",
      "a",
      "b",
    ]);
  });

  it("misses every unanswered moment of a recurring schedule inside the window", () => {
    // NOW is 12:00, so each of the three 09:00s inside a three-day lookback is
    // both past its grace and inside the window — and the 09:00 four days back
    // is outside it.
    const r = reminder("r1", dailyTimes(["09:00"]));

    expect(
      sweep([r], [], { lookbackMs: 3 * DAY }).map((v) => v.scheduledTime)
    ).toEqual([
      NOW - 3 * DAY + 21 * HOUR,
      NOW - 2 * DAY + 21 * HOUR,
      NOW - DAY + 21 * HOUR,
    ]);
  });

  it("invents no moment from before the reminder existed", () => {
    // A daily medication added an hour ago. Day-based generation has no start of
    // its own, so without the createdAt bound this reports a week of doses the
    // person never had the medication for — and adherence falls for a treatment
    // that had not begun.
    const r = reminder("r1", dailyTimes(["09:00"]), {
      createdAt: NOW - HOUR,
    });

    expect(sweep([r], [])).toEqual([]);
  });

  it("counts a moment the reminder was created for", () => {
    const at = NOW - 3 * HOUR;
    const r = reminder("r1", onceTimes(at), { createdAt: at });

    expect(sweep([r], []).map((v) => v.scheduledTime)).toEqual([at]);
  });

  it("returns a bounded plan for a schedule dense enough to fill the window", () => {
    // An interval of a hundredth of an hour is not reachable from any surface;
    // it is the shape a corrupt or hostile schedule column takes. The rule is
    // that one reminder's contribution stays bounded, so the sweep returns at
    // all instead of materialising a row for every 36 seconds of a week.
    const r = reminder("r1", intervalTimes(0.01, NOW - 7 * DAY));

    const verdicts = sweep([r], []);

    expect(verdicts.length).toBeGreaterThan(0);
    expect(verdicts.length).toBeLessThanOrEqual(MAX_MISSED_PER_REMINDER);
  });
});

describe("what the sweep does to adherence", () => {
  it("stops one dose in thirty from reading as perfect", () => {
    // The defect issue #11 exists for. Instances are minted only when someone
    // acts, and adherence excludes `pending`, so before the sweep the single
    // dose this person took was the only row that existed — and it scored 100%.
    const start = NOW - 30 * DAY;
    const r = reminder("r1", dailyTimes(["09:00"]), { createdAt: start });
    const taken = instance("i1", "r1", NOW - 21 * HOUR, "completed");

    const swept = planMissedSweep({
      reminders: [r],
      instances: [taken],
      now: NOW,
      gracePeriodMs: GRACE_PERIOD_MS,
      // The whole month, so the arithmetic is about the rule and not about the
      // bound — which ADR 0005 already documents as leaving an older gap absent.
      lookbackMs: 30 * DAY,
    });

    const minted = swept.map((verdict) =>
      instance(
        `swept-${String(verdict.scheduledTime)}`,
        verdict.reminderId,
        verdict.scheduledTime,
        "missed"
      )
    );

    const before = computeAdherence([taken], start, NOW);
    const after = computeAdherence([taken, ...minted], start, NOW);

    expect(before.percent).toBe(100);
    expect(after.percent).toBeLessThan(10);
    expect(after.missed).toBe(after.total - 1);
  });
});
