import { describe, expect, it } from "vitest";

import type { Reminder, ScheduleTimes } from "../domain/index";
import {
  createReminder,
  dailyTimes,
  intervalTimes,
  weeklyTimes,
} from "../domain/index";
import type { Medication } from "../index";

import { selectUpcomingDoses, selectUpcomingOccurrences } from "./horizon";

function med(overrides: Partial<Medication>): Medication {
  return {
    id: "med-1",
    userId: "user-1",
    name: "Test Med",
    rxcui: undefined,
    dosage: undefined,
    scheduleType: "daily",
    scheduleTimes: { times: ["09:00"] },
    active: true,
    createdAt: 0,
    updatedAt: 0,
    deletedAt: undefined,
    ...overrides,
  };
}

/**
 * All tests use UTC (vitest pins TZ=UTC in vitest.config.ts).
 * 2026-07-26T00:00:00Z = 1785196800000.
 */
const NOON_UTC = new Date("2026-07-26T12:00:00Z").getTime();

describe("selectUpcomingDoses", () => {
  it("returns an empty array when maxTotal is 0", () => {
    expect(selectUpcomingDoses([med({})], NOON_UTC, 0)).toHaveLength(0);
  });

  it("returns an empty array when maxTotal is negative", () => {
    expect(selectUpcomingDoses([med({})], NOON_UTC, -5)).toHaveLength(0);
  });

  it("returns an empty array when there are no medications", () => {
    expect(selectUpcomingDoses([], NOON_UTC, 64)).toHaveLength(0);
  });

  it("skips inactive medications entirely", () => {
    const active = med({ id: "active", active: true });
    const inactive = med({ id: "inactive", active: false });
    const result = selectUpcomingDoses([active, inactive], NOON_UTC, 64);
    expect(result.every((d) => d.medicationId === "active")).toBe(true);
  });

  it("caps the returned list at maxTotal even with many meds", () => {
    const meds = Array.from({ length: 10 }, (_, i) =>
      med({ id: `m${String(i)}`, scheduleTimes: { times: ["09:00"] } })
    );
    const result = selectUpcomingDoses(meds, NOON_UTC, 64);
    expect(result).toHaveLength(64);
  });

  it("returns doses in strictly non-decreasing time order", () => {
    const meds = [
      med({ id: "a", scheduleTimes: { times: ["09:00", "21:00"] } }),
      med({ id: "b", scheduleTimes: { times: ["12:00", "18:00"] } }),
      med({ id: "c", scheduleTimes: { times: ["06:00", "15:00"] } }),
    ];
    const result = selectUpcomingDoses(meds, NOON_UTC, 12);
    const times = result.map((d) => d.scheduledTime);
    const sorted = [...times].sort((a, b) => a - b);
    expect(times).toEqual(sorted);
  });

  it("interleaves doses from multiple meds by time, not by med", () => {
    // med A: 13:00, 14:00; med B: 12:30, 13:30
    // Expected order: B@12:30, A@13:00, B@13:30, A@14:00
    const a = med({
      id: "a",
      scheduleType: "interval",
      scheduleTimes: {
        hours: 1,
        startAt: new Date("2026-07-26T13:00:00Z").getTime(),
      },
    });
    const b = med({
      id: "b",
      scheduleType: "interval",
      scheduleTimes: {
        hours: 1,
        startAt: new Date("2026-07-26T12:30:00Z").getTime(),
      },
    });
    const result = selectUpcomingDoses([a, b], NOON_UTC, 4);
    expect(result.map((d) => d.medicationId)).toEqual(["b", "a", "b", "a"]);
  });

  it("prefers closer doses from med B over farther doses from med A", () => {
    // Med A gets its next 4 daily doses (long horizon).
    // Med B has an interval schedule starting soon; its doses should
    // push out A's later ones.
    const a = med({
      id: "a",
      scheduleTimes: { times: ["21:00"] }, // 9pm daily
    });
    const b = med({
      id: "b",
      scheduleType: "interval",
      scheduleTimes: {
        hours: 1,
        startAt: new Date("2026-07-26T13:00:00Z").getTime(),
      },
    });
    // From noon, next 4:
    // b @13:00, b @14:00, b @15:00, b @16:00 (all before a @21:00)
    const result = selectUpcomingDoses([a, b], NOON_UTC, 4);
    expect(result.filter((d) => d.medicationId === "b")).toHaveLength(4);
    expect(result.filter((d) => d.medicationId === "a")).toHaveLength(0);
  });

  it("does not schedule doses from the past", () => {
    const past = med({
      id: "past",
      scheduleTimes: { times: ["06:00"] }, // 6am, already gone by noon
    });
    const result = selectUpcomingDoses([past], NOON_UTC, 4);
    for (const dose of result) {
      expect(dose.scheduledTime).toBeGreaterThan(NOON_UTC);
    }
  });

  it("breaks ties on scheduledTime by medicationId (stable output)", () => {
    // Two meds with identical daily schedule -> identical times.
    // Expect deterministic order (medicationId ascending).
    const alpha = med({ id: "alpha", scheduleTimes: { times: ["15:00"] } });
    const bravo = med({ id: "bravo", scheduleTimes: { times: ["15:00"] } });
    const result = selectUpcomingDoses([bravo, alpha], NOON_UTC, 2);
    expect(result.map((d) => d.medicationId)).toEqual(["alpha", "bravo"]);
  });
});

function reminder(
  id: string,
  schedule: ScheduleTimes,
  active = true
): Reminder {
  return createReminder({
    id,
    userId: "user-1",
    kind: "medication",
    name: `Reminder ${id}`,
    dosage: "10mg",
    schedule,
    active,
    createdAt: 0,
    updatedAt: 0,
  });
}

/** `noUncheckedIndexedAccess`-safe read of a fire time. */
function fireAtOf(
  occurrences: readonly { readonly fireAt: number }[],
  index: number
): number {
  return occurrences[index]?.fireAt ?? Number.NaN;
}

describe("selectUpcomingOccurrences", () => {
  it("returns an empty array when maxTotal is 0", () => {
    const result = selectUpcomingOccurrences(
      [reminder("r1", dailyTimes(["09:00"]))],
      NOON_UTC,
      0
    );
    expect(result).toHaveLength(0);
  });

  it("returns an empty array when maxTotal is negative", () => {
    const result = selectUpcomingOccurrences(
      [reminder("r1", dailyTimes(["09:00"]))],
      NOON_UTC,
      -5
    );
    expect(result).toHaveLength(0);
  });

  it("returns an empty array when there are no reminders", () => {
    expect(selectUpcomingOccurrences([], NOON_UTC, 64)).toHaveLength(0);
  });

  it("skips inactive reminders entirely", () => {
    const active = reminder("active", dailyTimes(["09:00"]));
    const inactive = reminder("inactive", dailyTimes(["09:30"]), false);
    const result = selectUpcomingOccurrences([active, inactive], NOON_UTC, 64);
    expect(result.length).toBeGreaterThan(0);
    expect(result.every((o) => o.reminderId === "active")).toBe(true);
  });

  it("caps the returned list at maxTotal even with many reminders", () => {
    const reminders = Array.from({ length: 10 }, (_, i) =>
      reminder(`r${String(i)}`, dailyTimes(["09:00"]))
    );
    const result = selectUpcomingOccurrences(reminders, NOON_UTC, 64);
    expect(result).toHaveLength(64);
  });

  it("returns occurrences in non-decreasing fireAt order", () => {
    const reminders = [
      reminder("a", dailyTimes(["09:00", "21:00"])),
      reminder("b", dailyTimes(["12:00", "18:00"])),
      reminder("c", weeklyTimes([0, 3], ["06:00", "15:00"])),
    ];
    const result = selectUpcomingOccurrences(reminders, NOON_UTC, 12);
    const times = result.map((o) => o.fireAt);
    expect(times).toEqual([...times].sort((x, y) => x - y));
  });

  it("interleaves occurrences from multiple reminders by time", () => {
    const a = reminder(
      "a",
      intervalTimes(1, new Date("2026-07-26T13:00:00Z").getTime())
    );
    const b = reminder(
      "b",
      intervalTimes(1, new Date("2026-07-26T12:30:00Z").getTime())
    );
    const result = selectUpcomingOccurrences([a, b], NOON_UTC, 4);
    expect(result.map((o) => o.reminderId)).toEqual(["b", "a", "b", "a"]);
  });

  it("prefers closer occurrences from one reminder over farther ones", () => {
    const a = reminder("a", dailyTimes(["21:00"]));
    const b = reminder(
      "b",
      intervalTimes(1, new Date("2026-07-26T13:00:00Z").getTime())
    );
    const result = selectUpcomingOccurrences([a, b], NOON_UTC, 4);
    expect(result.filter((o) => o.reminderId === "b")).toHaveLength(4);
    expect(result.filter((o) => o.reminderId === "a")).toHaveLength(0);
  });

  it("never returns an occurrence at or before fromMs", () => {
    const past = reminder("past", dailyTimes(["06:00"]));
    const result = selectUpcomingOccurrences([past], NOON_UTC, 4);
    expect(result).toHaveLength(4);
    for (const occurrence of result) {
      expect(occurrence.fireAt).toBeGreaterThan(NOON_UTC);
    }
  });

  it("schedules weekly reminders, which the POC engine cannot express", () => {
    // NOON_UTC is Sunday 2026-07-26; day 3 is Wednesday.
    const weekly = reminder("weekly", weeklyTimes([3], ["08:00"]));
    const result = selectUpcomingOccurrences([weekly], NOON_UTC, 2);
    expect(result.map((o) => o.fireAt)).toEqual([
      new Date("2026-07-29T08:00:00Z").getTime(),
      new Date("2026-08-05T08:00:00Z").getTime(),
    ]);
    expect(result.map((o) => o.occurrenceId)).toEqual([
      `weekly:${String(fireAtOf(result, 0))}`,
      `weekly:${String(fireAtOf(result, 1))}`,
    ]);
  });

  it("interleaves a weekly reminder with a daily one by fire time", () => {
    const daily = reminder("daily", dailyTimes(["10:00"]));
    // Sunday 2026-07-26 is day 0, so this fires later today at 20:00.
    const weekly = reminder("weekly", weeklyTimes([0], ["20:00"]));
    const result = selectUpcomingOccurrences([daily, weekly], NOON_UTC, 3);
    expect(result.map((o) => o.reminderId)).toEqual([
      "weekly",
      "daily",
      "daily",
    ]);
  });

  it("breaks ties on fireAt by reminderId, independent of input order", () => {
    const alpha = reminder("alpha", dailyTimes(["15:00"]));
    const bravo = reminder("bravo", dailyTimes(["15:00"]));
    const forwards = selectUpcomingOccurrences([alpha, bravo], NOON_UTC, 2);
    const backwards = selectUpcomingOccurrences([bravo, alpha], NOON_UTC, 2);
    expect(forwards.map((o) => o.reminderId)).toEqual(["alpha", "bravo"]);
    expect(backwards.map((o) => o.reminderId)).toEqual(["alpha", "bravo"]);
    expect(fireAtOf(forwards, 0)).toEqual(fireAtOf(forwards, 1));
  });
});
