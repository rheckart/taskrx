import { describe, expect, it } from "vitest";

import type { Reminder, ScheduleTimes } from "../domain/index";
import {
  createReminder,
  dailyTimes,
  intervalTimes,
  noneTimes,
  onceTimes,
  weeklyTimes,
} from "../domain/index";

import { selectMedicationHorizon, selectTaskHorizon } from "./horizon";

function reminder(
  id: string,
  schedule: ScheduleTimes,
  active = true
): Reminder {
  return createReminder({
    id,
    userId: "user-1",
    kind: "medication",
    name: `Reminder ${id}`,
    dosage: "10mg",
    schedule,
    active,
    createdAt: 0,
    updatedAt: 0,
  });
}

function task(id: string, schedule: ScheduleTimes, active = true): Reminder {
  return createReminder({
    id,
    userId: "user-1",
    kind: "task",
    name: `Task ${id}`,
    schedule,
    active,
    createdAt: 0,
    updatedAt: 0,
  });
}

function fireAtOf(
  occurrences: readonly { readonly fireAt: number }[],
  index: number
): number {
  return occurrences[index]?.fireAt ?? Number.NaN;
}

/**
 * All tests use UTC (vitest pins TZ=UTC in vitest.config.ts).
 * 2026-07-26T00:00:00Z = 1785196800000.
 */
const NOON_UTC = new Date("2026-07-26T12:00:00Z").getTime();

describe("selectMedicationHorizon", () => {
  it("returns an empty array when maxTotal is 0", () => {
    const result = selectMedicationHorizon(
      [reminder("r1", dailyTimes(["09:00"]))],
      NOON_UTC,
      0
    );
    expect(result).toHaveLength(0);
  });

  it("returns an empty array when maxTotal is negative", () => {
    const result = selectMedicationHorizon(
      [reminder("r1", dailyTimes(["09:00"]))],
      NOON_UTC,
      -5
    );
    expect(result).toHaveLength(0);
  });

  it("returns an empty array when there are no reminders", () => {
    expect(selectMedicationHorizon([], NOON_UTC, 64)).toHaveLength(0);
  });

  it("skips inactive reminders entirely", () => {
    const active = reminder("active", dailyTimes(["09:00"]));
    const inactive = reminder("inactive", dailyTimes(["09:30"]), false);
    const result = selectMedicationHorizon([active, inactive], NOON_UTC, 64);
    expect(result.length).toBeGreaterThan(0);
    expect(result.every((o) => o.reminderId === "active")).toBe(true);
  });

  it("caps the returned list at maxTotal even with many reminders", () => {
    const reminders = Array.from({ length: 10 }, (_, i) =>
      reminder(`r${String(i)}`, dailyTimes(["09:00"]))
    );
    const result = selectMedicationHorizon(reminders, NOON_UTC, 64);
    expect(result).toHaveLength(64);
  });

  it("returns occurrences in non-decreasing fireAt order", () => {
    const reminders = [
      reminder("a", dailyTimes(["09:00", "21:00"])),
      reminder("b", dailyTimes(["12:00", "18:00"])),
      reminder("c", weeklyTimes([0, 3], ["06:00", "15:00"])),
    ];
    const result = selectMedicationHorizon(reminders, NOON_UTC, 12);
    const times = result.map((o) => o.fireAt);
    expect(times).toEqual([...times].sort((x, y) => x - y));
  });

  it("interleaves occurrences from multiple reminders by time", () => {
    const a = reminder(
      "a",
      intervalTimes(1, new Date("2026-07-26T13:00:00Z").getTime())
    );
    const b = reminder(
      "b",
      intervalTimes(1, new Date("2026-07-26T12:30:00Z").getTime())
    );
    const result = selectMedicationHorizon([a, b], NOON_UTC, 4);
    expect(result.map((o) => o.reminderId)).toEqual(["b", "a", "b", "a"]);
  });

  it("prefers closer occurrences from one reminder over farther ones", () => {
    const a = reminder("a", dailyTimes(["21:00"]));
    const b = reminder(
      "b",
      intervalTimes(1, new Date("2026-07-26T13:00:00Z").getTime())
    );
    const result = selectMedicationHorizon([a, b], NOON_UTC, 4);
    expect(result.filter((o) => o.reminderId === "b")).toHaveLength(4);
    expect(result.filter((o) => o.reminderId === "a")).toHaveLength(0);
  });

  it("never returns an occurrence at or before fromMs", () => {
    const past = reminder("past", dailyTimes(["06:00"]));
    const result = selectMedicationHorizon([past], NOON_UTC, 4);
    expect(result).toHaveLength(4);
    for (const occurrence of result) {
      expect(occurrence.fireAt).toBeGreaterThan(NOON_UTC);
    }
  });

  it("schedules weekly reminders", () => {
    // NOON_UTC is Sunday 2026-07-26; day 3 is Wednesday.
    const weekly = reminder("weekly", weeklyTimes([3], ["08:00"]));
    const result = selectMedicationHorizon([weekly], NOON_UTC, 2);
    expect(result.map((o) => o.fireAt)).toEqual([
      new Date("2026-07-29T08:00:00Z").getTime(),
      new Date("2026-08-05T08:00:00Z").getTime(),
    ]);
    expect(result.map((o) => o.occurrenceId)).toEqual([
      `weekly:${String(fireAtOf(result, 0))}`,
      `weekly:${String(fireAtOf(result, 1))}`,
    ]);
  });

  it("interleaves a weekly reminder with a daily one by fire time", () => {
    const daily = reminder("daily", dailyTimes(["10:00"]));
    // Sunday 2026-07-26 is day 0, so this fires later today at 20:00.
    const weekly = reminder("weekly", weeklyTimes([0], ["20:00"]));
    const result = selectMedicationHorizon([daily, weekly], NOON_UTC, 3);
    expect(result.map((o) => o.reminderId)).toEqual([
      "weekly",
      "daily",
      "daily",
    ]);
  });

  it("leaves a task out of the medication Horizon entirely", () => {
    const errand = task("t-errand", dailyTimes(["13:00"]));
    expect(selectMedicationHorizon([errand], NOON_UTC, 64)).toHaveLength(0);
  });

  /**
   * ADR 0003's whole argument, as a case: a bounded Horizon shared with tasks
   * is one a dozen routine tasks can fill, leaving an evening dose unarmed.
   * The tasks here are all sooner than the dose and there is room for exactly
   * one alarm, so a Kind-blind selector arms a task and drops the dose.
   */
  it("never lets a task displace a dose from a full Horizon", () => {
    const tasks = Array.from({ length: 12 }, (_, i) =>
      task(`t-${String(i)}`, dailyTimes(["13:00"]))
    );
    const dose = reminder("insulin", dailyTimes(["21:00"]));

    const result = selectMedicationHorizon([...tasks, dose], NOON_UTC, 1);

    expect(result.map((o) => o.reminderId)).toEqual(["insulin"]);
  });

  it("breaks ties on fireAt by reminderId, independent of input order", () => {
    const alpha = reminder("alpha", dailyTimes(["15:00"]));
    const bravo = reminder("bravo", dailyTimes(["15:00"]));
    const forwards = selectMedicationHorizon([alpha, bravo], NOON_UTC, 2);
    const backwards = selectMedicationHorizon([bravo, alpha], NOON_UTC, 2);
    expect(forwards.map((o) => o.reminderId)).toEqual(["alpha", "bravo"]);
    expect(backwards.map((o) => o.reminderId)).toEqual(["alpha", "bravo"]);
    expect(fireAtOf(forwards, 0)).toEqual(fireAtOf(forwards, 1));
  });
});

describe("selectTaskHorizon", () => {
  it("returns an empty array when maxTotal is 0", () => {
    const result = selectTaskHorizon(
      [task("t1", dailyTimes(["09:00"]))],
      NOON_UTC,
      0
    );
    expect(result).toHaveLength(0);
  });

  it("returns an empty array when maxTotal is negative", () => {
    const result = selectTaskHorizon(
      [task("t1", dailyTimes(["09:00"]))],
      NOON_UTC,
      -5
    );
    expect(result).toHaveLength(0);
  });

  it("returns an empty array when there are no reminders", () => {
    expect(selectTaskHorizon([], NOON_UTC, 32)).toHaveLength(0);
  });

  it("selects a scheduled task, so that it notifies at its time", () => {
    const errand = task("pharmacy", dailyTimes(["14:00"]));
    const result = selectTaskHorizon([errand], NOON_UTC, 1);
    expect(result.map((o) => o.fireAt)).toEqual([
      new Date("2026-07-26T14:00:00Z").getTime(),
    ]);
  });

  it("selects the single moment of a once task", () => {
    const at = new Date("2026-07-31T14:00:00Z").getTime();
    const result = selectTaskHorizon(
      [task("insurance", onceTimes(at))],
      NOON_UTC,
      4
    );
    expect(result.map((o) => o.fireAt)).toEqual([at]);
  });

  it("selects nothing for a Backlog task, which has no moment to fire at", () => {
    const result = selectTaskHorizon(
      [task("someday", noneTimes())],
      NOON_UTC,
      32
    );
    expect(result).toHaveLength(0);
  });

  it("skips inactive tasks entirely", () => {
    const active = task("active", dailyTimes(["09:00"]));
    const inactive = task("inactive", dailyTimes(["09:30"]), false);
    const result = selectTaskHorizon([active, inactive], NOON_UTC, 32);
    expect(result.length).toBeGreaterThan(0);
    expect(result.every((o) => o.reminderId === "active")).toBe(true);
  });

  it("caps the returned list at maxTotal even with many tasks", () => {
    const tasks = Array.from({ length: 10 }, (_, i) =>
      task(`t${String(i)}`, dailyTimes(["09:00"]))
    );
    expect(selectTaskHorizon(tasks, NOON_UTC, 32)).toHaveLength(32);
  });

  it("returns occurrences in non-decreasing fireAt order", () => {
    const tasks = [
      task("a", dailyTimes(["09:00", "21:00"])),
      task("b", dailyTimes(["12:30", "18:00"])),
      task("c", weeklyTimes([0, 3], ["06:00", "15:00"])),
    ];
    const times = selectTaskHorizon(tasks, NOON_UTC, 12).map((o) => o.fireAt);
    expect(times).toEqual([...times].sort((x, y) => x - y));
  });

  it("breaks ties on fireAt by reminderId, independent of input order", () => {
    const alpha = task("alpha", dailyTimes(["15:00"]));
    const bravo = task("bravo", dailyTimes(["15:00"]));
    const forwards = selectTaskHorizon([alpha, bravo], NOON_UTC, 2);
    const backwards = selectTaskHorizon([bravo, alpha], NOON_UTC, 2);
    expect(forwards.map((o) => o.reminderId)).toEqual(["alpha", "bravo"]);
    expect(backwards.map((o) => o.reminderId)).toEqual(["alpha", "bravo"]);
    expect(fireAtOf(forwards, 0)).toEqual(fireAtOf(forwards, 1));
  });

  it("leaves a medication out of the task Horizon entirely", () => {
    const dose = reminder("insulin", dailyTimes(["13:00"]));
    expect(selectTaskHorizon([dose], NOON_UTC, 32)).toHaveLength(0);
  });
});

/**
 * ADR 0003's guarantee, stated as the thing a person would observe rather than
 * as the mechanism that delivers it: however many tasks exist, the doses that
 * were going to be armed are still armed.
 *
 * The medication Horizon here has room for exactly one alarm and the tasks are
 * all sooner than the dose, so any selector that ranked the two Kinds together
 * would arm a task and drop the insulin.
 */
describe("the two Horizons together", () => {
  const doses = [
    reminder("insulin", dailyTimes(["21:00"])),
    reminder("statin", dailyTimes(["22:00"])),
  ];
  const flood = Array.from({ length: 200 }, (_, i) =>
    task(`t-${String(i).padStart(3, "0")}`, dailyTimes(["13:00", "13:30"]))
  );

  it("arms every dose it would have armed, with the tasks present", () => {
    const withoutTasks = selectMedicationHorizon(doses, NOON_UTC, 64);
    const withTasks = selectMedicationHorizon(
      [...flood, ...doses],
      NOON_UTC,
      64
    );
    expect(withTasks).toEqual(withoutTasks);
  });

  it("keeps a dose armed even when one slot is all there is", () => {
    const result = selectMedicationHorizon([...flood, ...doses], NOON_UTC, 1);
    expect(result.map((o) => o.reminderId)).toEqual(["insulin"]);
  });

  it("shares no occurrence between the two Horizons", () => {
    const all = [...flood, ...doses];
    const medication = selectMedicationHorizon(all, NOON_UTC, 64);
    const tasks = selectTaskHorizon(all, NOON_UTC, 32);
    const medicationIds = new Set(medication.map((o) => o.occurrenceId));
    expect(medication.length).toBeGreaterThan(0);
    expect(tasks.length).toBeGreaterThan(0);
    expect(tasks.some((o) => medicationIds.has(o.occurrenceId))).toBe(false);
  });

  it("selects the tasks on their own budget rather than on what the doses left over", () => {
    const all = [...flood, ...doses];
    expect(selectTaskHorizon(all, NOON_UTC, 32)).toHaveLength(32);
  });
});
