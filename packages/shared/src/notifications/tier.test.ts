import { describe, expect, it } from "vitest";

import type { Reminder, ScheduleTimes } from "../domain/index";
import { createReminder, dailyTimes } from "../domain/index";

import { entersMedicationHorizon, entersTaskHorizon, tierFor } from "./tier";

function reminder(
  kind: Reminder["kind"],
  overrides: {
    readonly priority?: Reminder["priority"];
    readonly schedule?: ScheduleTimes;
  } = {}
): Reminder {
  return createReminder({
    id: `r-${kind}`,
    userId: "user-1",
    kind,
    name: `Reminder ${kind}`,
    schedule: overrides.schedule ?? dailyTimes(["09:00"]),
    ...(overrides.priority === undefined
      ? {}
      : { priority: overrides.priority }),
    createdAt: 0,
    updatedAt: 0,
  });
}

describe("tierFor", () => {
  it("puts a medication in the medication Tier", () => {
    expect(tierFor("medication")).toBe("medication");
  });

  it("puts a task in the task Tier", () => {
    expect(tierFor("task")).toBe("task");
  });
});

describe("entersMedicationHorizon", () => {
  it("admits a medication", () => {
    expect(entersMedicationHorizon(reminder("medication"))).toBe(true);
  });

  it("refuses a task", () => {
    expect(entersMedicationHorizon(reminder("task"))).toBe(false);
  });

  it("refuses a critical task, because Tier is Kind and not Priority", () => {
    expect(
      entersMedicationHorizon(reminder("task", { priority: "critical" }))
    ).toBe(false);
  });

  it("admits a low-priority medication, for the same reason", () => {
    expect(
      entersMedicationHorizon(reminder("medication", { priority: "low" }))
    ).toBe(true);
  });
});

describe("entersTaskHorizon", () => {
  it("admits a task", () => {
    expect(entersTaskHorizon(reminder("task"))).toBe(true);
  });

  it("refuses a medication", () => {
    expect(entersTaskHorizon(reminder("medication"))).toBe(false);
  });

  it("refuses a low-priority medication, because Tier is Kind and not Priority", () => {
    expect(entersTaskHorizon(reminder("medication", { priority: "low" }))).toBe(
      false
    );
  });

  it("admits a low-priority task, for the same reason", () => {
    expect(entersTaskHorizon(reminder("task", { priority: "low" }))).toBe(true);
  });
});

/**
 * The two budgets partition the Reminders rather than sharing them. This is
 * what ADR 0003 means by "by construction": there is no Kind that competes for
 * both, and none that falls through and is armed by neither.
 */
describe("the two Horizons together", () => {
  it("admits every Kind to exactly one of them", () => {
    for (const kind of ["medication", "task"] as const) {
      const subject = reminder(kind);
      expect([
        entersMedicationHorizon(subject),
        entersTaskHorizon(subject),
      ]).toContainEqual(true);
      expect(entersMedicationHorizon(subject)).not.toBe(
        entersTaskHorizon(subject)
      );
    }
  });
});
