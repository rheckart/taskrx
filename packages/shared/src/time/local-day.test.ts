import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { localCalendarDaysBetween, localDayBounds } from "./local-day";

const MS_PER_HOUR = 3_600_000;

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

describe("localDayBounds", () => {
  it("spans the day holding `now`, midnight to its last millisecond", () => {
    const bounds = localDayBounds(local(2026, 8, 13, 9, 30), 0);

    expect(bounds.start).toBe(local(2026, 8, 13));
    expect(bounds.end).toBe(local(2026, 8, 13, 23, 59) + 59_999);
  });

  it("reports the same day whatever time of day it is asked", () => {
    expect(localDayBounds(local(2026, 8, 13, 0, 0), 0)).toEqual(
      localDayBounds(local(2026, 8, 13, 23, 59), 0)
    );
  });

  it("counts backwards for a negative offset", () => {
    const bounds = localDayBounds(local(2026, 8, 13, 9), -1);

    expect(bounds.start).toBe(local(2026, 8, 12));
    expect(bounds.end).toBe(local(2026, 8, 12, 23, 59) + 59_999);
  });

  it("counts forwards for a positive offset", () => {
    expect(localDayBounds(local(2026, 8, 13, 9), 1).start).toBe(
      local(2026, 8, 14)
    );
  });

  it("rolls back over a month boundary", () => {
    expect(localDayBounds(local(2026, 3, 1, 9), -1).start).toBe(
      local(2026, 2, 28)
    );
  });

  it("rolls forward over a year boundary", () => {
    expect(localDayBounds(local(2025, 12, 31, 9), 1).start).toBe(
      local(2026, 1, 1)
    );
  });

  it("reaches a whole week back in one step", () => {
    expect(localDayBounds(local(2026, 8, 13, 9), -6).start).toBe(
      local(2026, 8, 7)
    );
  });
});

describe("localCalendarDaysBetween", () => {
  it("is zero for two moments on the same day", () => {
    expect(
      localCalendarDaysBetween(local(2026, 8, 13, 0, 1), local(2026, 8, 13, 23))
    ).toBe(0);
  });

  it("counts the days between, not the hours", () => {
    // Twenty-five minutes apart, and still a day: the answer is which day the
    // second moment lands on, not how much of the first one was left.
    expect(
      localCalendarDaysBetween(
        local(2026, 8, 13, 23, 50),
        local(2026, 8, 14, 0, 15)
      )
    ).toBe(1);
  });

  it("counts a whole week", () => {
    expect(
      localCalendarDaysBetween(local(2026, 8, 13, 9), local(2026, 8, 20, 18))
    ).toBe(7);
  });

  it("goes negative when the second moment is on an earlier day", () => {
    expect(
      localCalendarDaysBetween(local(2026, 8, 13, 9), local(2026, 8, 11, 9))
    ).toBe(-2);
  });

  it("counts across a month boundary", () => {
    expect(
      localCalendarDaysBetween(local(2026, 2, 26, 9), local(2026, 3, 3, 9))
    ).toBe(5);
  });

  it("counts across a year boundary", () => {
    expect(
      localCalendarDaysBetween(local(2025, 12, 30, 9), local(2026, 1, 2, 9))
    ).toBe(3);
  });
});

/**
 * The reason this is one function rather than the same six lines in a screen
 * and in the adherence period.
 *
 * A local day is not 24 hours twice a year, and both callers care: the timeline
 * would drop an 23:00 row or double an 00:30 one, and the adherence period would
 * start an hour off midnight and clip the first day of its own window. Neither
 * bug is visible in the `TZ=UTC` the rest of the suite runs at, which is why the
 * span is asserted here explicitly rather than left to the callers' own cases.
 */
describe("localDayBounds — daylight saving", () => {
  const previousTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });

  afterAll(() => {
    process.env.TZ = previousTz;
  });

  it("gives the spring-forward day 23 hours, still starting at midnight", () => {
    // 2027-03-14 is the US spring-forward date.
    const bounds = localDayBounds(local(2027, 3, 14, 12), 0);

    expect(new Date(bounds.start).getHours()).toBe(0);
    expect(new Date(bounds.start).getDate()).toBe(14);
    expect(bounds.end - bounds.start).toBe(23 * MS_PER_HOUR - 1);
  });

  it("gives the fall-back day 25 hours, still starting at midnight", () => {
    // 2027-11-07 is the US fall-back date.
    const bounds = localDayBounds(local(2027, 11, 7, 12), 0);

    expect(new Date(bounds.start).getHours()).toBe(0);
    expect(new Date(bounds.start).getDate()).toBe(7);
    expect(bounds.end - bounds.start).toBe(25 * MS_PER_HOUR - 1);
  });

  it("lands on midnight when stepping onto a transition day", () => {
    const bounds = localDayBounds(local(2027, 3, 13, 12), 1);

    expect(new Date(bounds.start).getHours()).toBe(0);
    expect(new Date(bounds.start).getDate()).toBe(14);
  });

  /**
   * The two cases a division by 86_400_000 gets wrong. Spring forward puts 23
   * hours between the midnights, which truncates to nothing and reports the
   * next day as today; fall back puts 25 between them, which a ceiling would
   * report as two. Either way a refill warning keyed off the count arrives on
   * the wrong day, once each spring and once each autumn.
   */
  it("counts one day across spring forward, though only 23 hours passed", () => {
    expect(
      localCalendarDaysBetween(local(2027, 3, 14, 9), local(2027, 3, 15, 9))
    ).toBe(1);
  });

  it("counts one day across fall back, though 25 hours passed", () => {
    expect(
      localCalendarDaysBetween(local(2027, 11, 7, 9), local(2027, 11, 8, 9))
    ).toBe(1);
  });

  it("counts a week spanning a transition as seven days", () => {
    expect(
      localCalendarDaysBetween(local(2027, 3, 11, 9), local(2027, 3, 18, 9))
    ).toBe(7);
  });
});
