import type { Reminder, ScheduleTimes } from "@taskrx/shared";
import { createReminder, dailyTimes, noneTimes } from "@taskrx/shared";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { readRefills } from "./read";

/**
 * Executable coverage for the Refill read — the pass that turns a pill count
 * into a warning somebody meets away from this screen.
 *
 * `packages/shared/src/refill/projection.test.ts` already runs every rule about
 * how long a supply lasts, and `planner.test.ts` every rule about what gets
 * raised. What neither can reach is what this ticket is actually about: that the
 * Backlog ends up agreeing with the projection, once, and that collecting a
 * prescription puts it back.
 *
 * The repository is mocked, for the reason `runner.test.ts` and `read.test.ts`
 * next door both give: it reaches `getDb()` and through it expo-sqlite, which
 * does not load in a node test. The mock is a small in-memory table rather than
 * a bare stub, so the assertions below read the reminders that EXIST afterwards
 * rather than which function was called in what order —
 * `docs/specs/fused-timeline.md` forbids the latter as a subject.
 */

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

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  softDelete: vi.fn(),
}));

vi.mock("../db/repos/reminders", () => ({
  list: mocks.list,
  create: mocks.create,
  softDelete: mocks.softDelete,
}));

vi.mock("../db/repos/ids", () => ({
  generateId: () => "generated-id",
}));

let stored: Reminder[] = [];

function medication(options: {
  readonly id?: string;
  readonly name?: string;
  readonly schedule?: ScheduleTimes;
  readonly pillsRemaining?: number;
  readonly pillsPerDose?: number;
  readonly refillReminderDays?: number;
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
  });
}

function task(id: string, name: string): Reminder {
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

/** The tasks standing in the Backlog after the read, by name. */
function backlogNames(): readonly string[] {
  return stored
    .filter((row) => row.kind === "task" && row.active)
    .map((row) => row.name);
}

beforeEach(() => {
  vi.clearAllMocks();
  stored = [];

  mocks.list.mockImplementation(
    (
      userId: string,
      kind?: string,
      options: { readonly includeInactive?: boolean } = {}
    ) =>
      Promise.resolve(
        stored.filter(
          (row) =>
            row.userId === userId &&
            (options.includeInactive === true || row.active) &&
            (kind === undefined || row.kind === kind)
        )
      )
  );

  mocks.create.mockImplementation(
    (input: {
      readonly id?: string;
      readonly userId: string;
      readonly kind: Reminder["kind"];
      readonly name: string;
      readonly schedule: ScheduleTimes;
    }) => {
      const row = createReminder({
        ...input,
        id: input.id ?? "minted-by-the-repo",
        createdAt: NOW,
        updatedAt: NOW,
      });
      stored.push(row);
      return Promise.resolve(row);
    }
  );

  mocks.softDelete.mockImplementation((id: string) => {
    stored = stored.map((row) =>
      row.id === id ? { ...row, active: false } : row
    );
    return Promise.resolve();
  });
});

describe("readRefills", () => {
  it("reports how long each medication has left", async () => {
    stored = [medication({ pillsRemaining: 12, pillsPerDose: 1 })];

    const reading = await readRefills("user-1", NOW);

    const [row] = reading.rows;
    expect(row?.projection).toMatchObject({
      projected: true,
      occurrencesRemaining: 12,
      daysRemaining: 12,
    });
  });

  it("says nothing about the supply of a medication that has none recorded", async () => {
    stored = [medication({ pillsPerDose: 1 })];

    const reading = await readRefills("user-1", NOW);

    // Not a zero, and not a run-out date: an unrecorded supply is the absence of
    // a claim, and rendering it as an empty bottle would be inventing one.
    expect(reading.rows[0]?.projection).toEqual({
      projected: false,
      reason: "no-supply",
    });
  });

  it("puts the medication closest to running out first", async () => {
    stored = [
      medication({
        id: "m1",
        name: "Plenty",
        pillsRemaining: 40,
        pillsPerDose: 1,
      }),
      medication({
        id: "m2",
        name: "Soon",
        pillsRemaining: 2,
        pillsPerDose: 1,
      }),
      medication({ id: "m3", name: "Untracked" }),
      medication({
        id: "m4",
        name: "Middling",
        pillsRemaining: 9,
        pillsPerDose: 1,
      }),
    ];

    const reading = await readRefills("user-1", NOW);

    expect(reading.rows.map((row) => row.medication.name)).toEqual([
      "Soon",
      "Middling",
      "Plenty",
      // Last, because "nothing recorded" is not urgent and a missing number is
      // not a zero.
      "Untracked",
    ]);
  });

  it("raises a Refill Reminder into the Backlog before the supply runs out", async () => {
    stored = [
      medication({
        name: "Atorvastatin",
        pillsRemaining: 3,
        pillsPerDose: 1,
        refillReminderDays: 7,
      }),
    ];

    const reading = await readRefills("user-1", NOW);

    expect(reading.raised).toEqual(["Refill Atorvastatin"]);
    expect(backlogNames()).toEqual(["Refill Atorvastatin"]);
  });

  it("leaves the Backlog alone while the supply is comfortable", async () => {
    stored = [
      medication({
        pillsRemaining: 60,
        pillsPerDose: 1,
        refillReminderDays: 7,
      }),
    ];

    await readRefills("user-1", NOW);

    expect(backlogNames()).toEqual([]);
  });

  /**
   * The screen can be opened as many times as somebody likes. Two Refill
   * Reminders for one medication is the app nagging about a thing it already
   * said, and it compounds — a week of glances is a week of duplicates.
   */
  it("does not raise a second Reminder for a medication already warned about", async () => {
    stored = [
      medication({
        name: "Atorvastatin",
        pillsRemaining: 3,
        pillsPerDose: 1,
        refillReminderDays: 7,
      }),
    ];

    await readRefills("user-1", NOW);
    const second = await readRefills("user-1", NOW);

    expect(second.raised).toEqual([]);
    expect(backlogNames()).toEqual(["Refill Atorvastatin"]);
  });

  /**
   * The acceptance criterion is that the supply can be updated after collecting
   * a prescription and the projection updates. A warning left standing after
   * that is the app telling someone to collect a prescription they are holding.
   */
  it("retires the Reminder once the supply has been topped up", async () => {
    stored = [
      medication({
        id: "med-1",
        name: "Atorvastatin",
        pillsRemaining: 3,
        pillsPerDose: 1,
        refillReminderDays: 7,
      }),
    ];
    await readRefills("user-1", NOW);

    stored = stored.map((row) =>
      row.id === "med-1" ? { ...row, pillsRemaining: 30 } : row
    );
    const afterCollecting = await readRefills("user-1", NOW);

    expect(afterCollecting.retired).toEqual(["Refill Atorvastatin"]);
    expect(backlogNames()).toEqual([]);
    expect(afterCollecting.rows[0]?.projection).toMatchObject({
      daysRemaining: 30,
      needsRefill: false,
    });
  });

  it("raises again when a topped-up medication runs low a second time", async () => {
    stored = [
      medication({
        id: "med-1",
        name: "Atorvastatin",
        pillsRemaining: 3,
        pillsPerDose: 1,
        refillReminderDays: 7,
      }),
    ];
    await readRefills("user-1", NOW);

    stored = stored.map((row) =>
      row.id === "med-1" ? { ...row, pillsRemaining: 30 } : row
    );
    await readRefills("user-1", NOW);

    stored = stored.map((row) =>
      row.id === "med-1" ? { ...row, pillsRemaining: 2 } : row
    );
    const runningLowAgain = await readRefills("user-1", NOW);

    // Only true because a withdrawn warning is not read back as a standing one.
    expect(runningLowAgain.raised).toEqual(["Refill Atorvastatin"]);
  });

  /**
   * Stopping a treatment has to reach the Backlog, and it is the one case that
   * needs this layer rather than `reconcileRefills`: the withdrawal is decided
   * from a medication the screen must not list, so the read has to fetch rows it
   * then filters out. Fetching only the active ones would leave the warning
   * standing forever, with nothing on any surface to explain it.
   */
  it("withdraws the warning for a stopped medication without listing it", async () => {
    stored = [
      medication({
        id: "med-1",
        name: "Atorvastatin",
        pillsRemaining: 3,
        pillsPerDose: 1,
        refillReminderDays: 7,
      }),
    ];
    await readRefills("user-1", NOW);
    expect(backlogNames()).toEqual(["Refill Atorvastatin"]);

    stored = stored.map((row) =>
      row.id === "med-1" ? { ...row, active: false } : row
    );
    const afterStopping = await readRefills("user-1", NOW);

    expect(afterStopping.retired).toEqual(["Refill Atorvastatin"]);
    expect(backlogNames()).toEqual([]);
    expect(afterStopping.rows).toEqual([]);
  });

  /**
   * A task has no supply, so it can neither appear here nor have a Refill
   * Reminder raised about it — including one that would be named after a task
   * somebody happened to call "Refill Atorvastatin" themselves.
   */
  it("has nothing to say about tasks", async () => {
    stored = [task("t1", "Buy milk"), task("t2", "Call the surgery")];

    const reading = await readRefills("user-1", NOW);

    expect(reading.rows).toEqual([]);
    expect(reading.raised).toEqual([]);
    expect(reading.retired).toEqual([]);
  });

  it("writes the Reminder the planner decided on, id and all", async () => {
    stored = [
      medication({ pillsRemaining: 0, pillsPerDose: 1, refillReminderDays: 7 }),
    ];

    await readRefills("user-1", NOW);

    const raised = stored.find((row) => row.kind === "task");
    expect(raised?.id).toBe("generated-id");
    expect(raised?.schedule).toEqual(noneTimes());
    expect(raised?.priority).toBe("high");
  });

  it("reports nothing rather than failing for someone with no medications", async () => {
    const reading = await readRefills("user-1", NOW);

    expect(reading).toEqual({ rows: [], raised: [], retired: [] });
  });
});
