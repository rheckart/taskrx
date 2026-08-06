import { describe, expect, it } from "vitest";

import type { ScheduleTimes } from "./schedule-times";
import {
  assertNever,
  dailyTimes,
  intervalTimes,
  isWellFormedSchedule,
  noneTimes,
  onceTimes,
  parseScheduleTimes,
  serializeScheduleTimes,
  weeklyTimes,
} from "./schedule-times";

/**
 * Exhaustive dispatch over every variant. The `default` arm narrows to
 * `never`, so this compiles. It also proves each variant carries its own
 * distinct payload fields.
 */
function describeExhaustively(schedule: ScheduleTimes): string {
  switch (schedule.type) {
    case "daily":
      return `daily:${String(schedule.times.length)}`;
    case "interval":
      return `interval:${String(schedule.hours)}`;
    case "weekly":
      return `weekly:${String(schedule.daysOfWeek.length)}`;
    case "once":
      return `once:${String(schedule.at)}`;
    case "none":
      return "none";
    default:
      return assertNever(schedule);
  }
}

/**
 * The failure fixture required by the plan's QA scenario: a `switch` that
 * deliberately omits `weekly`. In the `default` arm `schedule` narrows to
 * `WeeklyTimes`, not `never`, so passing it to `assertNever` is a compile
 * error — suppressed here by `@ts-expect-error`.
 *
 * This is a live assertion, not a comment: if someone adds the missing `case`
 * (or deletes the `weekly` variant), the suppressed error disappears, the
 * directive becomes unused, and `npm run typecheck` fails. A future variant
 * therefore cannot be silently ignored.
 */
function describeIncompletely(schedule: ScheduleTimes): string {
  switch (schedule.type) {
    case "daily":
      return `daily:${String(schedule.times.length)}`;
    case "interval":
      return `interval:${String(schedule.hours)}`;
    default:
      // @ts-expect-error `weekly` is unhandled above, so this is not `never`.
      return assertNever(schedule);
  }
}

describe("ScheduleTimes constructors", () => {
  it("tags a daily schedule", () => {
    expect(dailyTimes(["09:00", "21:00"])).toEqual({
      type: "daily",
      times: ["09:00", "21:00"],
    });
  });

  it("tags an interval schedule", () => {
    const startAt = Date.UTC(2026, 0, 1, 6, 0, 0);
    expect(intervalTimes(8, startAt)).toEqual({
      type: "interval",
      hours: 8,
      startAt,
    });
  });

  it("tags a weekly schedule", () => {
    expect(weeklyTimes([1, 3, 5], ["09:00"])).toEqual({
      type: "weekly",
      daysOfWeek: [1, 3, 5],
      times: ["09:00"],
    });
  });

  it("tags a single-moment schedule with the instant it happens at", () => {
    expect(onceTimes(Date.UTC(2026, 7, 7, 14, 0, 0))).toEqual({
      type: "once",
      at: Date.UTC(2026, 7, 7, 14, 0, 0),
    });
  });

  it("tags an absent schedule, which carries no time at all", () => {
    expect(noneTimes()).toEqual({ type: "none" });
  });

  it("accepts an empty daily schedule, matching the C# default", () => {
    expect(dailyTimes([])).toEqual({ type: "daily", times: [] });
  });
});

describe("exhaustiveness", () => {
  it("dispatches every variant without a fallback", () => {
    expect(describeExhaustively(dailyTimes(["09:00"]))).toBe("daily:1");
    expect(describeExhaustively(intervalTimes(8, 0))).toBe("interval:8");
    expect(describeExhaustively(onceTimes(1_700_000_000_000))).toBe(
      "once:1700000000000"
    );
    expect(describeExhaustively(noneTimes())).toBe("none");
    expect(describeExhaustively(weeklyTimes([1, 3], ["09:00"]))).toBe(
      "weekly:2"
    );
  });

  it("throws when an unhandled variant reaches assertNever at runtime", () => {
    expect(() => describeIncompletely(weeklyTimes([1], ["09:00"]))).toThrow(
      /unhandled/i
    );
  });

  it("still dispatches the handled variants in the incomplete switch", () => {
    expect(describeIncompletely(dailyTimes(["09:00"]))).toBe("daily:1");
    expect(describeIncompletely(intervalTimes(6, 0))).toBe("interval:6");
  });
});

describe("isWellFormedSchedule", () => {
  it("accepts a schedule that names at least one usable moment", () => {
    expect(isWellFormedSchedule(dailyTimes(["09:00"]))).toBe(true);
    expect(isWellFormedSchedule(weeklyTimes([1], ["09:00"]))).toBe(true);
    expect(isWellFormedSchedule(intervalTimes(8, 0))).toBe(true);
    expect(isWellFormedSchedule(onceTimes(1_700_000_000_000))).toBe(true);
  });

  it("accepts an absent schedule, because having no time is the point of it", () => {
    expect(isWellFormedSchedule(noneTimes())).toBe(true);
  });

  it("rejects a recurring schedule with no moment in it", () => {
    // The misconfiguration `none` replaced: it produces nothing and looks like a
    // backlog item without being one.
    expect(isWellFormedSchedule(dailyTimes([]))).toBe(false);
    expect(isWellFormedSchedule(dailyTimes(["breakfast"]))).toBe(false);
    expect(isWellFormedSchedule(weeklyTimes([], ["09:00"]))).toBe(false);
    expect(isWellFormedSchedule(weeklyTimes([1], []))).toBe(false);
  });

  it("rejects an interval that never comes round", () => {
    expect(isWellFormedSchedule(intervalTimes(0, 0))).toBe(false);
    expect(isWellFormedSchedule(intervalTimes(8, Number.NaN))).toBe(false);
  });

  it("rejects a single moment that is not a moment", () => {
    expect(isWellFormedSchedule(onceTimes(Number.NaN))).toBe(false);
  });

  it("accepts a single moment that has already passed, which is merely spent", () => {
    expect(isWellFormedSchedule(onceTimes(0))).toBe(true);
  });

  it("throws on a variant it does not know", () => {
    const unknown = { type: "monthly" } as unknown as ScheduleTimes;

    expect(() => isWellFormedSchedule(unknown)).toThrow(/unhandled/i);
  });
});

describe("serializeScheduleTimes", () => {
  it("writes the C# converter's daily wire shape", () => {
    expect(serializeScheduleTimes(dailyTimes(["09:00", "21:30"]))).toEqual({
      $type: "DailyTimes",
      Times: ["09:00:00", "21:30:00"],
    });
  });

  it("writes the C# converter's interval wire shape", () => {
    expect(
      serializeScheduleTimes(intervalTimes(8, Date.UTC(2026, 0, 1, 6, 0, 0)))
    ).toEqual({
      $type: "IntervalTimes",
      Hours: 8,
      StartAtUtc: "2026-01-01T06:00:00.000Z",
    });
  });

  it("refuses to write a time it cannot represent as a TimeOnly", () => {
    expect(() => serializeScheduleTimes(dailyTimes(["25:00"]))).toThrow(
      /TimeOnly/
    );
    expect(() =>
      serializeScheduleTimes(weeklyTimes([1], ["breakfast"]))
    ).toThrow(/TimeOnly/);
  });

  it("writes the C# converter's weekly wire shape", () => {
    expect(serializeScheduleTimes(weeklyTimes([1, 3, 5], ["08:00"]))).toEqual({
      $type: "WeeklyTimes",
      DaysOfWeek: [1, 3, 5],
      Times: ["08:00:00"],
    });
  });

  it("writes a single moment as an instant, the way an interval start is written", () => {
    expect(
      serializeScheduleTimes(onceTimes(Date.UTC(2026, 0, 1, 6, 0, 0)))
    ).toEqual({
      $type: "OnceTimes",
      AtUtc: "2026-01-01T06:00:00.000Z",
    });
  });

  it("writes an absent schedule as a discriminator with no members", () => {
    expect(serializeScheduleTimes(noneTimes())).toEqual({ $type: "NoneTimes" });
  });
});

describe("parseScheduleTimes", () => {
  it("round-trips every variant through JSON", () => {
    const schedules: readonly ScheduleTimes[] = [
      dailyTimes(["09:00", "21:30"]),
      intervalTimes(8, Date.UTC(2026, 0, 1, 6, 0, 0)),
      weeklyTimes([0, 2, 6], ["08:00", "20:00"]),
      onceTimes(Date.UTC(2026, 7, 7, 14, 0, 0)),
      noneTimes(),
    ];

    for (const schedule of schedules) {
      const wire: unknown = JSON.parse(
        JSON.stringify(serializeScheduleTimes(schedule))
      );
      expect(parseScheduleTimes(wire)).toEqual(schedule);
    }
  });

  it("reads TimeOnly's seconds and tick precision", () => {
    expect(
      parseScheduleTimes({
        $type: "DailyTimes",
        Times: ["09:00:00", "21:30:00.0000000", "07:15"],
      })
    ).toEqual(dailyTimes(["09:00", "21:30", "07:15"]));
  });

  it("reads DateTimeOffset's +00:00 spelling of the epoch instant", () => {
    expect(
      parseScheduleTimes({
        $type: "IntervalTimes",
        Hours: 8,
        StartAtUtc: "2026-01-01T06:00:00+00:00",
      })
    ).toEqual(intervalTimes(8, Date.UTC(2026, 0, 1, 6, 0, 0)));
  });

  it("reads DayOfWeek as names as well as ordinals", () => {
    expect(
      parseScheduleTimes({
        $type: "WeeklyTimes",
        DaysOfWeek: ["Sunday", "Wednesday", "Saturday"],
        Times: ["08:00:00"],
      })
    ).toEqual(weeklyTimes([0, 3, 6], ["08:00"]));
  });

  it("reads a single moment written with an offset rather than a Z", () => {
    expect(
      parseScheduleTimes({
        $type: "OnceTimes",
        AtUtc: "2026-08-07T14:00:00+00:00",
      })
    ).toEqual(onceTimes(Date.UTC(2026, 7, 7, 14, 0, 0)));
  });

  it("rejects a single moment whose instant is missing or unreadable", () => {
    expect(parseScheduleTimes({ $type: "OnceTimes" })).toBeNull();
    expect(
      parseScheduleTimes({ $type: "OnceTimes", AtUtc: "not a date" })
    ).toBeNull();
  });

  it("returns null for an unknown discriminator, matching the C# reader", () => {
    expect(parseScheduleTimes({ $type: "MonthlyTimes", Times: [] })).toBeNull();
  });

  it("returns null when the discriminator is absent", () => {
    expect(parseScheduleTimes({ Times: ["09:00:00"] })).toBeNull();
  });

  it("returns null for non-object input", () => {
    expect(parseScheduleTimes(null)).toBeNull();
    expect(parseScheduleTimes("DailyTimes")).toBeNull();
    expect(parseScheduleTimes([])).toBeNull();
  });

  it("rejects a daily payload whose Times is not an array of times", () => {
    expect(parseScheduleTimes({ $type: "DailyTimes" })).toBeNull();
    expect(
      parseScheduleTimes({ $type: "DailyTimes", Times: "09:00" })
    ).toBeNull();
    expect(
      parseScheduleTimes({ $type: "DailyTimes", Times: ["25:00"] })
    ).toBeNull();
    expect(parseScheduleTimes({ $type: "DailyTimes", Times: [9] })).toBeNull();
  });

  it("rejects an interval payload with a bad hours or start instant", () => {
    expect(
      parseScheduleTimes({
        $type: "IntervalTimes",
        StartAtUtc: "2026-01-01T00:00:00Z",
      })
    ).toBeNull();
    expect(
      parseScheduleTimes({
        $type: "IntervalTimes",
        Hours: 0,
        StartAtUtc: "2026-01-01T00:00:00Z",
      })
    ).toBeNull();
    expect(
      parseScheduleTimes({
        $type: "IntervalTimes",
        Hours: 8,
        StartAtUtc: "not-a-date",
      })
    ).toBeNull();
    expect(parseScheduleTimes({ $type: "IntervalTimes", Hours: 8 })).toBeNull();
  });

  it("rejects a weekly payload with an out-of-range or unknown day", () => {
    expect(
      parseScheduleTimes({
        $type: "WeeklyTimes",
        DaysOfWeek: [7],
        Times: ["08:00:00"],
      })
    ).toBeNull();
    expect(
      parseScheduleTimes({
        $type: "WeeklyTimes",
        DaysOfWeek: ["Caturday"],
        Times: ["08:00:00"],
      })
    ).toBeNull();
    expect(
      parseScheduleTimes({
        $type: "WeeklyTimes",
        DaysOfWeek: [1.5],
        Times: ["08:00:00"],
      })
    ).toBeNull();
    expect(
      parseScheduleTimes({
        $type: "WeeklyTimes",
        DaysOfWeek: [null],
        Times: ["08:00:00"],
      })
    ).toBeNull();
    expect(
      parseScheduleTimes({ $type: "WeeklyTimes", Times: ["08:00:00"] })
    ).toBeNull();
    expect(
      parseScheduleTimes({ $type: "WeeklyTimes", DaysOfWeek: [1] })
    ).toBeNull();
  });

  it("accepts an empty weekly day set, which the parser produces before days are known", () => {
    expect(
      parseScheduleTimes({
        $type: "WeeklyTimes",
        DaysOfWeek: [],
        Times: ["08:00:00"],
      })
    ).toEqual(weeklyTimes([], ["08:00"]));
  });
});
