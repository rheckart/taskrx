import { describe, expect, it } from "vitest";

import type { Reminder } from "../domain/reminder";
import { createReminder } from "../domain/reminder";
import { dailyTimes } from "../domain/schedule-times";

import type { PlanRefillOptions } from "./planner";
import { planRefill } from "./planner";

const REFILL_OPTIONS: PlanRefillOptions = {
  refillReminderId: "refill-reminder-1",
  now: 1_700_000_000_000,
};

function buildMedication(options: {
  readonly name?: string;
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
}): Reminder {
  return createReminder({
    id: "med-1",
    userId: "user-1",
    createdAt: 0,
    updatedAt: 0,
    kind: "medication",
    name: options.name ?? "Atorvastatin",
    ...(options.pillsRemaining === undefined
      ? {}
      : { pillsRemaining: options.pillsRemaining }),
    ...(options.pillsPerDose === undefined
      ? {}
      : { pillsPerDose: options.pillsPerDose }),
  });
}

function buildTaskReminder(options: {
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
}): Reminder {
  return createReminder({
    id: "task-1",
    userId: "user-1",
    createdAt: 0,
    updatedAt: 0,
    kind: "task",
    name: "Buy milk",
    ...(options.pillsRemaining === undefined
      ? {}
      : { pillsRemaining: options.pillsRemaining }),
    ...(options.pillsPerDose === undefined
      ? {}
      : { pillsPerDose: options.pillsPerDose }),
  });
}

describe("planRefill", () => {
  // Translated 1:1 from RefillPlannerTests.cs (TaskRx.Core.Tests).

  it("needs a refill when pills remaining are within 3x the lookahead window", () => {
    // Plan_30Pills_1PerDose_Window10_NeedsRefill
    const med = buildMedication({
      name: "Atorvastatin",
      pillsRemaining: 30,
      pillsPerDose: 1,
    });

    const decision = planRefill(med, 10, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.name).toBe("Refill Atorvastatin");
      expect(decision.refillReminder.kind).toBe("task");
      expect(decision.refillReminder.active).toBe(true);
      expect(decision.refillReminder.userId).toBe(med.userId);
    }
  });

  it("does not need a refill when pills remaining are well above the threshold", () => {
    // Plan_100Pills_1PerDose_Window10_DoesNotNeedRefill
    const med = buildMedication({
      name: "Atorvastatin",
      pillsRemaining: 100,
      pillsPerDose: 1,
    });

    const decision = planRefill(med, 10, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(false);
  });

  it("does not need a refill when pillsRemaining is absent", () => {
    // Plan_PillsRemainingNull_DoesNotNeedRefill
    const med = buildMedication({ pillsPerDose: 1 });

    const decision = planRefill(med, 10, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(false);
  });

  it("does not need a refill when pillsPerDose is zero", () => {
    // Plan_PillsPerDoseZero_DoesNotNeedRefill
    const med = buildMedication({ pillsRemaining: 10, pillsPerDose: 0 });

    const decision = planRefill(med, 10, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(false);
  });

  it("needs a refill when pills remaining exactly equal the window's threshold", () => {
    // Plan_RemainingDosesExactlyEqualToWindow_NeedsRefill
    const med = buildMedication({ pillsRemaining: 10, pillsPerDose: 1 });

    const decision = planRefill(med, 10, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(true);
  });

  it("with a fractional pillsPerDose, follows the window against the threshold", () => {
    // Plan_FractionalPillsPerDose_WindowMatchesRemainingDoses
    const med = buildMedication({ pillsRemaining: 5, pillsPerDose: 2.5 });

    const decisionWindow2 = planRefill(med, 2, REFILL_OPTIONS);
    expect(decisionWindow2.needsRefill).toBe(true);

    const decisionWindow1 = planRefill(med, 1, REFILL_OPTIONS);
    expect(decisionWindow1.needsRefill).toBe(false);
  });

  it("does not need a refill for a non-medication reminder", () => {
    // Plan_NonMedicationKind_DoesNotNeedRefill
    const task = buildTaskReminder({ pillsRemaining: 5, pillsPerDose: 1 });

    const decision = planRefill(task, 10, REFILL_OPTIONS);

    expect(decision.needsRefill).toBe(false);
  });

  // Extra QA beyond the translated suite: a boundary table plus the guard
  // branches the C# suite doesn't exercise on its own, required for 100%
  // coverage on this port.

  it("boundary: exactly at the threshold needs a refill", () => {
    const med = buildMedication({ pillsRemaining: 15, pillsPerDose: 1 });

    expect(planRefill(med, 5, REFILL_OPTIONS).needsRefill).toBe(true);
  });

  it("boundary: one pill above the threshold does not need a refill", () => {
    const med = buildMedication({ pillsRemaining: 16, pillsPerDose: 1 });

    expect(planRefill(med, 5, REFILL_OPTIONS).needsRefill).toBe(false);
  });

  it("a small fractional pillsPerDose still passes the positive-dose guard", () => {
    const med = buildMedication({ pillsRemaining: 9, pillsPerDose: 0.5 });

    expect(planRefill(med, 3, REFILL_OPTIONS).needsRefill).toBe(true);
  });

  it("does not need a refill when pillsPerDose is absent", () => {
    const med = buildMedication({ pillsRemaining: 10 });

    expect(planRefill(med, 10, REFILL_OPTIONS).needsRefill).toBe(false);
  });

  it("does not need a refill when the lookahead window is zero", () => {
    const med = buildMedication({ pillsRemaining: 0, pillsPerDose: 1 });

    expect(planRefill(med, 0, REFILL_OPTIONS).needsRefill).toBe(false);
  });

  it("does not need a refill when the lookahead window is negative", () => {
    const med = buildMedication({ pillsRemaining: 0, pillsPerDose: 1 });

    expect(planRefill(med, -1, REFILL_OPTIONS).needsRefill).toBe(false);
  });

  it("builds the refill reminder with the caller-supplied id and timestamp", () => {
    const med = buildMedication({
      name: "Lisinopril",
      pillsRemaining: 1,
      pillsPerDose: 1,
    });
    const options: PlanRefillOptions = {
      refillReminderId: "generated-refill-id",
      now: 1_800_000_000_000,
    };

    const decision = planRefill(med, 1, options);

    expect(decision.needsRefill).toBe(true);
    if (decision.needsRefill) {
      expect(decision.refillReminder.id).toBe("generated-refill-id");
      expect(decision.refillReminder.createdAt).toBe(1_800_000_000_000);
      expect(decision.refillReminder.updatedAt).toBe(1_800_000_000_000);
      expect(decision.refillReminder.priority).toBe("high");
      expect(decision.refillReminder.schedule).toEqual(dailyTimes([]));
      expect(decision.refillReminder.tags).toEqual([]);
      expect(decision.refillReminder.caregiverIds).toEqual([]);
    }
  });
});
