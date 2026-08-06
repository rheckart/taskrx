import {
  GRACE_PERIOD_MS,
  SWEEP_LOOKBACK_MS,
  planMissedSweep,
} from "@taskrx/shared";

import * as instancesRepo from "../db/repos/reminderInstances";
import * as remindersRepo from "../db/repos/reminders";

/**
 * The missed sweep's persistence pass: read, plan, write.
 *
 * Every rule about what is Missed lives in `planMissedSweep` in
 * `@taskrx/shared`, which is pure and tested there, together with the Grace
 * Period and lookback ADR 0005 settled. This file decides nothing — it reads the
 * two tables, hands them over, and persists the verdicts. If a judgement about
 * time ever appears below, it is in the wrong file.
 *
 * It exists because a Reminder Instance is minted lazily, only when someone
 * acts. Until this ran, an Occurrence nobody answered produced no row at all and
 * then fell out of the forward-looking Occurrence window — so it did not become
 * Missed, it ceased to exist, and Adherence scored a person who took one dose in
 * thirty at 100%.
 *
 * ## Why the calls are serialized
 *
 * A sweep is triggered on every foreground, and the plan is computed from a
 * snapshot of what is already stored. Two overlapping sweeps would both read
 * "no Instance at 09:00" and both mint one, so the fix for a moment that
 * vanished would be a moment recorded twice. Chaining them means the second
 * reads the rows the first wrote and plans nothing, which is also what makes
 * running it more often than necessary free.
 */

/**
 * The tail of the sweep chain. Rejections are swallowed *for the chain only* —
 * the caller still receives the rejected promise — so one failed sweep does not
 * poison every later one.
 */
let queue: Promise<unknown> = Promise.resolve();

async function sweep(userId: string, now: number): Promise<number> {
  // Active only — unlike the timeline, which needs a stopped reminder to render
  // what was already recorded against it. The sweep never does: an inactive
  // reminder predicts no moments, and a stored Instance carries its own kind, so
  // it is settled without its parent being in hand at all.
  const reminders = await remindersRepo.list(userId);
  // The same window the planner bounds itself by. Reading wider would cost rows
  // the plan then discards; reading narrower would hide answers that must block
  // a second Instance being minted at a moment already settled.
  const instances = await instancesRepo.list({
    userId,
    since: now - SWEEP_LOOKBACK_MS,
    until: now,
  });

  const verdicts = planMissedSweep({
    reminders,
    instances,
    now,
    gracePeriodMs: GRACE_PERIOD_MS,
    lookbackMs: SWEEP_LOOKBACK_MS,
  });

  for (const verdict of verdicts) {
    if (verdict.instanceId !== undefined) {
      await instancesRepo.markMissed(verdict.instanceId);
      continue;
    }

    await instancesRepo.create({
      userId,
      reminderId: verdict.reminderId,
      reminderKind: verdict.reminderKind,
      scheduledTime: verdict.scheduledTime,
      status: "missed",
    });
  }

  return verdicts.length;
}

/**
 * Sweep this user's unanswered moments, returning how many were recorded as
 * Missed.
 *
 * `now` is a parameter rather than a call to `Date.now()` inside so a caller can
 * state the instant it means, and so the whole path stays as testable as the
 * planner it wraps.
 */
export function runMissedSweep(
  userId: string,
  now: number = Date.now()
): Promise<number> {
  const next = queue.then(() => sweep(userId, now));
  queue = next.catch(() => undefined);
  return next;
}
