/**
 * How this app writes an adherence percentage.
 *
 * A whole number, because a figure like "77.7777%" claims a precision a week of
 * doses does not have. But plain rounding is not safe at the top, and this is
 * the one screen where that matters: `Math.round` turns 99.6% into "100%", so a
 * person who missed a dose is told they missed none — on the surface built so
 * the number could be trusted. Issue #16 asks for "a figure that can be
 * trusted", and a headline that reads as a clean sweep when it was not is the
 * same flattering failure as the one the missed sweep removed, arriving through
 * the formatter instead of through the data.
 *
 * ## Why only the top end is reserved
 *
 * The mirror case looks symmetrical and is not. Rounding 0.4% up to "1%" would
 * report adherence nobody achieved — the identical failure, just small — and on
 * this surface the two directions are not equally safe. Understating adherence
 * gives a person a figure they can act on; overstating it is the exact thing
 * this sequence of tickets exists to stop. So a near-zero figure is left to read
 * as "0%", and the count printed beside it ("1 of 250") is what says a dose was
 * taken. The fraction is already on the row; the percentage does not need to
 * round upward to carry it.
 *
 * With the seven-day period the top-end guard is defensive rather than live:
 * missing one dose of n rounds to 100% only once n reaches about 200, which a
 * week does not reach. It is kept because it becomes reachable the moment the
 * period widens, and because that is the direction where being wrong matters.
 */
export function formatPercent(percent: number): string {
  const rounded = Math.round(percent);

  if (rounded === 100 && percent < 100) return "99%";

  return `${String(rounded)}%`;
}
