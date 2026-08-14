import { describe, expect, it } from "vitest";

import type { Reminder } from "../domain/reminder";
import { createReminder } from "../domain/reminder";
import type { ScheduleTimes } from "../domain/schedule-times";
import { dailyTimes, noneTimes } from "../domain/schedule-times";

import { reconcileRefills } from "./reconcile";

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

function medication(options: {
  readonly id?: string;
  readonly name?: string;
  readonly schedule?: ScheduleTimes;
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
  readonly active?: boolean;
}): Reminder {
  return createReminder({
    id: options.id ?? "med-1",
    userId: "user-1",
    createdAt: 0,
    updatedAt: 0,
    kind: "medication",
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
    ...(options.active === undefined ? {} : { active: options.active }),
  });
}

function standingRefill(id: string, name: string): Reminder {
  return createReminder({
    id,
    userId: "user-1",
    createdAt: 0,
    updatedAt: 0,
    kind: "task",
    name,
    schedule: noneTimes(),
  });
}

/** Ids in the order they are asked for, so a raise can be identified. */
function idMinter(): () => string {
  let next = 0;
  return () => {
    next += 1;
    return `minted-${String(next)}`;
  };
}

function reconcile(options: {
  readonly medications: readonly Reminder[];
  readonly standing?: readonly Reminder[];
}): ReturnType<typeof reconcileRefills> {
  return reconcileRefills({
    medications: options.medications,
    standing: options.standing ?? [],
    now: NOW,
    mintId: idMinter(),
  });
}

/** A medication whose supply runs out well inside a seven-day window. */
function runningLow(name: string, id = "med-1"): Reminder {
  return medication({
    id,
    name,
    pillsRemaining: 3,
    pillsPerDose: 1,
    refillReminderDays: 7,
  });
}

describe("reconcileRefills — raising", () => {
  it("raises a Refill Reminder for a medication running out inside its window", () => {
    const result = reconcile({ medications: [runningLow("Atorvastatin")] });

    expect(result.raise.map((row) => row.name)).toEqual([
      "Refill Atorvastatin",
    ]);
  });

  it("raises nothing while the supply outlasts the window", () => {
    const comfortable = medication({
      pillsRemaining: 40,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    expect(reconcile({ medications: [comfortable] }).raise).toEqual([]);
  });

  /**
   * The surface this feeds can be opened as many times as somebody likes. Two
   * Refill Reminders for one medication is the app nagging about a thing it
   * already said, and it compounds — a week of glances is a week of duplicates.
   */
  it("raises nothing for a medication already warned about", () => {
    const result = reconcile({
      medications: [runningLow("Atorvastatin")],
      standing: [standingRefill("task-1", "Refill Atorvastatin")],
    });

    expect(result.raise).toEqual([]);
    expect(result.retire).toEqual([]);
  });

  /**
   * Two medications entered under one name — a duplicate, most likely — must not
   * produce two identical Refill Reminders in a single pass. The name is the
   * only handle there is on a raised Reminder, so a second one is a warning
   * nothing afterwards can tell apart from the first, including the check that
   * is meant to stop a third.
   */
  it("raises one Reminder for two medications sharing a name", () => {
    const result = reconcile({
      medications: [
        runningLow("Atorvastatin", "med-1"),
        runningLow("Atorvastatin", "med-2"),
      ],
    });

    expect(result.raise).toHaveLength(1);
  });

  it("raises nothing for a task, whatever numbers it carries", () => {
    const task = createReminder({
      id: "task-1",
      userId: "user-1",
      createdAt: 0,
      updatedAt: 0,
      kind: "task",
      name: "Buy milk",
      pillsRemaining: 0,
      pillsPerDose: 1,
    });

    expect(reconcile({ medications: [task] }).raise).toEqual([]);
  });

  it("takes each raised Reminder's id from the minter, in order", () => {
    const result = reconcile({
      medications: [runningLow("Atorvastatin", "med-1")],
    });

    expect(result.raise[0]?.id).toBe("minted-1");
  });
});

describe("reconcileRefills — withdrawing", () => {
  /**
   * The acceptance criterion is that the supply can be updated after collecting
   * a prescription and the projection updates. A warning left standing after
   * that is the app telling someone to collect a prescription they are holding.
   */
  it("withdraws the warning once the supply has been topped up", () => {
    const toppedUp = medication({
      pillsRemaining: 30,
      pillsPerDose: 1,
      refillReminderDays: 7,
    });

    const result = reconcile({
      medications: [toppedUp],
      standing: [standingRefill("task-1", "Refill Atorvastatin")],
    });

    expect(result.retire.map((row) => row.id)).toEqual(["task-1"]);
  });

  /**
   * Clearing the pill count leaves the projection blind, not reassured. A
   * warning withdrawn on the strength of that is the app forgetting rather than
   * the person collecting.
   */
  it("keeps the warning when the supply stops being recorded at all", () => {
    const untracked = medication({ pillsPerDose: 1 });

    const result = reconcile({
      medications: [untracked],
      standing: [standingRefill("task-1", "Refill Atorvastatin")],
    });

    expect(result.retire).toEqual([]);
  });

  it("keeps the warning when the dose size stops being recorded", () => {
    const noDoseSize = medication({ pillsRemaining: 3 });

    const result = reconcile({
      medications: [noDoseSize],
      standing: [standingRefill("task-1", "Refill Atorvastatin")],
    });

    expect(result.retire).toEqual([]);
  });

  /**
   * Coming off a treatment is the one absence that IS reassuring: nothing is
   * being consumed, so nothing is running out, and a standing Refill Reminder
   * is an instruction to buy medicine somebody was told to stop taking.
   */
  it("withdraws the warning for a medication that has been stopped", () => {
    const stopped = medication({
      pillsRemaining: 3,
      pillsPerDose: 1,
      refillReminderDays: 7,
      active: false,
    });

    const result = reconcile({
      medications: [stopped],
      standing: [standingRefill("task-1", "Refill Atorvastatin")],
    });

    expect(result.retire.map((row) => row.id)).toEqual(["task-1"]);
    expect(result.raise).toEqual([]);
  });

  it("leaves alone a standing task that belongs to no medication here", () => {
    const result = reconcile({
      medications: [],
      standing: [standingRefill("task-1", "Refill Something Else")],
    });

    expect(result.retire).toEqual([]);
  });

  it("does not touch an ordinary errand that happens to be standing", () => {
    const result = reconcile({
      medications: [runningLow("Atorvastatin")],
      standing: [standingRefill("task-1", "Buy milk")],
    });

    expect(result.retire).toEqual([]);
    expect(result.raise).toHaveLength(1);
  });

  it("has nothing to do for someone with no medications at all", () => {
    expect(reconcile({ medications: [] })).toEqual({ raise: [], retire: [] });
  });
});
