/**
 * The window Adherence is reported over.
 *
 * `computeAdherence` takes a `[fromMs, toMs]` pair and says nothing about where
 * it comes from; this is where it comes from. It is here rather than in the
 * screen that renders it for the same reason the calculator is: picking the
 * window is local calendar arithmetic, it is wrong in a way nobody notices
 * twice a year, and the shared package is where that gets pinned by a test.
 *
 * ## Seven days, because that is what the sweep can account for
 *
 * ADR 0005 fixed the sweep's lookback at seven days and tied the two numbers
 * together in as many words: "Adherence is reported by day and reviewed by week,
 * so a sweep that reaches a week back can always account for every day a person
 * is looking at."
 *
 * The same ADR records what a wider period would cost. An Occurrence more than
 * seven days old that nobody answered is never minted as Missed — the sweep does
 * not reach it, and the next sweep does not either, because the bound is measured
 * from the current instant each time. So a thirty-day figure computed from stored
 * Instances counts the doses a person answered and silently omits the ones they
 * did not, across exactly the absences where that difference matters. That is the
 * flattering number the missed sweep was built to remove, reappearing past day
 * seven. A period the sweep can vouch for end to end does not have the failure at
 * all, which is worth more than a longer chart.
 *
 * ## Local calendar days, not 24-hour blocks
 *
 * `now - days * 86_400_000` is the obvious implementation and it drifts an hour
 * off midnight either side of a daylight-saving transition, clipping the first
 * day of the window or doubling it. The daily buckets are grouped by local
 * calendar date, so a window that starts at 01:00 puts part of a day in the
 * figure and leaves the rest out, and that day's bucket disagrees with every
 * other one.
 *
 * That is `../time/local-day.ts`'s problem rather than this file's: the timeline
 * picks a day the same way, and the two were computing the same span from the
 * same calendar fields — one of them inside a screen. A period is now just its
 * two ends, each asked for by offset.
 *
 * Pure function: no clock, no globals. The caller supplies `now`.
 */

import { localDayBounds } from "../time/local-day";

/**
 * How many local calendar days Adherence covers, the day holding `now`
 * included. Seven, matching `SWEEP_LOOKBACK_MS` — see the file header for why
 * they are the same number and not a coincidence.
 */
export const ADHERENCE_PERIOD_DAYS = 7;

/** A `[fromMs, toMs]` pair in the shape `computeAdherence` reads. */
export interface AdherencePeriod {
  readonly fromMs: number;
  readonly toMs: number;
}

/**
 * The `days` local calendar days ending with the one `now` falls in, from that
 * first day's midnight to the last instant of the last.
 *
 * Both ends are inclusive, because `computeAdherence`'s window is
 * (`scheduledTime >= fromMs && scheduledTime <= toMs`) — so the end is the last
 * millisecond of the day rather than the next day's midnight, which would count
 * a dose scheduled at exactly 00:00 tomorrow.
 */
export function adherencePeriod(now: number, days: number): AdherencePeriod {
  // `days - 1` because the day holding `now` is one of them: a seven-day period
  // reaches back six days, not seven.
  return {
    fromMs: localDayBounds(now, -(days - 1)).start,
    toMs: localDayBounds(now, 0).end,
  };
}
