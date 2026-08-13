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
 * ## The guard is about this function's domain, not about its callers
 *
 * Worth stating, because the arithmetic invites the opposite conclusion: today's
 * only caller cannot reach the guard. It fires for a percentage in [99.5, 100),
 * and the largest fraction below 1 with denominator n is (n - 1)/n, so n must
 * reach 200 before any imperfect week can round to 100 — about 29 doses a day
 * across the seven-day period, which no schedule this app offers produces.
 *
 * That is not a reason to drop it. This takes a `number`, and its contract has
 * to hold across the numbers it accepts rather than across the subset one
 * caller currently produces; a formatter that is only correct because of what
 * happens to be upstream of it is a bug waiting for the upstream to change.
 * Nor is it speculative generality — there is no parameter, no hook and no
 * abstraction here, only two lines that keep the function honest over its own
 * input domain.
 */
export function formatPercent(percent: number): string {
  const rounded = Math.round(percent);

  if (rounded === 100 && percent < 100) return "99%";

  return `${String(rounded)}%`;
}
