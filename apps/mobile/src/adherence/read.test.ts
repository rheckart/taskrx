import type { ReminderInstance } from "@taskrx/shared";
import {
  createReminder,
  createReminderInstance,
  dailyTimes,
} from "@taskrx/shared";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { readAdherence } from "./read";

/**
 * Executable coverage for the Adherence read — the pass that turns two tables
 * into the figure a person is shown.
 *
 * `packages/shared/src/adherence/calculator.test.ts` already runs every rule
 * about what the figure IS, and the calculator is pure;
 * `packages/shared/src/adherence/period.test.ts` runs every rule about the
 * window. What neither can reach is the thing this ticket is actually about:
 * whether the doses nobody answered have been recorded by the time the figure is
 * computed. That is the difference between "1 of 30" reading as 3% and reading
 * as 100%, and it is a property of the read order, not of either pure function.
 *
 * Both repositories are mocked, for the reason `runner.test.ts` and
 * `pull.test.ts` both give: they reach `getDb()` and through it expo-sqlite,
 * which does not load in a node test. The instances mock is a small in-memory
 * table rather than a bare stub, so the assertions below read the FIGURE that
 * came back rather than which function was called in what order —
 * `docs/specs/fused-timeline.md` forbids the latter as a subject. "A dose nobody
 * answered lowers the figure" is the rule; "the sweep was called before the list"
 * is the mechanism that happens to deliver it.
 */

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

/** Midday, so a whole-day window has room either side of it. */
const NOW = new Date(2026, 7, 13, 12, 0, 0, 0).getTime();

const mocks = vi.hoisted(() => ({
  listReminders: vi.fn(),
  listInstances: vi.fn(),
  createInstance: vi.fn(),
  markMissed: vi.fn(),
}));

vi.mock("../db/repos/reminders", () => ({
  list: mocks.listReminders,
}));

vi.mock("../db/repos/reminderInstances", () => ({
  list: mocks.listInstances,
  create: mocks.createInstance,
  markMissed: mocks.markMissed,
}));

let stored: ReminderInstance[] = [];

function instance(
  id: string,
  scheduledTime: number,
  status: ReminderInstance["status"],
  reminderKind: ReminderInstance["reminderKind"] = "medication"
): ReminderInstance {
  return createReminderInstance({
    id,
    reminderId: "r1",
    userId: "user-1",
    reminderKind,
    scheduledTime,
    status,
    createdAt: 0,
    updatedAt: 0,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  stored = [];

  mocks.listReminders.mockResolvedValue([]);
  mocks.listInstances.mockImplementation(
    (filter: { since?: number; until?: number }) =>
      Promise.resolve(
        stored.filter(
          (row) =>
            (filter.since === undefined || row.scheduledTime >= filter.since) &&
            (filter.until === undefined || row.scheduledTime <= filter.until)
        )
      )
  );
  mocks.createInstance.mockImplementation(
    (input: {
      scheduledTime: number;
      status: ReminderInstance["status"];
      reminderKind: ReminderInstance["reminderKind"];
    }) => {
      const row = instance(
        `minted-${String(stored.length)}`,
        input.scheduledTime,
        input.status,
        input.reminderKind
      );
      stored.push(row);
      return Promise.resolve(row);
    }
  );
  mocks.markMissed.mockImplementation((id: string) => {
    stored = stored.map((row) =>
      row.id === id ? { ...row, status: "missed" as const } : row
    );
    return Promise.resolve(stored.find((row) => row.id === id));
  });
});

describe("readAdherence", () => {
  it("reports the proportion of doses met over the period", async () => {
    stored = [
      instance("i1", NOW - DAY, "completed"),
      instance("i2", NOW - DAY - HOUR, "completed"),
      instance("i3", NOW - 2 * DAY, "missed"),
      instance("i4", NOW - 2 * DAY - HOUR, "skipped"),
    ];

    const { stats } = await readAdherence("user-1", NOW);

    expect(stats.completed).toBe(2);
    expect(stats.total).toBe(4);
    expect(stats.percent).toBe(50);
  });

  /**
   * The reason this ticket was blocked on #11, stated as the number a person
   * sees. One dose taken out of thirty scheduled must not read as success — and
   * before the sweep runs there are no rows for the twenty-nine, so it does.
   */
  it("counts the doses nobody ever answered, so 1-of-30 does not read as success", async () => {
    const everyMorning = createReminder({
      id: "r1",
      userId: "user-1",
      kind: "medication",
      name: "Atorvastatin",
      schedule: dailyTimes(["09:00"]),
      createdAt: NOW - 30 * DAY,
      updatedAt: NOW - 30 * DAY,
    });
    mocks.listReminders.mockResolvedValue([everyMorning]);
    // The one dose they did take, this morning.
    stored = [instance("i1", new Date(2026, 7, 13, 9).getTime(), "completed")];

    const { stats } = await readAdherence("user-1", NOW);

    expect(stats.percent).toBeLessThan(50);
    expect(stats.missed).toBeGreaterThan(0);
    expect(stats.completed).toBe(1);
  });

  it("excludes tasks, so a productive week does not move the figure", async () => {
    const doses = [
      instance("i1", NOW - DAY, "completed"),
      instance("i2", NOW - 2 * DAY, "missed"),
    ];
    stored = doses;
    const { stats: withoutTasks } = await readAdherence("user-1", NOW);

    stored = [
      ...doses,
      instance("t1", NOW - DAY - HOUR, "completed", "task"),
      instance("t2", NOW - 2 * DAY - HOUR, "completed", "task"),
      instance("t3", NOW - 3 * DAY, "completed", "task"),
    ];
    const { stats: withTasks } = await readAdherence("user-1", NOW);

    expect(withoutTasks.percent).toBe(50);
    expect(withTasks).toEqual(withoutTasks);
  });

  it("breaks the period down by day, ordered chronologically", async () => {
    stored = [
      instance("i1", NOW - DAY, "completed"),
      instance("i2", NOW - 3 * DAY, "missed"),
      instance("i3", NOW - 2 * DAY, "completed"),
    ];

    const { stats } = await readAdherence("user-1", NOW);

    // Every day of the period, in order — not only the three that have data.
    expect(stats.daily.map((day) => day.date)).toEqual([
      "2026-08-07",
      "2026-08-08",
      "2026-08-09",
      "2026-08-10",
      "2026-08-11",
      "2026-08-12",
      "2026-08-13",
    ]);
  });

  /**
   * The gap is the point. A day nobody recorded anything on used to be absent
   * from the list, which on screen was indistinguishable from a day scrolled
   * past — the breakdown simply stepped from the 10th to the 12th. ADR 0005
   * endorses "a gap reads as no data"; a gap nobody can see is not a reading.
   */
  it("shows a day with nothing recorded rather than stepping over it", async () => {
    stored = [
      instance("i1", NOW - 3 * DAY, "completed"), // the 10th
      instance("i2", NOW - DAY, "completed"), // the 12th
    ];

    const { stats } = await readAdherence("user-1", NOW);

    const eleventh = stats.daily.find((day) => day.date === "2026-08-11");

    expect(eleventh).toBeDefined();
    expect(eleventh?.total).toBe(0);
    // And it is a day with nothing due, not a day of total failure: the two
    // doses that were due were both taken, so the period is perfect.
    expect(stats.percent).toBe(100);
  });

  it("surfaces snoozed doses in their own right", async () => {
    stored = [
      instance("i1", NOW - DAY, "completed"),
      instance("i2", NOW - 2 * DAY, "snoozed"),
    ];

    const { stats } = await readAdherence("user-1", NOW);

    // Visible as a snooze rather than folded into the miss count, and still
    // counted against the figure — see the calculator's header.
    expect(stats.snoozed).toBe(1);
    expect(stats.missed).toBe(0);
    expect(stats.total).toBe(2);
    expect(stats.percent).toBe(50);
  });

  it("leaves out what happened before the period began", async () => {
    stored = [
      instance("i1", NOW - DAY, "completed"),
      instance("old", NOW - 30 * DAY, "missed"),
    ];

    const { stats } = await readAdherence("user-1", NOW);

    expect(stats.total).toBe(1);
    expect(stats.percent).toBe(100);
  });

  it("reports the period it covered, so the surface can name it", async () => {
    const { period } = await readAdherence("user-1", NOW);

    expect(period.fromMs).toBe(new Date(2026, 7, 7).getTime());
    expect(period.toMs).toBe(new Date(2026, 7, 13, 23, 59, 59, 999).getTime());
  });

  it("reports nothing rather than failing when there is no history at all", async () => {
    const { stats } = await readAdherence("user-1", NOW);

    expect(stats.total).toBe(0);
    expect(stats.percent).toBe(0);
    // Seven days, all empty — the period is still the period. The surface shows
    // its own "nothing to report yet" message for this case rather than seven
    // blank rows, which is a rendering decision and not this function's.
    expect(stats.daily).toHaveLength(7);
    expect(stats.daily.every((day) => day.total === 0)).toBe(true);
  });
});
