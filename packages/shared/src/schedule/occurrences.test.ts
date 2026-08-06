import { afterAll, beforeAll, describe, expect, it } from "vitest";

import type { Reminder, ScheduleTimes } from "../domain/index";
import {
  createReminder,
  dailyTimes,
  intervalTimes,
  noneTimes,
  onceTimes,
  weeklyTimes,
} from "../domain/index";

import { nextOccurrences } from "./occurrences";
import type { ScheduledOccurrence } from "./types";

const MS_PER_HOUR = 3_600_000;

/**
 * Mirrors the `Reminder(...)` helper in `ReminderScheduleEngineTests.cs`. The
 * id is fixed rather than a fresh Guid so occurrence ids are readable in
 * failure output.
 */
function reminder(schedule: ScheduleTimes): Reminder {
  return createReminder({
    id: "11111111-1111-1111-1111-111111111111",
    userId: "user-1",
    kind: "medication",
    name: "Test med",
    dosage: "10mg",
    schedule,
    priority: "critical",
    createdAt: 0,
    updatedAt: 0,
  });
}

/** Local wall-clock helper. The suite runs under `TZ=UTC` unless overridden. */
function local(
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0
): number {
  return new Date(year, month - 1, day, hour, minute, 0, 0).getTime();
}

/** The local hour and minute a fire time lands on, as `"HH:MM"`. */
function localTime(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(
    d.getMinutes()
  ).padStart(2, "0")}`;
}

/** Elapsed ms between consecutive occurrences. */
function gaps(occurrences: readonly ScheduledOccurrence[]): readonly number[] {
  return occurrences
    .slice(1)
    .map((o, index) => o.fireAt - (occurrences[index]?.fireAt ?? Number.NaN));
}

/** `fireAt` of one occurrence, or `NaN` if the index is out of range. */
function fireAt(
  occurrences: readonly ScheduledOccurrence[],
  index: number
): number {
  return occurrences[index]?.fireAt ?? Number.NaN;
}

describe("nextOccurrences — daily", () => {
  // NextOccurrences_Daily_RespectsCount
  it("respects count and tags each occurrence with the reminder", () => {
    const r = reminder(dailyTimes(["09:00"]));
    const from = local(2026, 7, 3);

    const occ = nextOccurrences(r, from, 5);

    expect(occ).toHaveLength(5);
    expect(occ[0]?.reminderId).toBe(r.id);
    expect(occ[0]?.name).toBe(r.name);
    expect(occ[0]?.dosage).toBe(r.dosage);
    expect(occ[0]?.occurrenceId).toBe(`${r.id}:${String(occ[0]?.fireAt)}`);
    expect(occ.map((o) => o.fireAt)).toEqual([
      local(2026, 7, 3, 9),
      local(2026, 7, 4, 9),
      local(2026, 7, 5, 9),
      local(2026, 7, 6, 9),
      local(2026, 7, 7, 9),
    ]);
  });

  // NextOccurrences_Daily_MultipleTimes_OrderedByTime
  it("orders multiple times within a day chronologically", () => {
    const r = reminder(dailyTimes(["21:00", "09:00", "15:00"]));
    const from = local(2026, 7, 3, 6);

    const occ = nextOccurrences(r, from, 3);

    expect(occ.map((o) => localTime(o.fireAt))).toEqual([
      "09:00",
      "15:00",
      "21:00",
    ]);
  });

  it("orders two times inside the same hour by minute", () => {
    const r = reminder(dailyTimes(["09:30", "09:00"]));

    const occ = nextOccurrences(r, local(2026, 7, 3), 2);

    expect(occ.map((o) => localTime(o.fireAt))).toEqual(["09:00", "09:30"]);
  });

  it("skips slots earlier in the day than `from` and rolls to tomorrow", () => {
    const r = reminder(dailyTimes(["09:00"]));

    const occ = nextOccurrences(r, local(2026, 7, 3, 10), 1);

    expect(occ.map((o) => o.fireAt)).toEqual([local(2026, 7, 4, 9)]);
  });

  it("excludes a dose landing exactly on `from`", () => {
    const r = reminder(dailyTimes(["09:00"]));

    const occ = nextOccurrences(r, local(2026, 7, 3, 9), 1);

    expect(occ.map((o) => o.fireAt)).toEqual([local(2026, 7, 4, 9)]);
  });

  it("returns nothing for an empty time list", () => {
    expect(
      nextOccurrences(reminder(dailyTimes([])), local(2026, 7, 3), 64)
    ).toEqual([]);
  });

  it("ignores time strings it cannot parse", () => {
    const r = reminder(dailyTimes(["09:00", "nope", "25:00", "09:61"]));

    const occ = nextOccurrences(r, local(2026, 7, 3), 2);

    expect(occ.map((o) => o.fireAt)).toEqual([
      local(2026, 7, 3, 9),
      local(2026, 7, 4, 9),
    ]);
  });

  it("returns nothing when every time string is unparseable", () => {
    const r = reminder(dailyTimes(["breakfast", "bedtime"]));

    expect(nextOccurrences(r, local(2026, 7, 3), 5)).toEqual([]);
  });

  it("stops at the 60-day horizon rather than filling `count`", () => {
    const r = reminder(dailyTimes(["09:00"]));

    const occ = nextOccurrences(r, local(2026, 7, 3), 100);

    expect(occ).toHaveLength(60);
    expect(occ[59]?.fireAt).toBe(local(2026, 7, 3 + 59, 9));
  });
});

describe("nextOccurrences — interval", () => {
  // NextOccurrences_Interval_FastForwardsPastFromUtc
  it("fast-forwards past `from` from the start instant", () => {
    const start = Date.UTC(2026, 6, 3, 0, 0, 0);
    const r = reminder(intervalTimes(8, start));

    const occ = nextOccurrences(r, start + 20 * MS_PER_HOUR, 3);

    expect(occ.map((o) => o.fireAt)).toEqual([
      start + 24 * MS_PER_HOUR,
      start + 32 * MS_PER_HOUR,
      start + 40 * MS_PER_HOUR,
    ]);
  });

  it("starts at the start instant when `from` precedes it", () => {
    const start = Date.UTC(2026, 6, 3, 8, 0, 0);
    const r = reminder(intervalTimes(8, start));

    const occ = nextOccurrences(r, start - MS_PER_HOUR, 3);

    expect(occ.map((o) => o.fireAt)).toEqual([
      start,
      start + 8 * MS_PER_HOUR,
      start + 16 * MS_PER_HOUR,
    ]);
  });

  it("lands on the next slot when `from` is an exact multiple of the interval", () => {
    const start = Date.UTC(2026, 6, 3, 0, 0, 0);
    const r = reminder(intervalTimes(8, start));

    const occ = nextOccurrences(r, start + 16 * MS_PER_HOUR, 1);

    expect(occ.map((o) => o.fireAt)).toEqual([start + 24 * MS_PER_HOUR]);
  });

  it("returns nothing for a non-positive interval", () => {
    const start = Date.UTC(2026, 6, 3, 0, 0, 0);

    expect(
      nextOccurrences(reminder(intervalTimes(0, start)), start, 5)
    ).toEqual([]);
    expect(
      nextOccurrences(reminder(intervalTimes(-8, start)), start, 5)
    ).toEqual([]);
  });

  it("is not capped by a day horizon", () => {
    const start = Date.UTC(2026, 6, 3, 0, 0, 0);
    const r = reminder(intervalTimes(8, start));

    const occ = nextOccurrences(r, start, 200);

    expect(occ).toHaveLength(200);
  });
});

/**
 * An occurrence landing exactly on `from` is EXCLUDED, matching
 * `ScheduleEngine.cs`'s `while (next <= fromUtc)`.
 *
 * This used to be pinned from both sides, because the deleted POC engine
 * included that occurrence and the two disagreeing silently would have been a
 * medication-safety defect either way — firing an already-due dose again, or
 * dropping it. With the POC engine gone there is only one behaviour left to
 * pin, but it is still worth pinning: the boundary is exactly where an
 * off-by-one produces a duplicate alarm at the moment a dose comes due.
 */
describe("the `from` boundary", () => {
  const start = Date.UTC(2026, 6, 3, 0, 0, 0);

  it("excludes the interval occurrence exactly at `from`, as the C# engine does", () => {
    const occ = nextOccurrences(reminder(intervalTimes(8, start)), start, 2);

    expect(occ.map((o) => o.fireAt)).toEqual([
      start + 8 * MS_PER_HOUR,
      start + 16 * MS_PER_HOUR,
    ]);
  });
});

describe("nextOccurrences — weekly", () => {
  // NextOccurrences_Weekly_SkipsNonMatchingDays
  it("emits only on the selected weekdays", () => {
    const r = reminder(weeklyTimes([1, 3, 5], ["07:00"]));
    const from = local(2026, 7, 6); // a Monday

    const occ = nextOccurrences(r, from, 5);

    expect(occ).toHaveLength(5);
    for (const o of occ) {
      expect(localTime(o.fireAt)).toBe("07:00");
      expect([1, 3, 5]).toContain(new Date(o.fireAt).getDay());
    }
    expect(occ.map((o) => o.fireAt)).toEqual([
      local(2026, 7, 6, 7),
      local(2026, 7, 8, 7),
      local(2026, 7, 10, 7),
      local(2026, 7, 13, 7),
      local(2026, 7, 15, 7),
    ]);
  });

  // NextOccurrences_Weekly_MultipleTimesPerDay_OrderedByTime
  it("orders multiple times within a selected day chronologically", () => {
    const r = reminder(weeklyTimes([1], ["20:00", "08:00"]));

    const occ = nextOccurrences(r, local(2026, 7, 6), 2);

    expect(occ.map((o) => localTime(o.fireAt))).toEqual(["08:00", "20:00"]);
  });

  // NextOccurrences_Weekly_EmptyDaysOrTimes_ReturnsEmpty
  it("returns nothing when either the day set or the time list is empty", () => {
    const from = local(2026, 7, 6);

    expect(
      nextOccurrences(reminder(weeklyTimes([], ["09:00"])), from, 5)
    ).toEqual([]);
    expect(nextOccurrences(reminder(weeklyTimes([1], [])), from, 5)).toEqual(
      []
    );
  });

  it("excludes an occurrence landing exactly on `from`", () => {
    const r = reminder(weeklyTimes([1], ["07:00"]));

    const occ = nextOccurrences(r, local(2026, 7, 6, 7), 1);

    expect(occ.map((o) => o.fireAt)).toEqual([local(2026, 7, 13, 7)]);
  });

  it("stops at the 56-day horizon rather than filling `count`", () => {
    const r = reminder(weeklyTimes([1], ["07:00"]));

    const occ = nextOccurrences(r, local(2026, 7, 6), 100);

    expect(occ).toHaveLength(8);
    expect(occ[7]?.fireAt).toBe(local(2026, 7, 6 + 49, 7));
  });
});

describe("nextOccurrences — once", () => {
  it("comes due exactly once, however many the caller asks for", () => {
    const r = reminder(onceTimes(local(2026, 7, 10, 14)));

    const occ = nextOccurrences(r, local(2026, 7, 3), 64);

    expect(occ.map((o) => o.fireAt)).toEqual([local(2026, 7, 10, 14)]);
  });

  it("is spent once its moment has passed", () => {
    const r = reminder(onceTimes(local(2026, 7, 10, 14)));

    expect(nextOccurrences(r, local(2026, 7, 10, 14, 1), 64)).toEqual([]);
  });

  it("excludes a moment landing exactly on `from`, as every variant does", () => {
    const at = local(2026, 7, 10, 14);
    const r = reminder(onceTimes(at));

    expect(nextOccurrences(r, at - 1, 64).map((o) => o.fireAt)).toEqual([at]);
    expect(nextOccurrences(r, at, 64)).toEqual([]);
  });
});

describe("nextOccurrences — none", () => {
  it("never comes due, which is what puts a reminder in the Backlog", () => {
    const r = reminder(noneTimes());

    expect(nextOccurrences(r, local(2026, 7, 3), 64)).toEqual([]);
  });
});

describe("nextOccurrences — contract", () => {
  // NextOccurrences_UnknownScheduleType_Throws
  it("throws on a schedule variant it does not know", () => {
    const unknown = {
      type: "monthly",
      dayOfMonth: 1,
    } as unknown as ScheduleTimes;

    expect(() =>
      nextOccurrences(reminder(unknown), local(2026, 7, 3), 1)
    ).toThrow(/unhandled/i);
  });

  it("rejects a negative count", () => {
    const r = reminder(dailyTimes(["09:00"]));

    expect(() => nextOccurrences(r, local(2026, 7, 3), -1)).toThrow(RangeError);
  });

  it("rejects a fractional count", () => {
    const r = reminder(dailyTimes(["09:00"]));

    expect(() => nextOccurrences(r, local(2026, 7, 3), 2.5)).toThrow(
      RangeError
    );
  });

  it("returns nothing for a count of zero", () => {
    const r = reminder(dailyTimes(["09:00"]));

    expect(nextOccurrences(r, local(2026, 7, 3), 0)).toEqual([]);
  });

  it("defaults count to 64, matching the C# signature", () => {
    const start = Date.UTC(2026, 6, 3, 0, 0, 0);

    expect(
      nextOccurrences(reminder(intervalTimes(8, start)), start)
    ).toHaveLength(64);
  });

  it("builds a stable occurrence id for the same reminder and fire time", () => {
    const start = Date.UTC(2026, 6, 3, 8, 0, 0);
    const r = reminder(intervalTimes(8, start));
    const from = start - MS_PER_HOUR;

    const a = nextOccurrences(r, from, 1);
    const b = nextOccurrences(r, from, 1);

    expect(a[0]?.occurrenceId).toBe(b[0]?.occurrenceId);
    expect(a[0]?.occurrenceId).toBe(`${r.id}:${String(start)}`);
  });
});

/**
 * Daylight saving is where naive "add 86_400_000 ms" scheduling breaks: a 09:00
 * dose silently becomes 08:00 or 10:00 for half the year. Occurrences are built
 * from local calendar fields instead, so the wall-clock time the user chose is
 * what survives, and the gap between consecutive doses is the one that varies.
 */
describe("nextOccurrences — daylight saving", () => {
  const previousTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });

  afterAll(() => {
    process.env.TZ = previousTz;
  });

  it("keeps a daily 09:00 dose at 09:00 across spring forward", () => {
    const r = reminder(dailyTimes(["09:00"]));
    // 2027-03-14 is the US spring-forward date.
    const occ = nextOccurrences(r, local(2027, 3, 13, 0), 3);

    expect(occ.map((o) => localTime(o.fireAt))).toEqual([
      "09:00",
      "09:00",
      "09:00",
    ]);
    expect(gaps(occ)).toEqual([23 * MS_PER_HOUR, 24 * MS_PER_HOUR]);
  });

  it("keeps a daily 09:00 dose at 09:00 across fall back", () => {
    const r = reminder(dailyTimes(["09:00"]));
    // 2027-11-07 is the US fall-back date.
    const occ = nextOccurrences(r, local(2027, 11, 6, 0), 3);

    expect(occ.map((o) => localTime(o.fireAt))).toEqual([
      "09:00",
      "09:00",
      "09:00",
    ]);
    expect(gaps(occ)).toEqual([25 * MS_PER_HOUR, 24 * MS_PER_HOUR]);
  });

  it("keeps a weekly 07:00 dose at 07:00 across spring forward", () => {
    const r = reminder(weeklyTimes([0], ["07:00"])); // Sundays
    const occ = nextOccurrences(r, local(2027, 3, 8, 0), 2);

    expect(occ.map((o) => localTime(o.fireAt))).toEqual(["07:00", "07:00"]);
    expect(new Date(fireAt(occ, 0)).getDate()).toBe(14); // the transition day
  });

  it("still emits a dose whose local time does not exist, shifted forward", () => {
    const r = reminder(dailyTimes(["02:30"]));
    // 02:30 never happens on 2027-03-14; the clock jumps 02:00 -> 03:00.
    const occ = nextOccurrences(r, local(2027, 3, 13, 12), 1);

    expect(occ).toHaveLength(1);
    expect(localTime(fireAt(occ, 0))).toBe("03:30");
  });

  it("keeps an interval schedule on fixed absolute spacing across a transition", () => {
    const start = Date.UTC(2027, 2, 13, 12, 0, 0);
    const r = reminder(intervalTimes(12, start));

    const occ = nextOccurrences(r, start, 3);

    expect(occ.map((o) => o.fireAt)).toEqual([
      start + 12 * MS_PER_HOUR,
      start + 24 * MS_PER_HOUR,
      start + 36 * MS_PER_HOUR,
    ]);
  });
});
