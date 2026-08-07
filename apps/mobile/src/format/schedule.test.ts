import { describe, expect, it } from "vitest";

import { describeSchedule } from "./schedule";

/**
 * What a person reads back when the app tells them when something happens.
 *
 * This is the sentence the natural-language entry surface shows before it
 * saves anything, so "what the parser understood" is only as reviewable as
 * these strings are true. A schedule that renders as though it were set when
 * it is not — an empty `daily`, a `weekly` with no days — is the specific
 * failure worth pinning: it invites someone to accept a reminder that will
 * never come due.
 *
 * The `once` arm is asserted on its lead-in only. It formats through the
 * device locale, which is the point of it, and pinning a locale's rendering
 * would be asserting on the platform rather than on this function.
 */

describe("describeSchedule", () => {
  it("names every daily time", () => {
    expect(describeSchedule({ type: "daily", times: ["08:00", "18:00"] })).toBe(
      "Daily at 08:00, 18:00"
    );
  });

  it("says so when a daily schedule has no time to come due at", () => {
    expect(describeSchedule({ type: "daily", times: [] })).toBe(
      "Daily (no times set)"
    );
  });

  it("names the weekdays and the times they apply on", () => {
    expect(
      describeSchedule({
        type: "weekly",
        daysOfWeek: [1, 3, 5],
        times: ["09:00"],
      })
    ).toBe("Mon, Wed, Fri at 09:00");
  });

  it("says so when a weekly schedule names no day", () => {
    expect(
      describeSchedule({ type: "weekly", daysOfWeek: [], times: ["09:00"] })
    ).toBe("Weekly (not fully set)");
  });

  it("says so when a weekly schedule names no time", () => {
    expect(
      describeSchedule({ type: "weekly", daysOfWeek: [2], times: [] })
    ).toBe("Weekly (not fully set)");
  });

  it("names the gap and where an interval starts", () => {
    expect(
      describeSchedule({
        type: "interval",
        hours: 8,
        startAt: Date.UTC(2026, 0, 15, 7, 30),
      })
    ).toBe("Every 8h starting 07:30");
  });

  it("writes an interval start as a two-digit clock time", () => {
    expect(
      describeSchedule({
        type: "interval",
        hours: 6,
        startAt: Date.UTC(2026, 0, 15, 7, 5),
      })
    ).toBe("Every 6h starting 07:05");
  });

  it("says a single moment happens once", () => {
    expect(
      describeSchedule({ type: "once", at: Date.UTC(2026, 0, 15, 7, 30) })
    ).toMatch(/^Once, /);
  });

  it("says a reminder with no time is in the backlog", () => {
    expect(describeSchedule({ type: "none" })).toBe(
      "In your backlog — no time set"
    );
  });
});
