import { describe, expect, it } from "vitest";

import type { Reminder, ScheduleTimes } from "../domain/index";
import { createReminder, dailyTimes } from "../domain/index";

import { entersMedicationHorizon, tierFor } from "./tier";

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
