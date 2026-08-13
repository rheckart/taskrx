import type { AdherencePeriod, AdherenceStats } from "@taskrx/shared";
import {
  ADHERENCE_PERIOD_DAYS,
  adherencePeriod,
  computeAdherence,
} from "@taskrx/shared";

import * as instancesRepo from "../db/repos/reminderInstances";
import { runMissedSweep } from "../sweep/runner";

/**
 * The Adherence read: sweep, then read, then compute.
 *
 * Every rule about what the figure is lives in `computeAdherence` and every rule
 * about the window it covers lives in `adherencePeriod`, both pure and both
 * tested in `@taskrx/shared`. This file decides nothing. What it owns is an
 * ORDER, and the order is the whole reason issue #16 was blocked on #11.
 *
 * ## Why the sweep runs first here and last on the timeline
 *
 * `app/(app)/index.tsx` renders the day and sweeps afterwards, deliberately: the
 * rows a sweep settles were already on screen a moment earlier as `pending`,
 * which is what they were, so nobody is shown anything false while it works.
 *
 * Adherence has no such reading. A dose nobody answered has no stored Instance
 * at all until the sweep mints one, and the calculator counts what it is given —
 * so a figure computed before the sweep is not merely stale, it is wrong in the
 * flattering direction, and someone who took one dose in thirty is told they
 * read as 100%. There is no intermediate state worth showing, because the
 * intermediate state is the bug. The spec says so directly: the sweep "runs when
 * the app comes to the foreground and before any Adherence read."
 *
 * Putting the sweep inside this function rather than in the screen is what makes
 * that structural. A second Adherence surface cannot forget to do it, because
 * there is no way to obtain the figure that skips it.
 *
 * ## Why a failed sweep fails the whole read
 *
 * The rejection is not swallowed. Computing on top of a sweep that did not finish
 * produces exactly the flattering number this path exists to prevent, and it
 * produces it silently — the figure looks like an answer either way. A person is
 * better served by "couldn't load" and a retry than by a confident 100%. The
 * timeline can take the opposite view of the same failure because the timeline
 * is still correct without it.
 */

export interface AdherenceReading {
  readonly stats: AdherenceStats;
  readonly period: AdherencePeriod;
}

/**
 * Compute this user's medication Adherence over the last
 * `ADHERENCE_PERIOD_DAYS` local calendar days.
 *
 * `now` is a parameter rather than a call to `Date.now()` inside so a caller can
 * state the instant it means, matching `runMissedSweep` and keeping this path as
 * testable as the pure functions it wraps.
 */
export async function readAdherence(
  userId: string,
  now: number = Date.now()
): Promise<AdherenceReading> {
  await runMissedSweep(userId, now);

  const period = adherencePeriod(now, ADHERENCE_PERIOD_DAYS);

  // No `reminderKind` filter, though one is available and would read fewer
  // rows. Adherence being medication-only is a rule, and it is stated once, in
  // the calculator — narrowing the query here would state it a second time in a
  // place no test covers, and the two could then disagree.
  const instances = await instancesRepo.list({
    userId,
    since: period.fromMs,
    until: period.toMs,
  });

  return {
    stats: computeAdherence(instances, period.fromMs, period.toMs),
    period,
  };
}
