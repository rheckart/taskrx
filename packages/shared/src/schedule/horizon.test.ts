import { describe, expect, it } from "vitest";

import type { Reminder, ScheduleTimes } from "../domain/index";
import {
  createReminder,
  dailyTimes,
  intervalTimes,
  weeklyTimes,
} from "../domain/index";

import { selectUpcomingOccurrences } from "./horizon";

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

function fireAtOf(
  occurrences: readonly { readonly fireAt: number }[],
  index: number
): number {
  return occurrences[index]?.fireAt ?? Number.NaN;
}

/**
 * All tests use UTC (vitest pins TZ=UTC in vitest.config.ts).
 * 2026-07-26T00:00:00Z = 1785196800000.
 */
const NOON_UTC = new Date("2026-07-26T12:00:00Z").getTime();

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

  it("schedules weekly reminders", () => {
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
