/**
 * Reconciling the Backlog with the refill projections: which Refill Reminders
 * to raise, and which standing ones to withdraw.
 *
 * This is a rule, so it is here rather than in the device read that performs the
 * writes. `docs/specs/fused-timeline.md` is explicit about the boundary — the
 * on-device layer "persists what the pure functions decide", and "if a rule ends
 * up inside a repository, that is a signal the extraction was incomplete". A
 * first draft of #17 had the raise-skip-retire decision inside
 * `apps/mobile/src/refill/read.ts`, where nothing gated it and the only way to
 * exercise it was a hand-rolled fake of expo-sqlite.
 *
 * ## What a "standing" Refill Reminder is
 *
 * There is no column pointing from a raised Reminder back at the medication it
 * came from, so the pairing is by name — `refillReminderName`, one definition
 * shared by the raiser and this check. That makes it exact for the Reminders
 * this code raises and blind to a same-named task somebody typed themselves,
 * which is worth naming and much cheaper than a schema change for a collision
 * that requires typing "Refill Atorvastatin" by hand.
 *
 * ## Why withdrawing needs more than "no refill due"
 *
 * A supply the app can no longer see is not a supply that has been replenished.
 * Clearing the pill count leaves the projection blind rather than reassured, and
 * a warning retired on the strength of that is the app forgetting rather than
 * the person collecting. So an unprojectable medication leaves a standing
 * warning alone — with one exception, which is the medication having been
 * stopped: nothing is being consumed, so there is nothing left to warn about,
 * and a Refill Reminder for a treatment somebody has come off is an instruction
 * to buy medicine they were told to stop taking.
 *
 * Pure: the caller supplies the instant, the ids, and does the writing.
 */

import type { Reminder } from "../domain/reminder";

import { planRefill, refillReminderName } from "./planner";
import { projectRefill } from "./projection";

export interface ReconcileRefillsInput {
  /**
   * Every medication to consider, stopped ones included. A stopped medication
   * raises nothing, but it can still have a standing warning to withdraw.
   */
  readonly medications: readonly Reminder[];
  /** The task Reminders currently standing, from which the raised ones are recognised. */
  readonly standing: readonly Reminder[];
  /** The instant the supplies are judged at, unix ms. */
  readonly now: number;
  /**
   * Ids for the Reminders to be raised, one per call.
   *
   * A generator rather than a list, because how many will be raised is what this
   * function is working out. It keeps the id-minting outside, the same way
   * `PlanRefillOptions` does — nothing here calls `crypto.randomUUID()`.
   */
  readonly mintId: () => string;
}

export interface RefillReconciliation {
  /** Refill Reminders to write, exactly as `planRefill` built them. */
  readonly raise: readonly Reminder[];
  /** Standing Refill Reminders to withdraw, by id. */
  readonly retire: readonly Reminder[];
}

/**
 * What the Backlog should gain and lose for it to agree with the projections.
 *
 * Idempotent by construction: a medication whose warning is already standing
 * appears in neither list, so running this on every glance at a screen does
 * nothing after the first.
 */
export function reconcileRefills(
  input: ReconcileRefillsInput
): RefillReconciliation {
  const { medications, standing, now, mintId } = input;

  const standingByName = new Map(standing.map((task) => [task.name, task]));
  const raise: Reminder[] = [];
  const retire: Reminder[] = [];

  for (const medication of medications) {
    const name = refillReminderName(medication);
    const alreadyStanding = standingByName.get(name);
    const decision = planRefill(medication, {
      refillReminderId: mintId(),
      now,
    });

    if (decision.needsRefill) {
      if (alreadyStanding !== undefined) continue;
      raise.push(decision.refillReminder);
      // Recorded as standing straight away, so that two medications sharing a
      // name — a duplicate somebody entered twice, most likely — produce one
      // Refill Reminder rather than two that nothing afterwards can tell apart.
      standingByName.set(name, decision.refillReminder);
      continue;
    }

    if (alreadyStanding === undefined) continue;

    const projection = projectRefill(medication, now);
    const withdrawable =
      projection.projected || projection.reason === "stopped";
    if (withdrawable) {
      retire.push(alreadyStanding);
      standingByName.delete(name);
    }
  }

  return { raise, retire };
}
