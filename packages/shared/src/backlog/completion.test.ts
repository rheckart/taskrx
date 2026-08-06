import { describe, expect, it } from "vitest";

import type { Reminder, ScheduleTimes } from "../domain/index";
import { createReminder, dailyTimes, noneTimes } from "../domain/index";

import { isBacklog, planBacklogCompletion } from "./completion";

const ACTED_AT = Date.UTC(2026, 7, 6, 15, 42, 17);

function reminder(
  schedule: ScheduleTimes,
  kind: "medication" | "task" = "task"
): Reminder {
  return createReminder({
    id: "r1",
    userId: "user-1",
    kind,
    name: "Call the pharmacy",
    schedule,
    createdAt: 0,
    updatedAt: 0,
  });
}

describe("isBacklog", () => {
  it("is a reminder with no schedule at all", () => {
    expect(isBacklog(reminder(noneTimes()))).toBe(true);
  });

  it("is not a reminder whose daily schedule merely has no times", () => {
    // The misconfiguration `none` replaces: it also produces no Occurrences,
    // and it is not a Backlog commitment.
    expect(isBacklog(reminder(dailyTimes([])))).toBe(false);
  });
});

describe("planBacklogCompletion", () => {
  it("mints the instance at the moment the person acted", () => {
    expect(planBacklogCompletion(reminder(noneTimes()), ACTED_AT)).toEqual({
      reminderId: "r1",
      reminderKind: "task",
      scheduledTime: ACTED_AT,
      status: "completed",
      completedAt: ACTED_AT,
    });
  });

  it("gives the instance the moment it occupied, which is when it was done", () => {
    // ADR 0002: `scheduledTime` means "the instant this Instance occupied on the
    // timeline", and for the Backlog that is the completion time. Keeping it
    // non-null is what leaves all four time-ordered indexes valid.
    const plan = planBacklogCompletion(reminder(noneTimes()), ACTED_AT);

    expect(plan.scheduledTime).toBe(plan.completedAt);
  });

  it("carries the kind, so a backlog medication still reaches adherence", () => {
    expect(
      planBacklogCompletion(reminder(noneTimes(), "medication"), ACTED_AT)
        .reminderKind
    ).toBe("medication");
  });

  it("refuses a reminder that has a schedule", () => {
    expect(() =>
      planBacklogCompletion(reminder(dailyTimes(["09:00"])), ACTED_AT)
    ).toThrow(/backlog/i);
  });
});
