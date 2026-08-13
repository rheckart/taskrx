import { describe, expect, it } from "vitest";

import { formatPercent } from "./percent";

/**
 * Rounding, on the one screen where rounding can tell a lie.
 *
 * The adherence figure is a whole number on screen because "77.7777%" reads as
 * false precision about a week of doses. But `Math.round` alone converts 99.6%
 * into "100%", and a person who missed a dose is then told they missed nothing —
 * on the surface built specifically so the number can be trusted.
 *
 * The rounding is deliberately asymmetric, because the two directions are not
 * equally safe here. Understating adherence is a figure someone can act on;
 * overstating it is the flattering number this whole sequence exists to remove.
 * So "100%" is reserved for a genuinely perfect period, and nothing else is
 * special-cased — a figure below half a percent reads as "0%" and is left to the
 * count printed beside it ("1 of 250") to qualify. Nudging that one UP to "1%"
 * would be inventing adherence that was not there.
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

  /**
   * Stated as the property rather than as two convenient inputs, because the
   * property is the rule: across the whole domain this function accepts, "100%"
   * comes back for exactly one value. Two hand-picked numbers would pass just as
   * well against a guard that clamped only the values somebody thought of.
   */
  it("reserves 100% for exactly one input, however close the others get", () => {
    const nearMisses = [
      99.5,
      99.6,
      99.9,
      99.99,
      99.999,
      99.9999,
      99.999999,
      // The float that is as close to 100 as a double can get without being it.
      100 - Number.EPSILON * 50,
    ];

    for (const percent of nearMisses) {
      expect(formatPercent(percent)).not.toBe("100%");
    }
    expect(formatPercent(100)).toBe("100%");
  });

  it("says 0% when nothing at all was taken", () => {
    expect(formatPercent(0)).toBe("0%");
  });

  /**
   * The direction that matters. Rounding 0.4% up to "1%" would report adherence
   * nobody achieved, on the screen whose entire purpose is that the figure can
   * be trusted — the same failure as the pre-#11 100%, just smaller.
   */
  it("never rounds a near-zero figure up into adherence that was not there", () => {
    expect(formatPercent(0.4)).toBe("0%");
    expect(formatPercent(0.0001)).toBe("0%");
  });
});
