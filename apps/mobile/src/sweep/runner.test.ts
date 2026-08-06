import type { Reminder, ReminderInstance } from "@taskrx/shared";
import {
  createReminder,
  createReminderInstance,
  onceTimes,
} from "@taskrx/shared";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { runMissedSweep } from "./runner";

/**
 * Executable coverage for the sweep's persistence pass — the half of issue #11
 * that turns a verdict into a row.
 *
 * `packages/shared/src/sweep/planner.test.ts` already runs every rule about what
 * is Missed, and the planner is pure. What nothing ran was the pass that CALLS
 * it: which of the two writes a verdict becomes, and whether two foregrounds
 * arriving together can mint the same moment twice.
 *
 * Both repositories are mocked, for the reason `pull.test.ts` gives: they reach
 * `getDb()` and through it expo-sqlite, which does not load in a node test. The
 * instances mock is a small in-memory table rather than a bare stub, so the
 * assertions read *what was stored* rather than which function was called with
 * what — `docs/specs/fused-timeline.md` forbids the latter as a subject, and the
 * concurrency rule in particular is only meaningful as an outcome: a second
 * sweep sees the first one's rows.
 */

const NOW = Date.UTC(2026, 7, 6, 12, 0, 0);
const HOUR = 3_600_000;

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

/** The rows the mocked instances table holds, as the repo would return them. */
let stored: ReminderInstance[] = [];

function instance(
  id: string,
  reminderId: string,
  scheduledTime: number,
  status: ReminderInstance["status"]
): ReminderInstance {
  return createReminderInstance({
    id,
    reminderId,
    userId: "user-1",
    reminderKind: "medication",
    scheduledTime,
    status,
    createdAt: 0,
    updatedAt: 0,
  });
}

function dueAt(id: string, at: number): Reminder {
  return createReminder({
    id,
    userId: "user-1",
    kind: "medication",
    name: `Reminder ${id}`,
    schedule: onceTimes(at),
    createdAt: 0,
    updatedAt: 0,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  stored = [];

  mocks.listReminders.mockResolvedValue([]);
  mocks.listInstances.mockImplementation(() => Promise.resolve([...stored]));
  // Stores the status it was handed, verbatim. Defaulting an omitted one here
  // would re-implement the repository's own default, and then the fake would be
  // asserting its own behaviour rather than the runner's.
  mocks.createInstance.mockImplementation(
    (input: {
      reminderId: string;
      scheduledTime: number;
      status: ReminderInstance["status"];
    }) => {
      const row = instance(
        `minted-${String(stored.length)}`,
        input.reminderId,
        input.scheduledTime,
        input.status
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

describe("runMissedSweep", () => {
  it("records a moment nobody answered, which had no row at all until now", async () => {
    mocks.listReminders.mockResolvedValue([dueAt("r1", NOW - 3 * HOUR)]);

    const swept = await runMissedSweep("user-1", NOW);

    expect(swept).toBe(1);
    expect(stored).toEqual([
      expect.objectContaining({
        reminderId: "r1",
        scheduledTime: NOW - 3 * HOUR,
        status: "missed",
      }),
    ]);
  });

  it("settles the row that already exists rather than opening a second one", async () => {
    mocks.listReminders.mockResolvedValue([dueAt("r1", NOW - 3 * HOUR)]);
    stored = [instance("i1", "r1", NOW - 3 * HOUR, "pending")];

    await runMissedSweep("user-1", NOW);

    expect(stored).toHaveLength(1);
    expect(stored[0]).toMatchObject({ id: "i1", status: "missed" });
  });

  it("writes nothing when every moment is answered or still answerable", async () => {
    mocks.listReminders.mockResolvedValue([dueAt("r1", NOW - HOUR)]);

    expect(await runMissedSweep("user-1", NOW)).toBe(0);
    expect(stored).toEqual([]);
  });

  it("does not record the same moment twice when two sweeps overlap", async () => {
    mocks.listReminders.mockResolvedValue([dueAt("r1", NOW - 3 * HOUR)]);

    const [first, second] = await Promise.all([
      runMissedSweep("user-1", NOW),
      runMissedSweep("user-1", NOW),
    ]);

    expect(stored).toHaveLength(1);
    expect(first + second).toBe(1);
  });

  it("keeps sweeping after one has failed", async () => {
    mocks.listReminders.mockRejectedValueOnce(new Error("database is locked"));

    await expect(runMissedSweep("user-1", NOW)).rejects.toThrow(/locked/);

    mocks.listReminders.mockResolvedValue([dueAt("r1", NOW - 3 * HOUR)]);
    expect(await runMissedSweep("user-1", NOW)).toBe(1);
  });
});
