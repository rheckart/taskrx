import { beforeEach, describe, expect, it, vi } from "vitest";

import { applyRemotePull } from "./pull";

/**
 * Executable coverage for the pull merge — the half of issue #7 that brings a
 * change made on one device back down onto another.
 *
 * `packages/shared/src/sync/conflict.test.ts` already runs the two decision
 * functions, and they are pure. What nothing ran was the pass that CALLS them:
 * the ordering between the two tables, and the parent-id join. That join is
 * where the bug in `eecee06` lived — an instance was written with its parent's
 * *Convex* id in `reminder_id`, a column every other producer fills with a local
 * UUID, poisoning the foreign key for every later read.
 *
 * Both repositories are mocked. They reach `getDb()` and through it expo-sqlite
 * and react-native, neither of which loads in a node test.
 *
 * Two consequences, stated because an earlier draft of this header got both
 * wrong and the repo has a history of comments that claim coverage nothing
 * provides:
 *
 *   * **The conflict guard is NOT exercised here.** It lives inside
 *     `reminderInstancesRepo.upsertMany`, which this file replaces wholesale.
 *     `shouldApplyRemoteReminderInstance` is run only by
 *     `packages/shared/src/sync/conflict.test.ts`, as a pure function. Nothing
 *     executes the two together, so "a completed Instance is never overwritten"
 *     is proven of the decision and of the pass, never of the pair.
 *   * **The `upsertMany` SQL is not exercised either.**
 *     `apps/mobile/scripts/verify-reminder-migrations.mjs` covers the
 *     migrations, not the repositories — it contains no `upsertMany`.
 *
 * `docs/specs/fused-timeline.md` asks that a test "never [assert] on ... which
 * collaborator was called, or in what order". These assertions read the rows
 * handed to a mocked repository, which is that shape. The justification is the
 * one `worker.test.ts` already makes: with the only real seam behind
 * expo-sqlite, the rows crossing this boundary are the sole observable the pass
 * has. Where a rule could be stated as an outcome instead — see "resolves
 * parents that only exist because this pass wrote them" — it is.
 */

const mocks = vi.hoisted(() => ({
  upsertReminders: vi.fn<(rows: readonly unknown[]) => Promise<number>>(),
  upsertInstances: vi.fn<(rows: readonly unknown[]) => Promise<number>>(),
  localIdForConvexId: vi.fn<(convexId: string) => Promise<string | null>>(),
  getReminder: vi.fn<(id: string) => Promise<unknown>>(),
  getInstance: vi.fn<(id: string) => Promise<unknown>>(),
}));

vi.mock("../db/repos/reminders", () => ({
  upsertMany: mocks.upsertReminders,
  localIdForConvexId: mocks.localIdForConvexId,
  get: mocks.getReminder,
}));

vi.mock("../db/repos/reminderInstances", () => ({
  upsertMany: mocks.upsertInstances,
  get: mocks.getInstance,
}));

// Imported by the module under test for the hook's subscriptions. Neither is
// reached by `applyRemotePull`, but the import graph still has to resolve.
vi.mock("@clerk/clerk-expo", () => ({
  useUser: () => ({ isSignedIn: false }),
}));
vi.mock("@react-native-community/netinfo", () => ({
  default: { fetch: vi.fn(), addEventListener: () => () => undefined },
}));
vi.mock("@taskrx/convex", () => ({
  api: { reminders: { list: "list" }, reminderInstances: { list: "list" } },
}));
vi.mock("convex/react", () => ({ useQuery: () => undefined }));
vi.mock("../db/repos/syncQueue", () => ({ pending: vi.fn() }));
vi.mock("./worker", () => ({
  syncWorker: { currentStatus: "idle", subscribe: () => () => undefined },
}));

function remoteReminder(
  overrides: Record<string, unknown> = {}
): Record<string, unknown> {
  return {
    _id: "convex-reminder-1",
    localId: "local-reminder-1",
    userId: "user-1",
    kind: "medication",
    name: "Metformin",
    schedule: { type: "daily", times: ["09:00"] },
    priority: "normal",
    tags: [],
    caregiverIds: [],
    active: true,
    createdAt: 1_700_000_000_000,
    updatedAt: 1_700_000_000_000,
    ...overrides,
  };
}

function remoteInstance(
  overrides: Record<string, unknown> = {}
): Record<string, unknown> {
  return {
    _id: "convex-instance-1",
    localId: "local-instance-1",
    userId: "user-1",
    reminderId: "convex-reminder-1",
    reminderKind: "medication",
    scheduledTime: 1_700_000_100_000,
    status: "pending",
    snoozeCount: 0,
    createdAt: 1_700_000_000_000,
    updatedAt: 1_700_000_000_000,
    ...overrides,
  };
}

/** `applyRemotePull` is typed against the Convex doc shapes; fixtures are plain. */
async function pull(
  reminders: readonly Record<string, unknown>[],
  instances: readonly Record<string, unknown>[]
): Promise<void> {
  await applyRemotePull(
    reminders as unknown as Parameters<typeof applyRemotePull>[0],
    instances as unknown as Parameters<typeof applyRemotePull>[1]
  );
}

describe("applyRemotePull", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.upsertReminders.mockResolvedValue(0);
    mocks.upsertInstances.mockResolvedValue(0);
    mocks.localIdForConvexId.mockResolvedValue("local-reminder-1");
    // Default: the device has neither row yet, so every candidate applies. The
    // conflict cases below give it one.
    mocks.getReminder.mockResolvedValue(null);
    mocks.getInstance.mockResolvedValue(null);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
  });

  it("writes a pulled reminder under its local id, not the Convex one", async () => {
    await pull([remoteReminder()], []);

    const rows = mocks.upsertReminders.mock.calls[0]?.[0] as {
      convexId: string;
      reminder: Record<string, unknown>;
    }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]?.convexId).toBe("convex-reminder-1");
    // `id` is the device UUID. Writing `_id` here would mint a second row for a
    // reminder the device already has.
    expect(rows[0]?.reminder).toMatchObject({
      id: "local-reminder-1",
      userId: "user-1",
      kind: "medication",
      name: "Metformin",
    });
  });

  it("carries rxcui through as null when the server has none", async () => {
    await pull([remoteReminder()], []);

    const rows = mocks.upsertReminders.mock.calls[0]?.[0] as {
      rxcui: string | null;
    }[];
    expect(rows[0]?.rxcui).toBeNull();

    mocks.upsertReminders.mockClear();
    await pull([remoteReminder({ rxcui: "860975" })], []);

    const withCode = mocks.upsertReminders.mock.calls[0]?.[0] as {
      rxcui: string | null;
    }[];
    expect(withCode[0]?.rxcui).toBe("860975");
  });

  it("resolves an instance's parent to the LOCAL reminder id", async () => {
    // The `eecee06` regression, pinned: `reminder_id` is a foreign key holding
    // a local UUID. `doc.reminderId` is an `Id<"reminders">` and belongs
    // nowhere near it.
    await pull([remoteReminder()], [remoteInstance()]);

    expect(mocks.localIdForConvexId).toHaveBeenCalledWith("convex-reminder-1");
    const rows = mocks.upsertInstances.mock.calls[0]?.[0] as {
      convexId: string;
      instance: Record<string, unknown>;
    }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]?.convexId).toBe("convex-instance-1");
    expect(rows[0]?.instance).toMatchObject({
      id: "local-instance-1",
      reminderId: "local-reminder-1",
      reminderKind: "medication",
      scheduledTime: 1_700_000_100_000,
      status: "pending",
    });
  });

  it("resolves parents that only exist because this pass wrote them", async () => {
    // The fresh-install race, stated as a rule rather than as a call order: on
    // a second device the local `reminders` table is EMPTY until this pass
    // fills it, so an instance can only find its parent if the parent write has
    // already landed. The stubs model exactly that — the lookup answers only
    // for reminders `upsertMany` has already seen — so a parent write that is
    // started but not awaited loses every instance, which is the bug the
    // one-hook shape exists to prevent.
    const written = new Set<string>();
    mocks.upsertReminders.mockImplementation(async (rows) => {
      await Promise.resolve();
      for (const row of rows as { readonly convexId: string }[]) {
        written.add(row.convexId);
      }
      return written.size;
    });
    mocks.localIdForConvexId.mockImplementation((convexId: string) =>
      Promise.resolve(written.has(convexId) ? "local-reminder-1" : null)
    );

    await pull([remoteReminder()], [remoteInstance()]);

    const rows = mocks.upsertInstances.mock.calls[0]?.[0] as {
      instance: Record<string, unknown>;
    }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]?.instance).toMatchObject({
      reminderId: "local-reminder-1",
    });
  });

  it("skips an instance whose parent did not come down", async () => {
    // Reachable however long you wait: a soft-deleted parent is filtered out of
    // `api.reminders.list`, so it is never pulled at all.
    mocks.localIdForConvexId.mockResolvedValue(null);

    await pull([], [remoteInstance()]);

    expect(mocks.upsertInstances).toHaveBeenCalledWith([]);
  });

  it("keeps the instances whose parents did resolve", async () => {
    // A skip must cost one row, not the batch.
    mocks.localIdForConvexId.mockImplementation((convexId: string) =>
      Promise.resolve(
        convexId === "convex-reminder-1" ? "local-reminder-1" : null
      )
    );

    await pull(
      [remoteReminder()],
      [
        remoteInstance(),
        remoteInstance({
          _id: "convex-instance-2",
          localId: "local-instance-2",
          reminderId: "convex-reminder-orphan",
        }),
      ]
    );

    const rows = mocks.upsertInstances.mock.calls[0]?.[0] as {
      convexId: string;
    }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]?.convexId).toBe("convex-instance-1");
  });

  it("passes the optional instance fields through when the server sets them", async () => {
    await pull(
      [remoteReminder()],
      [
        remoteInstance({
          status: "completed",
          completedAt: 1_700_000_200_000,
          notes: "with food",
          snoozeCount: 2,
        }),
      ]
    );

    const rows = mocks.upsertInstances.mock.calls[0]?.[0] as {
      instance: Record<string, unknown>;
    }[];
    expect(rows[0]?.instance).toMatchObject({
      status: "completed",
      completedAt: 1_700_000_200_000,
      notes: "with food",
      snoozeCount: 2,
    });
  });

  it("still writes the instance batch when nothing came down", async () => {
    await pull([], []);

    expect(mocks.upsertReminders).toHaveBeenCalledWith([]);
    expect(mocks.upsertInstances).toHaveBeenCalledWith([]);
  });
});

/**
 * The conflict rules, executed by the pass that applies them rather than only as
 * pure functions.
 *
 * `packages/shared/src/sync/conflict.test.ts` runs the two decisions against a
 * truth table and is the better place for boundary cases; it does not run them
 * against a pull. Until issue #7's last criterion these lived inside the two
 * `upsertMany` implementations, behind `getDb()` and so behind expo-sqlite,
 * which no node test loads — the rule was proven of the decision and of the
 * pass, never of the pair. These cases are that pair.
 *
 * Each asserts on what reaches the repository, which is the only observable the
 * pass has, and states the rule as an outcome rather than as a call order.
 */
describe("applyRemotePull conflict rules", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.upsertReminders.mockResolvedValue(0);
    mocks.upsertInstances.mockResolvedValue(0);
    mocks.localIdForConvexId.mockResolvedValue("local-reminder-1");
    mocks.getReminder.mockResolvedValue(null);
    mocks.getInstance.mockResolvedValue(null);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
  });

  /**
   * The safety-critical one — story 44 of `docs/specs/fused-timeline.md`, and
   * the criterion issue #7 left unticked. A dose the person recorded as taken on
   * this device is not erased by a copy arriving from another one, however much
   * newer that copy is. `updatedAt` here is an hour ahead precisely so the rule
   * cannot be mistaken for last-write-wins doing the work.
   */
  it("never lets a remote row overwrite a locally completed instance", async () => {
    // Keyed by id rather than answered for anything, so this case alone also
    // fails if the guard looks the local row up by the wrong identifier.
    mocks.getInstance.mockImplementation((id: string) =>
      Promise.resolve(
        id === "local-instance-1"
          ? { updatedAt: 1_700_000_000_000, status: "completed" }
          : null
      )
    );

    await pull(
      [remoteReminder()],
      [
        remoteInstance({
          status: "missed",
          updatedAt: 1_700_003_600_000,
        }),
      ]
    );

    expect(mocks.upsertInstances).toHaveBeenCalledWith([]);
  });

  it("applies a remote answer to an instance still pending here", async () => {
    mocks.getInstance.mockResolvedValue({
      updatedAt: 1_700_000_000_000,
      status: "pending",
    });

    await pull(
      [remoteReminder()],
      [remoteInstance({ status: "completed", updatedAt: 1_700_003_600_000 })]
    );

    const rows = mocks.upsertInstances.mock.calls[0]?.[0] as {
      instance: Record<string, unknown>;
    }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]?.instance).toMatchObject({ status: "completed" });
  });

  /**
   * A snooze is a deferral, not an answer, so it earns no protection — the
   * asymmetry with `completed` above is the whole reason the instance rule is a
   * separate function from the reminder one.
   */
  it("lets a newer remote answer replace a local snooze", async () => {
    mocks.getInstance.mockResolvedValue({
      updatedAt: 1_700_000_000_000,
      status: "snoozed",
    });

    await pull(
      [remoteReminder()],
      [remoteInstance({ status: "completed", updatedAt: 1_700_003_600_000 })]
    );

    expect(mocks.upsertInstances.mock.calls[0]?.[0]).toHaveLength(1);
  });

  it("keeps a local reminder edit that is newer than the server's", async () => {
    mocks.getReminder.mockResolvedValue({ updatedAt: 1_700_003_600_000 });

    await pull([remoteReminder({ updatedAt: 1_700_000_000_000 })], []);

    expect(mocks.upsertReminders).toHaveBeenCalledWith([]);
  });

  /** Local wins ties, so an equal timestamp is a skip rather than a rewrite. */
  it("leaves a reminder alone when the timestamps are equal", async () => {
    mocks.getReminder.mockResolvedValue({ updatedAt: 1_700_000_000_000 });

    await pull([remoteReminder({ updatedAt: 1_700_000_000_000 })], []);

    expect(mocks.upsertReminders).toHaveBeenCalledWith([]);
  });

  it("applies a reminder the server has edited more recently", async () => {
    mocks.getReminder.mockResolvedValue({ updatedAt: 1_700_000_000_000 });

    await pull(
      [remoteReminder({ name: "Metformin XR", updatedAt: 1_700_003_600_000 })],
      []
    );

    const rows = mocks.upsertReminders.mock.calls[0]?.[0] as {
      reminder: Record<string, unknown>;
    }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]?.reminder).toMatchObject({ name: "Metformin XR" });
  });

  /**
   * A refused row must cost one row, not the batch — the same property the
   * parent-missing skip carries, asserted of this skip too because it is a
   * different branch.
   */
  it("refuses only the conflicting instance, not the batch", async () => {
    mocks.getInstance.mockImplementation((id: string) =>
      Promise.resolve(
        id === "local-instance-1"
          ? { updatedAt: 1_700_000_000_000, status: "completed" }
          : null
      )
    );

    await pull(
      [remoteReminder()],
      [
        remoteInstance({ status: "missed", updatedAt: 1_700_003_600_000 }),
        remoteInstance({
          _id: "convex-instance-2",
          localId: "local-instance-2",
          status: "completed",
        }),
      ]
    );

    const rows = mocks.upsertInstances.mock.calls[0]?.[0] as {
      convexId: string;
    }[];
    expect(rows).toHaveLength(1);
    expect(rows[0]?.convexId).toBe("convex-instance-2");
  });

  /**
   * The guard reads the row it is about to overwrite, not some other one. An
   * implementation that looked the local copy up by the Convex id — the same
   * confusion `eecee06` made on the foreign key — would find nothing, decide
   * every remote row applies, and silently lose the protection above.
   */
  it("compares against the local row under the device UUID", async () => {
    await pull([remoteReminder()], [remoteInstance()]);

    expect(mocks.getInstance).toHaveBeenCalledWith("local-instance-1");
    expect(mocks.getReminder).toHaveBeenCalledWith("local-reminder-1");
  });
});
