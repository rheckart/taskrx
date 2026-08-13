import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { ADHERENCE_PERIOD_DAYS, adherencePeriod } from "./period";

const MS_PER_HOUR = 3_600_000;
const MS_PER_DAY = MS_PER_HOUR * 24;

/** Local wall-clock helper, matching the pattern in `occurrences.test.ts`. */
function local(
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0
): number {
  return new Date(year, month - 1, day, hour, minute, 0, 0).getTime();
}

describe("adherencePeriod", () => {
  it("ends at the last instant of the day holding `now`", () => {
    const period = adherencePeriod(local(2026, 8, 13, 9, 30), 7);

    expect(period.toMs).toBe(local(2026, 8, 13, 23, 59) + 59_999);
  });

  it("starts at local midnight, so a whole first day is counted", () => {
    const period = adherencePeriod(local(2026, 8, 13, 9, 30), 7);

    expect(period.fromMs).toBe(local(2026, 8, 7));
  });

  it("counts the day holding `now` as one of the days", () => {
    const period = adherencePeriod(local(2026, 8, 13, 9, 30), 1);

    expect(period.fromMs).toBe(local(2026, 8, 13));
    expect(period.toMs).toBe(local(2026, 8, 13, 23, 59) + 59_999);
  });

  it("reports the same window whatever time of day it is asked", () => {
    const earlyMorning = adherencePeriod(local(2026, 8, 13, 0, 1), 7);
    const lateNight = adherencePeriod(local(2026, 8, 13, 23, 58), 7);

    expect(earlyMorning).toEqual(lateNight);
  });

  it("reaches back across a month boundary", () => {
    const period = adherencePeriod(local(2026, 3, 2, 12), 7);

    expect(period.fromMs).toBe(local(2026, 2, 24));
  });

  it("reaches back across a year boundary", () => {
    const period = adherencePeriod(local(2026, 1, 3, 12), 7);

    expect(period.fromMs).toBe(local(2025, 12, 28));
  });

  /**
   * The window a person actually reviews. ADR 0005 fixed the sweep's lookback at
   * seven days and said why the two numbers are the same one: "Adherence is
   * reported by day and reviewed by week, so a sweep that reaches a week back can
   * always account for every day a person is looking at."
   *
   * A period wider than the sweep's reach would show days whose unanswered doses
   * were never minted as Missed — the flattering figure this whole sequence
   * exists to remove, moved past day seven rather than fixed.
   */
  it("is the seven days the sweep can account for", () => {
    expect(ADHERENCE_PERIOD_DAYS).toBe(7);
  });
});

/**
 * Local calendar days, not fixed 24-hour blocks.
 *
 * A period built by subtracting `days * MS_PER_DAY` from `now` lands an hour
 * either side of midnight for half the year, so the first day of the window is
 * clipped or doubled and its bucket disagrees with every other day's. Building
 * from `Date`'s local calendar fields — the pattern `occurrences.ts` established
 * — means the boundary is midnight because it was asked for midnight, whatever
 * the offset did in between.
 */
describe("adherencePeriod — daylight saving", () => {
  const previousTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });

  afterAll(() => {
    process.env.TZ = previousTz;
  });

  it("starts at midnight across spring forward, not an hour off it", () => {
    // 2027-03-14 is the US spring-forward date.
    const period = adherencePeriod(local(2027, 3, 16, 12), 7);
    const start = new Date(period.fromMs);

    expect(start.getHours()).toBe(0);
    expect(start.getDate()).toBe(10);
    // The week the clocks went forward is an hour short of seven 24-hour days.
    expect(period.toMs - period.fromMs).toBe(7 * MS_PER_DAY - MS_PER_HOUR - 1);
  });

  it("starts at midnight across fall back, not an hour off it", () => {
    // 2027-11-07 is the US fall-back date.
    const period = adherencePeriod(local(2027, 11, 9, 12), 7);
    const start = new Date(period.fromMs);

    expect(start.getHours()).toBe(0);
    expect(start.getDate()).toBe(3);
    // And the week they went back is an hour long.
    expect(period.toMs - period.fromMs).toBe(7 * MS_PER_DAY + MS_PER_HOUR - 1);
  });
});
