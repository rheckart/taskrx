/**
 * How this app writes an adherence percentage.
 *
 * A whole number, because a figure like "77.7777%" claims a precision a week of
 * doses does not have. But plain rounding is not safe at the two ends, and this
 * is the one screen where that matters: `Math.round` turns 99.6% into "100%",
 * so a person who missed a dose is told they missed none — on the surface built
 * so the number could be trusted. Issue #16 asks for "a figure that can be
 * trusted", and a headline that reads as a clean sweep when it was not is the
 * same flattering failure as the one the missed sweep removed, arriving through
 * the formatter instead of through the data.
 *
 * The mirror case is the same lie pointed the other way: 0.4% shown as "0%"
 * tells someone who did take a dose that they took none, which on a treatment
 * surface reads as a record of total failure.
 *
 * So both endpoints are reserved. "100%" is only ever a perfect week and "0%" is
 * only ever an empty one; everything between them rounds to the nearer whole
 * number and is nudged off the endpoint if it lands there.
 */
export function formatPercent(percent: number): string {
  const rounded = Math.round(percent);

  if (rounded === 100 && percent < 100) return "99%";
  if (rounded === 0 && percent > 0) return "1%";

  return `${String(rounded)}%`;
}
