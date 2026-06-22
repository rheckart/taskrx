import { describe, expect, it } from "vitest";

import type { Medication } from "../index.js";

import { nextNDaily, nextNInterval, upcomingDosesFor } from "./engine.js";

/**
 * Build a local-timestamp from an ISO date interpreted in the test process TZ.
 * Vitest config pins TZ=UTC so these map to the UTC instants used in the spec.
 */
function localMs(iso: string): number {
  return new Date(iso).getTime();
}

function dailyMed(
  times: readonly string[]
): Pick<Medication, "id" | "scheduleType" | "scheduleTimes"> {
  return {
    id: "med-daily",
    scheduleType: "daily",
    scheduleTimes: { times },
  };
}

function intervalMed(
  hours: number,
  startAt: number
): Pick<Medication, "id" | "scheduleType" | "scheduleTimes"> {
  return {
    id: "med-interval",
    scheduleType: "interval",
    scheduleTimes: { hours, startAt },
  };
}

describe("nextNDaily", () => {
  it("returns today's two doses when from is before the first dose", () => {
    const from = localMs("2026-06-22T08:00:00Z");
    const result = nextNDaily(["09:00", "21:00"], from, 4);

    expect(result).toEqual([
      localMs("2026-06-22T09:00:00Z"),
      localMs("2026-06-22T21:00:00Z"),
      localMs("2026-06-23T09:00:00Z"),
      localMs("2026-06-23T21:00:00Z"),
    ]);
  });

  it("skips past doses when from is after the first dose", () => {
    const from = localMs("2026-06-22T10:00:00Z");
    const result = nextNDaily(["09:00", "21:00"], from, 3);

    expect(result).toEqual([
      localMs("2026-06-22T21:00:00Z"),
      localMs("2026-06-23T09:00:00Z"),
      localMs("2026-06-23T21:00:00Z"),
    ]);
  });

  it("does not include the dose at exactly fromMs", () => {
    const from = localMs("2026-06-22T09:00:00Z");
    const result = nextNDaily(["09:00"], from, 3);

    expect(result).toEqual([
      localMs("2026-06-23T09:00:00Z"),
      localMs("2026-06-24T09:00:00Z"),
      localMs("2026-06-25T09:00:00Z"),
    ]);
  });

  it("returns an empty array for empty times", () => {
    expect(nextNDaily([], localMs("2026-06-22T08:00:00Z"), 5)).toEqual([]);
  });

  it("returns an empty array when n is 0", () => {
    expect(nextNDaily(["09:00"], localMs("2026-06-22T08:00:00Z"), 0)).toEqual(
      []
    );
  });

  it("returns exactly n doses", () => {
    expect(
      nextNDaily(["09:00", "21:00"], localMs("2026-06-22T08:00:00Z"), 1)
    ).toHaveLength(1);
  });

  it("handles times that cross the day boundary", () => {
    const from = localMs("2026-06-22T22:00:00Z");
    const result = nextNDaily(["23:00", "01:00"], from, 4);

    // 01:00 belongs to the next local day, so after 22:00 the next dose is 23:00 same day,
    // then 01:00 next day, then 23:00 next day, then 01:00 day after.
    expect(result).toEqual([
      localMs("2026-06-22T23:00:00Z"),
      localMs("2026-06-23T01:00:00Z"),
      localMs("2026-06-23T23:00:00Z"),
      localMs("2026-06-24T01:00:00Z"),
    ]);
  });

  it("sorts unsorted time strings", () => {
    const from = localMs("2026-06-22T08:00:00Z");
    const result = nextNDaily(["21:00", "09:00"], from, 2);

    expect(result).toEqual([
      localMs("2026-06-22T09:00:00Z"),
      localMs("2026-06-22T21:00:00Z"),
    ]);
  });

  it("sorts by minute when hours are equal", () => {
    const from = localMs("2026-06-22T08:00:00Z");
    const result = nextNDaily(["09:30", "09:00"], from, 2);

    expect(result).toEqual([
      localMs("2026-06-22T09:00:00Z"),
      localMs("2026-06-22T09:30:00Z"),
    ]);
  });
});

describe("nextNInterval", () => {
  it("includes the occurrence exactly at fromMs", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    const from = localMs("2026-06-22T08:00:00Z");
    const result = nextNInterval(8, startAt, from, 3);

    expect(result).toEqual([
      localMs("2026-06-22T08:00:00Z"),
      localMs("2026-06-22T16:00:00Z"),
      localMs("2026-06-23T00:00:00Z"),
    ]);
  });

  it("skips past occurrences", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    const from = localMs("2026-06-22T09:00:00Z");
    const result = nextNInterval(8, startAt, from, 2);

    expect(result).toEqual([
      localMs("2026-06-22T16:00:00Z"),
      localMs("2026-06-23T00:00:00Z"),
    ]);
  });

  it("works for 24-hour intervals", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    const from = localMs("2026-06-22T12:00:00Z");
    const result = nextNInterval(24, startAt, from, 2);

    expect(result).toEqual([
      localMs("2026-06-23T00:00:00Z"),
      localMs("2026-06-24T00:00:00Z"),
    ]);
  });

  it("returns an empty array when n is 0", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    expect(
      nextNInterval(8, startAt, localMs("2026-06-22T08:00:00Z"), 0)
    ).toEqual([]);
  });

  it("returns startAt when from is before the first occurrence", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    const from = localMs("2026-06-21T20:00:00Z");
    const result = nextNInterval(8, startAt, from, 2);

    expect(result).toEqual([startAt, localMs("2026-06-22T08:00:00Z")]);
  });

  it("handles a negative diff that lands exactly on an interval boundary", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    const from = localMs("2026-06-21T16:00:00Z");
    const result = nextNInterval(8, startAt, from, 2);

    expect(result).toEqual([startAt, localMs("2026-06-22T08:00:00Z")]);
  });

  it("handles a fractional interval offset", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    const from = localMs("2026-06-22T09:00:00Z");
    const result = nextNInterval(8, startAt, from, 1);

    expect(result).toEqual([localMs("2026-06-22T16:00:00Z")]);
  });
});

describe("upcomingDosesFor", () => {
  it("returns ScheduledDose objects for a daily schedule", () => {
    const med = dailyMed(["09:00", "21:00"]);
    const from = localMs("2026-06-22T08:00:00Z");
    const doses = upcomingDosesFor(med, from, 2);

    expect(doses).toEqual([
      {
        medicationId: "med-daily",
        scheduledTime: localMs("2026-06-22T09:00:00Z"),
      },
      {
        medicationId: "med-daily",
        scheduledTime: localMs("2026-06-22T21:00:00Z"),
      },
    ]);
  });

  it("returns ScheduledDose objects for an interval schedule", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    const med = intervalMed(8, startAt);
    const from = localMs("2026-06-22T08:00:00Z");
    const doses = upcomingDosesFor(med, from, 2);

    expect(doses).toEqual([
      {
        medicationId: "med-interval",
        scheduledTime: localMs("2026-06-22T08:00:00Z"),
      },
      {
        medicationId: "med-interval",
        scheduledTime: localMs("2026-06-22T16:00:00Z"),
      },
    ]);
  });

  it("returns an empty array for unsupported schedule types", () => {
    const med = {
      id: "med-unknown",
      scheduleType: "unknown" as const,
      scheduleTimes: {},
    } as unknown as Pick<Medication, "id" | "scheduleType" | "scheduleTimes">;
    expect(upcomingDosesFor(med, localMs("2026-06-22T08:00:00Z"), 2)).toEqual(
      []
    );
  });

  it("returns an empty array when n is 0", () => {
    const med = dailyMed(["09:00"]);
    expect(upcomingDosesFor(med, localMs("2026-06-22T08:00:00Z"), 0)).toEqual(
      []
    );
  });

  it("ignores malformed daily time strings", () => {
    const med = dailyMed(["09:00", "bad", "25:00", "9:5"]);
    const from = localMs("2026-06-22T08:00:00Z");
    const dailySchedule = med.scheduleTimes as {
      readonly times: readonly string[];
    };
    const result = nextNDaily(dailySchedule.times, from, 2);

    expect(result).toEqual([
      localMs("2026-06-22T09:00:00Z"),
      localMs("2026-06-23T09:00:00Z"),
    ]);
  });

  it("returns an empty array when all daily time strings are malformed", () => {
    expect(
      nextNDaily(["bad", "25:00"], localMs("2026-06-22T08:00:00Z"), 2)
    ).toEqual([]);
  });

  it("returns an empty array for invalid interval hours", () => {
    const startAt = localMs("2026-06-22T00:00:00Z");
    expect(
      nextNInterval(0, startAt, localMs("2026-06-22T08:00:00Z"), 2)
    ).toEqual([]);
    expect(
      nextNInterval(-1, startAt, localMs("2026-06-22T08:00:00Z"), 2)
    ).toEqual([]);
  });
});
