import { describe, expect, it } from "vitest";

import type { Medication } from "../index";

import { selectUpcomingDoses } from "./horizon";

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
