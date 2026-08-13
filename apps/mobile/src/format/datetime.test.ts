import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { dayKeyToLocalMs, formatDayKey } from "./datetime";

/**
 * The day key round-trip, and the one-line mistake that breaks it.
 *
 * `computeAdherence` groups by local calendar date and hands back a
 * `"YYYY-MM-DD"` key per day. Rendering that key means turning it back into a
 * date, and the obvious way — `new Date("2026-08-04")` — is specified to parse a
 * date-only string as UTC midnight. Every timezone west of Greenwich then
 * renders it as the day before, so the adherence breakdown blames a person for
 * Sunday's misses on Saturday's row. West of the date line it goes the other way.
 *
 * The bug is invisible under this suite's default `TZ=UTC`, which is exactly why
 * the cases below set a zone with a real offset.
 */
describe("dayKeyToLocalMs", () => {
  const previousTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });

  afterAll(() => {
    process.env.TZ = previousTz;
  });

  it("lands on local midnight of the day the key names", () => {
    const ms = dayKeyToLocalMs("2026-08-04");
    const date = new Date(ms);

    expect(date.getFullYear()).toBe(2026);
    expect(date.getMonth()).toBe(7);
    expect(date.getDate()).toBe(4);
    expect(date.getHours()).toBe(0);
  });

  it("does not slip to the previous day in a western time zone", () => {
    expect(dayKeyToLocalMs("2026-08-04")).toBe(new Date(2026, 7, 4).getTime());
  });

  it("round-trips the key a day of the year's first hours would produce", () => {
    expect(dayKeyToLocalMs("2026-01-01")).toBe(new Date(2026, 0, 1).getTime());
  });

  it("lands on local midnight on the day the clocks go forward", () => {
    // 2027-03-14 is the US spring-forward date, and 00:00 exists on it — the
    // hour that does not is 02:00.
    const date = new Date(dayKeyToLocalMs("2027-03-14"));

    expect(date.getDate()).toBe(14);
    expect(date.getHours()).toBe(0);
  });
});

describe("formatDayKey", () => {
  const previousTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });

  afterAll(() => {
    process.env.TZ = previousTz;
  });

  /**
   * Asserted against the same day built locally rather than against a literal
   * string: the label follows the device's locale conventions by design (`[]`),
   * so pinning "Tue, Aug 4" here would pin the test runner's locale as though it
   * were the rule. What IS the rule is which day gets named.
   */
  it("names the day the key denotes", () => {
    expect(formatDayKey("2026-08-04")).toBe(
      new Date(2026, 7, 4).toLocaleDateString([], {
        weekday: "short",
        month: "short",
        day: "numeric",
      })
    );
  });

  it("does not name the day before it", () => {
    expect(formatDayKey("2026-08-04")).not.toBe(
      new Date(2026, 7, 3).toLocaleDateString([], {
        weekday: "short",
        month: "short",
        day: "numeric",
      })
    );
  });
});
