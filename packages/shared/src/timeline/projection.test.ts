import { describe, expect, it } from "vitest";

import { createReminder, createReminderInstance } from "../domain/reminder";
import type { Reminder, ReminderInstance } from "../domain/reminder";
import { dailyTimes, intervalTimes } from "../domain/schedule-times";

import { projectTimeline } from "./projection";

/**
 * The timeline projection — the rule underneath issue #10's screen.
 *
 * These cases state rules, per `docs/specs/fused-timeline.md`: what the day
 * contains, in what order, and which entry the person should act on. They do
 * not assert shipped shapes — no case says "returns three rows" as its subject,
 * and none reaches into how the union was assembled.
 *
 * The two halves being fused are of genuinely different kinds. An Occurrence is
 * computed from a Schedule and has no identity; an Instance is a stored record
 * of what became of one. The interesting rules are all at the seam: the same
 * moment must not appear twice, and when both exist the stored answer is the
 * one that counts.
 *
 * `vitest.config.ts` pins `TZ=UTC`, so local calendar arithmetic and UTC agree
 * and the fixtures below can be written as plain `Date.UTC` values.
 */

const DAY_START = Date.UTC(2026, 7, 5, 0, 0, 0, 0);
const DAY_END = Date.UTC(2026, 7, 5, 23, 59, 59, 999);
const DAY = { start: DAY_START, end: DAY_END };

const PREVIOUS_DAY = {
  start: Date.UTC(2026, 7, 4, 0, 0, 0, 0),
  end: Date.UTC(2026, 7, 4, 23, 59, 59, 999),
};

function at(hour: number, minute = 0): number {
  return Date.UTC(2026, 7, 5, hour, minute, 0, 0);
}

function medication(overrides: Partial<Reminder> = {}): Reminder {
  return {
    ...createReminder({
      id: "rem-metformin",
      userId: "user-1",
      kind: "medication",
      name: "Metformin",
      dosage: "500mg",
      schedule: dailyTimes(["09:00"]),
      createdAt: 0,
      updatedAt: 0,
    }),
    ...overrides,
  };
}

function answered(overrides: Partial<ReminderInstance> = {}): ReminderInstance {
  return {
    ...createReminderInstance({
      id: "inst-1",
      reminderId: "rem-metformin",
      userId: "user-1",
      reminderKind: "medication",
      scheduledTime: at(9),
      status: "completed",
      createdAt: 0,
      updatedAt: 0,
    }),
    ...overrides,
  };
}

/** The moment/status pairs a row reduces to, which is what the rules are about. */
function shape(
  rows: ReturnType<typeof projectTimeline>
): readonly (readonly [number, string])[] {
  return rows.map((row) => [row.scheduledTime, row.status] as const);
}

describe("projectTimeline ordering", () => {
  it("returns the day in chronological order regardless of input order", () => {
    const rows = projectTimeline({
      reminders: [
        medication({ id: "rem-evening", schedule: dailyTimes(["21:00"]) }),
        medication({ id: "rem-morning", schedule: dailyTimes(["09:00"]) }),
        medication({ id: "rem-midday", schedule: dailyTimes(["13:00"]) }),
      ],
      instances: [],
      window: DAY,
      now: DAY_START,
    });

    expect(rows.map((row) => row.scheduledTime)).toEqual([
      at(9),
      at(13),
      at(21),
    ]);
  });

  /**
   * Two reminders due at the same instant need a total order, or the list
   * reshuffles between renders of identical data. Same tie-break as
   * `selectUpcomingOccurrences`, and for the same reason.
   */
  it("breaks a tie on reminder id so equal moments do not reshuffle", () => {
    const input = {
      instances: [],
      window: DAY,
      now: DAY_START,
    };
    const forwards = projectTimeline({
      ...input,
      reminders: [
        medication({ id: "rem-b", schedule: dailyTimes(["09:00"]) }),
        medication({ id: "rem-a", schedule: dailyTimes(["09:00"]) }),
      ],
    });
    const backwards = projectTimeline({
      ...input,
      reminders: [
        medication({ id: "rem-a", schedule: dailyTimes(["09:00"]) }),
        medication({ id: "rem-b", schedule: dailyTimes(["09:00"]) }),
      ],
    });

    expect(forwards.map((row) => row.reminderId)).toEqual(["rem-a", "rem-b"]);
    expect(backwards.map((row) => row.reminderId)).toEqual(["rem-a", "rem-b"]);
  });
});

describe("projectTimeline union of occurrences and instances", () => {
  it("shows an occurrence nobody has answered as still pending", () => {
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [],
      window: DAY,
      now: DAY_START,
    });

    expect(shape(rows)).toEqual([[at(9), "pending"]]);
    expect(rows[0]?.instanceId).toBeUndefined();
  });

  /**
   * The deduplication rule, and the reason the projection is a union rather
   * than a concatenation: the 09:00 dose is both a moment the schedule predicts
   * and a moment the person answered. It is one entry in their day.
   */
  it("shows an answered occurrence once, in the state it was answered", () => {
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [answered({ status: "completed" })],
      window: DAY,
      now: DAY_START,
    });

    expect(shape(rows)).toEqual([[at(9), "completed"]]);
    expect(rows[0]?.instanceId).toBe("inst-1");
  });

  it("pairs an instance to its occurrence by reminder and moment together", () => {
    // Same reminder, a moment the schedule does not predict: this is a distinct
    // entry, not the 09:00 one relabelled. Pairing on reminder alone would
    // swallow it; pairing on moment alone would attach it to another reminder.
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [answered({ id: "inst-late", scheduledTime: at(11) })],
      window: DAY,
      now: DAY_START,
    });

    expect(shape(rows)).toEqual([
      [at(9), "pending"],
      [at(11), "completed"],
    ]);
  });

  it("does not let one reminder's answer settle another's occurrence", () => {
    const rows = projectTimeline({
      reminders: [
        medication({ id: "rem-a", schedule: dailyTimes(["09:00"]) }),
        medication({ id: "rem-b", schedule: dailyTimes(["09:00"]) }),
      ],
      instances: [
        answered({ id: "inst-a", reminderId: "rem-a", scheduledTime: at(9) }),
      ],
      window: DAY,
      now: DAY_START,
    });

    expect(rows.map((row) => [row.reminderId, row.status] as const)).toEqual([
      ["rem-a", "completed"],
      ["rem-b", "pending"],
    ]);
  });

  it.each([
    { status: "completed", meaning: "a dose the person took" },
    { status: "skipped", meaning: "a dose deliberately declined" },
    { status: "missed", meaning: "a dose that ran out of time" },
    { status: "snoozed", meaning: "a dose pushed back to later" },
  ] as const)("keeps $status on the timeline — $meaning", ({ status }) => {
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [answered({ status })],
      window: DAY,
      now: DAY_END,
    });

    expect(shape(rows)).toEqual([[at(9), status]]);
  });

  it("carries the reminder's identity onto a row so it can be rendered", () => {
    const rows = projectTimeline({
      reminders: [medication({ name: "Metformin XR", dosage: "750mg" })],
      instances: [],
      window: DAY,
      now: DAY_START,
    });

    expect(rows[0]).toMatchObject({
      name: "Metformin XR",
      dosage: "750mg",
      kind: "medication",
      reminderId: "rem-metformin",
    });
  });

  /**
   * The projection is the *fused* one. Tasks have no screen until #13, but the
   * rule must not be medication-shaped, or #13 becomes a rewrite rather than a
   * caller.
   */
  it("projects a task by the same rules, carrying its kind", () => {
    const rows = projectTimeline({
      reminders: [
        medication({
          id: "rem-task",
          kind: "task",
          name: "Call pharmacy",
          dosage: undefined,
          schedule: dailyTimes(["14:00"]),
        }),
      ],
      instances: [],
      window: DAY,
      now: DAY_START,
    });

    expect(rows[0]).toMatchObject({ kind: "task", status: "pending" });
  });
});

describe("projectTimeline window", () => {
  it("leaves out moments beyond the day being viewed", () => {
    const rows = projectTimeline({
      reminders: [medication({ schedule: intervalTimes(6, at(3)) })],
      instances: [],
      window: DAY,
      now: DAY_START,
    });

    for (const row of rows) {
      expect(row.scheduledTime).toBeGreaterThanOrEqual(DAY_START);
      expect(row.scheduledTime).toBeLessThanOrEqual(DAY_END);
    }
    expect(rows.length).toBeGreaterThan(0);
  });

  /**
   * Both bounds are inclusive, and both halves of the union have to honour
   * that — a predicted moment and a recorded one are filtered by different
   * code. All four combinations are pinned across the next two cases; covering
   * only the diagonal let an exclusive bound survive on each side.
   *
   * Midnight is why the lower bound matters: a dose at 00:00 belongs to the day
   * that begins then, and an exclusive bound would drop it from every day it
   * could appear on.
   */
  it("includes a predicted moment landing exactly on either bound", () => {
    const rows = projectTimeline({
      reminders: [
        medication({ id: "rem-midnight", schedule: dailyTimes(["00:00"]) }),
        medication({ id: "rem-evening", schedule: dailyTimes(["21:00"]) }),
      ],
      instances: [],
      // Ends exactly on the evening dose, which `DAY_END` cannot express: no
      // clock time lands on 23:59:59.999.
      window: { start: DAY_START, end: at(21) },
      now: DAY_START,
    });

    expect(rows.map((row) => row.scheduledTime)).toEqual([DAY_START, at(21)]);
  });

  it("includes a recorded moment landing exactly on either bound", () => {
    const rows = projectTimeline({
      reminders: [medication({ active: false })],
      instances: [
        answered({ id: "inst-start", scheduledTime: DAY_START }),
        answered({ id: "inst-end", scheduledTime: DAY_END }),
      ],
      window: DAY,
      now: DAY_START,
    });

    expect(rows.map((row) => row.scheduledTime)).toEqual([DAY_START, DAY_END]);
  });

  it("leaves out a recorded answer from another day", () => {
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [
        answered({ id: "inst-yesterday", scheduledTime: at(9) - 86_400_000 }),
      ],
      window: DAY,
      now: DAY_START,
    });

    expect(shape(rows)).toEqual([[at(9), "pending"]]);
  });

  /** The "view a previous day" criterion, as a property of the window alone. */
  it("projects whichever day it is given, not the one containing now", () => {
    const yesterdayNineAm = at(9) - 86_400_000;
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [
        answered({ id: "inst-yesterday", scheduledTime: yesterdayNineAm }),
      ],
      window: PREVIOUS_DAY,
      now: at(15),
    });

    expect(shape(rows)).toEqual([[yesterdayNineAm, "completed"]]);
  });

  it("says nothing is scheduled by returning nothing at all", () => {
    expect(
      projectTimeline({
        reminders: [],
        instances: [],
        window: DAY,
        now: DAY_START,
      })
    ).toEqual([]);
  });
});

describe("projectTimeline what is next", () => {
  it("points at the soonest thing still unanswered", () => {
    const rows = projectTimeline({
      reminders: [
        medication({ id: "rem-a", schedule: dailyTimes(["09:00"]) }),
        medication({ id: "rem-b", schedule: dailyTimes(["13:00"]) }),
        medication({ id: "rem-c", schedule: dailyTimes(["21:00"]) }),
      ],
      instances: [],
      window: DAY,
      now: at(10),
    });

    expect(rows.filter((row) => row.isNext).map((row) => row.scheduledTime)) //
      .toEqual([at(13)]);
  });

  it("looks past an answered dose to the next unanswered one", () => {
    const rows = projectTimeline({
      reminders: [
        medication({ id: "rem-a", schedule: dailyTimes(["13:00"]) }),
        medication({ id: "rem-b", schedule: dailyTimes(["21:00"]) }),
      ],
      instances: [
        answered({
          id: "inst-early",
          reminderId: "rem-a",
          scheduledTime: at(13),
        }),
      ],
      window: DAY,
      now: at(10),
    });

    expect(rows.filter((row) => row.isNext).map((row) => row.scheduledTime)) //
      .toEqual([at(21)]);
  });

  it("points at nothing once the day holds no unanswered dose ahead", () => {
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [answered({ status: "completed" })],
      window: DAY,
      now: at(23),
    });

    expect(rows.some((row) => row.isNext)).toBe(false);
  });

  /**
   * A dose whose moment has passed but which nobody answered is overdue, not
   * next. Deliberately independent of the Grace Period, which
   * `docs/specs/fused-timeline.md` leaves open for the sweep in #11 — this rule
   * must not quietly pick a value for it.
   */
  it("does not call a moment that has already passed the next one", () => {
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [],
      window: DAY,
      now: at(17),
    });

    expect(shape(rows)).toEqual([[at(9), "pending"]]);
    expect(rows.some((row) => row.isNext)).toBe(false);
  });

  it("points at a dose due at this very moment", () => {
    const rows = projectTimeline({
      reminders: [medication()],
      instances: [],
      window: DAY,
      now: at(9),
    });

    expect(rows[0]?.isNext).toBe(true);
  });

  it("points at one dose only when several share the soonest moment", () => {
    const rows = projectTimeline({
      reminders: [
        medication({ id: "rem-a", schedule: dailyTimes(["09:00"]) }),
        medication({ id: "rem-b", schedule: dailyTimes(["09:00"]) }),
      ],
      instances: [],
      window: DAY,
      now: DAY_START,
    });

    expect(rows.filter((row) => row.isNext).map((row) => row.reminderId)) //
      .toEqual(["rem-a"]);
  });

  it("does not treat a deferred dose as the next thing to do", () => {
    // A snooze is an answer to "not now". Treating it as next would put the
    // person straight back where they declined to be.
    const rows = projectTimeline({
      reminders: [
        medication({ id: "rem-a", schedule: dailyTimes(["13:00"]) }),
        medication({ id: "rem-b", schedule: dailyTimes(["21:00"]) }),
      ],
      instances: [
        answered({
          id: "inst-snoozed",
          reminderId: "rem-a",
          scheduledTime: at(13),
          status: "snoozed",
        }),
      ],
      window: DAY,
      now: at(10),
    });

    expect(rows.filter((row) => row.isNext).map((row) => row.scheduledTime)) //
      .toEqual([at(21)]);
  });
});

describe("projectTimeline reminders it should not schedule", () => {
  it("stops predicting moments for a reminder that is no longer active", () => {
    const rows = projectTimeline({
      reminders: [medication({ active: false })],
      instances: [],
      window: DAY,
      now: DAY_START,
    });

    expect(rows).toEqual([]);
  });

  /** Stopping a medication does not unmake the doses already recorded. */
  it("still shows what was recorded against a stopped reminder", () => {
    const rows = projectTimeline({
      reminders: [medication({ active: false })],
      instances: [answered({ status: "completed" })],
      window: DAY,
      now: DAY_END,
    });

    expect(shape(rows)).toEqual([[at(9), "completed"]]);
  });

  it("drops a recorded answer whose reminder is not in hand", () => {
    // There is no name or dosage to render it with, and inventing one would put
    // a row on the timeline that names nothing.
    const rows = projectTimeline({
      reminders: [],
      instances: [answered({ reminderId: "rem-vanished" })],
      window: DAY,
      now: DAY_START,
    });

    expect(rows).toEqual([]);
  });
});
