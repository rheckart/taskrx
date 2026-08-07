import { describe, expect, it } from "vitest";

import {
  ANSWERABLE_STATUSES,
  INSTANCE_STATUSES,
  NOTIFICATION_TYPES,
  PRIORITIES,
  REMINDER_KINDS,
  isAnswerable,
} from "./enums";
import {
  createCaregiver,
  createReminder,
  createReminderInstance,
} from "./reminder";
import { dailyTimes, intervalTimes, weeklyTimes } from "./schedule-times";

const CREATED_AT = Date.UTC(2026, 6, 27, 12, 0, 0);

describe("enums", () => {
  it("carries every member of the C# enums", () => {
    expect(REMINDER_KINDS).toEqual(["medication", "task"]);
    expect(PRIORITIES).toEqual(["critical", "high", "normal", "low"]);
    expect(INSTANCE_STATUSES).toEqual([
      "pending",
      "completed",
      "snoozed",
      "skipped",
      "missed",
    ]);
    // `taken`, not `completed`. ADR 0001 renamed the member on
    // `InstanceStatus`, which both Kinds share; `NotificationType` is a
    // separate enum recording what happened to a notification, and the rename
    // deliberately did not reach it.
    expect(NOTIFICATION_TYPES).toEqual([
      "fired",
      "taken",
      "snoozed",
      "skipped",
      "missed",
      "caregiver_pinged",
    ]);
  });

  it("keeps snoozed distinct from skipped, which adherence depends on", () => {
    expect(INSTANCE_STATUSES).toContain("snoozed");
    expect(INSTANCE_STATUSES).toContain("skipped");
  });
});

describe("isAnswerable", () => {
  it("leaves an unanswered moment open", () => {
    expect(isAnswerable("pending")).toBe(true);
  });

  /**
   * The rule that earns this function. A snooze says "not now", so the moment
   * is still outstanding — treating it as answered would make snoozing the one
   * way to put something permanently out of reach.
   */
  it("leaves a snoozed moment open, because a deferral is not an answer", () => {
    expect(isAnswerable("snoozed")).toBe(true);
  });

  it("closes a moment the person has answered", () => {
    expect(isAnswerable("completed")).toBe(false);
    expect(isAnswerable("skipped")).toBe(false);
  });

  it("closes a moment that ran out of time", () => {
    expect(isAnswerable("missed")).toBe(false);
  });

  it("has an opinion about every status there is", () => {
    // Adding a status without deciding whether it is answerable would leave the
    // on-device lookup and the timeline's tappability to guess separately.
    for (const status of INSTANCE_STATUSES) {
      expect(typeof isAnswerable(status)).toBe("boolean");
    }
    expect(
      ANSWERABLE_STATUSES.every((s) => INSTANCE_STATUSES.includes(s))
    ).toBe(true);
  });
});

describe("createReminder", () => {
  it("applies the defaults BuildReminder uses", () => {
    expect(
      createReminder({
        id: "rem-1",
        userId: "user-1",
        kind: "medication",
        name: "Metformin",
        createdAt: CREATED_AT,
        updatedAt: CREATED_AT,
      })
    ).toEqual({
      id: "rem-1",
      userId: "user-1",
      kind: "medication",
      name: "Metformin",
      dosage: undefined,
      instructions: undefined,
      schedule: dailyTimes([]),
      priority: "normal",
      tags: [],
      pillsRemaining: undefined,
      pillsPerDose: undefined,
      refillReminderDays: undefined,
      caregiverIds: [],
      active: true,
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT,
    });
  });

  it("carries a task kind, which the POC's medication-only types cannot", () => {
    const reminder = createReminder({
      id: "rem-2",
      userId: "user-1",
      kind: "task",
      name: "Check blood pressure",
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT,
    });

    expect(reminder.kind).toBe("task");
  });

  it("carries a weekly schedule, which the POC's types cannot express", () => {
    const reminder = createReminder({
      id: "rem-3",
      userId: "user-1",
      kind: "medication",
      name: "Methotrexate",
      schedule: weeklyTimes([1], ["09:00"]),
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT,
    });

    expect(reminder.schedule).toEqual({
      type: "weekly",
      daysOfWeek: [1],
      times: ["09:00"],
    });
  });

  it("keeps every supplied field", () => {
    const reminder = createReminder({
      id: "rem-4",
      userId: "user-1",
      kind: "medication",
      name: "Warfarin",
      dosage: "5 mg",
      instructions: "with food",
      schedule: intervalTimes(8, CREATED_AT),
      priority: "critical",
      tags: ["blood-thinner"],
      pillsRemaining: 30,
      pillsPerDose: 1.5,
      refillReminderDays: 7,
      caregiverIds: ["care-1"],
      active: false,
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT + 1000,
    });

    expect(reminder).toEqual({
      id: "rem-4",
      userId: "user-1",
      kind: "medication",
      name: "Warfarin",
      dosage: "5 mg",
      instructions: "with food",
      schedule: { type: "interval", hours: 8, startAt: CREATED_AT },
      priority: "critical",
      tags: ["blood-thinner"],
      pillsRemaining: 30,
      pillsPerDose: 1.5,
      refillReminderDays: 7,
      caregiverIds: ["care-1"],
      active: false,
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT + 1000,
    });
  });

  it("does not alias the caller's arrays", () => {
    const tags = ["a"];
    const caregiverIds = ["care-1"];
    const reminder = createReminder({
      id: "rem-5",
      userId: "user-1",
      kind: "task",
      name: "Walk",
      tags,
      caregiverIds,
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT,
    });

    tags.push("b");
    caregiverIds.push("care-2");

    expect(reminder.tags).toEqual(["a"]);
    expect(reminder.caregiverIds).toEqual(["care-1"]);
  });
});

describe("createReminderInstance", () => {
  it("defaults to a pending, never-snoozed instance", () => {
    expect(
      createReminderInstance({
        id: "inst-1",
        reminderId: "rem-1",
        userId: "user-1",
        reminderKind: "medication",
        scheduledTime: CREATED_AT,
        createdAt: CREATED_AT,
        updatedAt: CREATED_AT,
      })
    ).toEqual({
      id: "inst-1",
      reminderId: "rem-1",
      userId: "user-1",
      reminderKind: "medication",
      scheduledTime: CREATED_AT,
      status: "pending",
      completedAt: undefined,
      snoozedUntil: undefined,
      snoozeCount: 0,
      notes: undefined,
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT,
    });
  });

  it("keeps every supplied field", () => {
    const instance = createReminderInstance({
      id: "inst-2",
      reminderId: "rem-1",
      userId: "user-1",
      reminderKind: "task",
      scheduledTime: CREATED_AT,
      status: "snoozed",
      completedAt: CREATED_AT + 60_000,
      snoozedUntil: CREATED_AT + 600_000,
      snoozeCount: 2,
      notes: "after lunch",
      createdAt: CREATED_AT,
      updatedAt: CREATED_AT + 1000,
    });

    expect(instance.status).toBe("snoozed");
    expect(instance.completedAt).toBe(CREATED_AT + 60_000);
    expect(instance.snoozedUntil).toBe(CREATED_AT + 600_000);
    expect(instance.snoozeCount).toBe(2);
    expect(instance.notes).toBe("after lunch");
  });
});

describe("createCaregiver", () => {
  it("defaults notification opt-ins to off and collections to empty", () => {
    expect(
      createCaregiver({
        id: "care-1",
        userId: "user-1",
        caregiverUserId: "user-2",
        createdAt: CREATED_AT,
      })
    ).toEqual({
      id: "care-1",
      userId: "user-1",
      caregiverUserId: "user-2",
      relationship: undefined,
      notifyForMissedMedication: false,
      notifyForMissedTask: false,
      channels: [],
      deviceTokens: [],
      createdAt: CREATED_AT,
    });
  });

  it("keeps every supplied field", () => {
    const caregiver = createCaregiver({
      id: "care-2",
      userId: "user-1",
      caregiverUserId: "user-3",
      relationship: "daughter",
      notifyForMissedMedication: true,
      notifyForMissedTask: true,
      channels: ["push"],
      deviceTokens: ["token-1"],
      createdAt: CREATED_AT,
    });

    expect(caregiver.relationship).toBe("daughter");
    expect(caregiver.notifyForMissedMedication).toBe(true);
    expect(caregiver.notifyForMissedTask).toBe(true);
    expect(caregiver.channels).toEqual(["push"]);
    expect(caregiver.deviceTokens).toEqual(["token-1"]);
  });
});
