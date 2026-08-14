import { describe, expect, it } from "vitest";

import type { Reminder } from "../domain/reminder";
import { createReminder } from "../domain/reminder";
import type { ScheduleTimes } from "../domain/schedule-times";
import { dailyTimes, noneTimes, weeklyTimes } from "../domain/schedule-times";

import type { PlanRefillOptions } from "./planner";
import { planRefill, refillReminderName } from "./planner";

/** Local wall-clock helper, matching the pattern in `occurrences.test.ts`. */
function local(
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0
): number {
  return new Date(year, month - 1, day, hour, minute, 0, 0).getTime();
}

/** An hour before the first dose of the day, so today's dose is still to come. */
const NOW = local(2026, 8, 13, 8);

const REFILL_OPTIONS: PlanRefillOptions = {
  refillReminderId: "refill-reminder-1",
  now: NOW,
};

function buildMedication(options: {
  readonly name?: string;
  readonly schedule?: ScheduleTimes;
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
  readonly kind?: Reminder["kind"];
}): Reminder {
  return createReminder({
    id: "med-1",
    userId: "user-1",
    createdAt: 0,
    updatedAt: 0,
    kind: options.kind ?? "medication",
    name: options.name ?? "Atorvastatin",
    schedule: options.schedule ?? dailyTimes(["09:00"]),
    ...(options.pillsRemaining === undefined
      ? {}
      : { pillsRemaining: options.pillsRemaining }),
    ...(options.pillsPerDose === undefined
      ? {}
      : { pillsPerDose: options.pillsPerDose }),
    ...(options.refillReminderDays === undefined
      ? {}
      : { refillReminderDays: options.refillReminderDays }),
  });
}

/**
 * `projection.test.ts` runs every rule about WHEN a refill is due; these cases
 * are about WHAT is raised when one is, and about the decision reaching this
 * function unchanged rather than being made a second time here.
 *
 * The original suite was translated 1:1 from `RefillPlannerTests.cs`, whose
 * cases all pinned the `dosesInLookaheadWindow * 3` threshold. Issue #17
 * replaced that threshold — it compares pills to doses and so ignores
 * `pillsPerDose`, which the ticket forbids — so those cases now state the rule
 * that replaced it, against the projection, in `projection.test.ts`.
 */
describe("planRefill", () => {
  it("raises a Refill Reminder when the supply runs out inside the warning window", () => {
    const med = buildMedication({
      name: "Atorvastatin",
      pillsRemaining: 5,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    const decision = planRefill(med, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.name).toBe("Refill Atorvastatin");
      expect(decision.refillReminder.userId).toBe(med.userId);
      expect(decision.refillReminder.active).toBe(true);
    }
  });

  it("raises nothing while the supply outlasts the warning window", () => {
    const med = buildMedication({
      pillsRemaining: 100,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    expect(planRefill(med, REFILL_OPTIONS).needsRefill).toBe(false);
  });

  it("raises nothing for a reminder with no supply to run out of", () => {
    const med = buildMedication({ pillsPerDose: 1 });

    expect(planRefill(med, REFILL_OPTIONS).needsRefill).toBe(false);
  });

  it("raises nothing for a task, whatever numbers it carries", () => {
    const task = buildMedication({
      kind: "task",
      pillsRemaining: 0,
      pillsPerDose: 1,
    });

    expect(planRefill(task, REFILL_OPTIONS).needsRefill).toBe(false);
  });

  /**
   * Collecting a prescription is an errand, and ADR 0003 keeps tasks out of the
   * bounded medication Horizon — so raising a refill can never cost a dose its
   * alarm slot. A Refill Reminder that was itself a `medication` would compete
   * with the medication it is about.
   */
  it("raises a task rather than a second medication", () => {
    const med = buildMedication({ pillsRemaining: 0, pillsPerDose: 1 });

    const decision = planRefill(med, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.kind).toBe("task");
    }
  });

  /**
   * The C# built this with a daily schedule carrying no times, because the
   * `none` variant did not exist yet. That is the misconfiguration `none`
   * replaced: it yields no Occurrences while claiming a daily schedule, so the
   * raised Reminder would appear on neither the timeline nor the Backlog — a
   * refill warning nobody can be shown is not a warning.
   */
  it("raises it into the Backlog, which is where an errand with no moment lives", () => {
    const med = buildMedication({ pillsRemaining: 0, pillsPerDose: 1 });

    const decision = planRefill(med, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.schedule).toEqual(noneTimes());
    }
  });

  it("raises it at high priority, above the ordinary errand", () => {
    const med = buildMedication({ pillsRemaining: 0, pillsPerDose: 1 });

    const decision = planRefill(med, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.priority).toBe("high");
    }
  });

  it("stamps it with the caller's id and instant rather than inventing either", () => {
    const med = buildMedication({
      name: "Lisinopril",
      pillsRemaining: 0,
      pillsPerDose: 1,
    });
    const options: PlanRefillOptions = {
      refillReminderId: "generated-refill-id",
      now: NOW,
    };

    const decision = planRefill(med, options);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.id).toBe("generated-refill-id");
      expect(decision.refillReminder.createdAt).toBe(NOW);
      expect(decision.refillReminder.updatedAt).toBe(NOW);
      expect(decision.refillReminder.tags).toEqual([]);
      expect(decision.refillReminder.caregiverIds).toEqual([]);
    }
  });

  /**
   * The supply is judged at `options.now`, not at some other instant. An empty
   * bottle for a Monday medication is four days away from mattering on the
   * Thursday before, and is due on the Monday itself.
   */
  it("judges the supply at the instant it was asked about", () => {
    const med = buildMedication({
      schedule: weeklyTimes([1], ["09:00"]),
      pillsRemaining: 0,
      pillsPerDose: 1,
      refillReminderDays: 0,
    });

    // Thursday 2026-08-13.
    expect(
      planRefill(med, { refillReminderId: "r", now: NOW }).needsRefill
    ).toBe(false);
    // The Monday after.
    expect(
      planRefill(med, { refillReminderId: "r", now: local(2026, 8, 17, 8) })
        .needsRefill
    ).toBe(true);
  });

  it("raises nothing for a medication that never comes due", () => {
    const med = buildMedication({
      schedule: noneTimes(),
      pillsRemaining: 0,
      pillsPerDose: 1,
    });

    expect(planRefill(med, REFILL_OPTIONS).needsRefill).toBe(false);
  });
});

describe("refillReminderName", () => {
  /**
   * The name is the only handle a caller has on a Refill Reminder it raised
   * earlier, and the raise has to be idempotent — a screen that can be opened
   * twice must not leave two. So the raiser and the "is one already standing?"
   * check must produce the same string, which is why it is a function and not
   * a template literal written out at each end.
   */
  it("names the Reminder the raiser will build", () => {
    const med = buildMedication({
      name: "Lisinopril",
      pillsRemaining: 0,
      pillsPerDose: 1,
    });

    const decision = planRefill(med, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.name).toBe(refillReminderName(med));
    }
  });
});
