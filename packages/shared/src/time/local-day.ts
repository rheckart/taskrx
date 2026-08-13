/**
 * The bounds of a local calendar day.
 *
 * One definition rather than two: the timeline picks a day to render and the
 * adherence period picks the day its window ends on, and both were computing
 * the same midnight-to-last-millisecond span from `Date`'s local calendar
 * fields — one of them inside a screen, where the spec puts no rules and no
 * test could reach it.
 *
 * ## Why it is not `now - offset * 86_400_000`
 *
 * A local day is 23 or 25 hours twice a year, and both callers are wrong in a
 * way nobody notices if they assume otherwise. The timeline would shift its
 * window an hour and drop a 23:00 row off the end of the day it belongs to, or
 * show a 00:30 one on two days running. The adherence period would begin an hour
 * off midnight, clipping the first day of its own window so that day's bucket
 * disagreed with every other one.
 *
 * Built from the calendar fields instead — the pattern `../schedule/occurrences.ts`
 * established — the boundary is midnight because it was asked for midnight,
 * whatever the offset did in between. `Date` normalizes an out-of-range day, so
 * the month and the year roll over on their own and no arithmetic is needed for
 * them here.
 *
 * Pure: no clock, no globals. The caller supplies `now`.
 */

/**
 * Midnight to the last millisecond of a local calendar day. Structurally a
 * `TimelineWindow`, which is what lets the timeline use it directly.
 */
export interface LocalDayBounds {
  readonly start: number;
  readonly end: number;
}

/**
 * The local calendar day `offsetDays` from the day holding `now` — `0` for that
 * day itself, negative for earlier, positive for later.
 *
 * `end` is the day's last millisecond rather than the next day's midnight, so
 * that a caller comparing with `<=` does not catch a moment scheduled at exactly
 * 00:00 tomorrow. Both of this repo's consumers compare inclusively.
 */
export function localDayBounds(
  now: number,
  offsetDays: number
): LocalDayBounds {
  const base = new Date(now);
  const year = base.getFullYear();
  const month = base.getMonth();
  const day = base.getDate() + offsetDays;

  return {
    start: new Date(year, month, day, 0, 0, 0, 0).getTime(),
    end: new Date(year, month, day, 23, 59, 59, 999).getTime(),
  };
}
