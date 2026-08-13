import { describe, expect, it } from "vitest";

import { formatPercent } from "./percent";

/**
 * Rounding, on the one screen where rounding can tell a lie.
 *
 * The adherence figure is a whole number on screen because "77.7777%" reads as
 * false precision about a week of doses. But `Math.round` alone converts 99.6%
 * into "100%", and a person who missed a dose this week is then told they missed
 * nothing — on the surface built specifically so the number can be trusted. The
 * mirror case is as bad in the other direction: 0.4% shown as "0%" tells someone
 * who did take a dose that they took none.
 *
 * So the two endpoints are reserved. "100%" means every dose, and "0%" means no
 * dose, and every other figure rounds normally.
 */
describe("formatPercent", () => {
  it("rounds an ordinary figure to a whole number", () => {
    expect(formatPercent(77.7777)).toBe("78%");
    expect(formatPercent(50)).toBe("50%");
    expect(formatPercent(33.3333)).toBe("33%");
  });

  it("says 100% only when nothing was missed", () => {
    expect(formatPercent(100)).toBe("100%");
  });

  it("does not round a missed dose away into a perfect score", () => {
    expect(formatPercent(99.6)).toBe("99%");
    expect(formatPercent(99.9999)).toBe("99%");
  });

  it("says 0% only when nothing at all was taken", () => {
    expect(formatPercent(0)).toBe("0%");
  });

  it("does not round a taken dose away into nothing", () => {
    expect(formatPercent(0.4)).toBe("1%");
    expect(formatPercent(0.0001)).toBe("1%");
  });
});
