import type { ConvexReactClient } from "convex/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { SyncQueueEntry } from "./types";
import { SyncWorker } from "./worker";

/**
 * Executable coverage for `SyncWorker.drain()`.
 *
 * `scripts/verify-reminder-migrations.mjs` asserts the same invariant by
 * reading the source: the re-entrancy flag must be released on every path out
 * of `drain()`. That check has now been evaded four separate ways -- a decoy
 * `try`, a `throw` before the guarded block, a nested releasing `finally`, and
 * a flag nobody reads -- because a scanner can only recognise the shapes
 * someone thought to describe. This file runs the function instead.
 *
 * The invariant, stated once: after ANY drain, a subsequent drain must reach
 * the queue. A drain that leaves the flag set is invisible in its own call --
 * it returns normally, sets a plausible status, and only the *next* drain
 * silently does nothing. So every case below drains twice and asserts on the
 * second.
 *
 * Everything the worker touches is mocked. The subject is control flow, not
 * the Convex mutations, and mocking the queue is what makes "did the second
 * drain get this far?" observable at all.
 */

const mocks = vi.hoisted(() => ({
  netInfoFetch: vi.fn<() => Promise<{ isConnected: boolean }>>(),
  pending: vi.fn<() => Promise<readonly SyncQueueEntry[]>>(),
  reconcileReminders: vi.fn<() => Promise<void>>(),
  reconcileInstances: vi.fn<() => Promise<void>>(),
  markFailed: vi.fn<(id: number, error: string) => Promise<void>>(),
  markDeadLettered: vi.fn<(id: number) => Promise<void>>(),
  remove: vi.fn<(id: number) => Promise<void>>(),
  setReminderConvexId: vi.fn<(localId: string, id: string) => Promise<void>>(),
  getReminderConvexId: vi.fn<(localId: string) => Promise<string | null>>(),
  markReminderSynced: vi.fn<(id: string) => Promise<void>>(),
  markReminderError: vi.fn<(id: string, error: string) => Promise<void>>(),
  setInstanceConvexId: vi.fn<(localId: string, id: string) => Promise<void>>(),
  getInstanceConvexId: vi.fn<(localId: string) => Promise<string | null>>(),
  markInstanceSynced: vi.fn<(id: string) => Promise<void>>(),
  markInstanceError: vi.fn<(id: string, error: string) => Promise<void>>(),
}));

vi.mock("@react-native-community/netinfo", () => ({
  default: {
    fetch: mocks.netInfoFetch,
    addEventListener: () => () => undefined,
  },
}));

vi.mock("../api/convex", () => ({
  convex: {},
  isConvexConfigured: true,
}));

// Distinct sentinels rather than `{}`: the push tests assert WHICH unified
// mutation an entry reached, and a bare object hands every branch the same
// `undefined` to be called with — so a create routed to `update` would pass.
// Strings rather than symbols so a failure message names the branch that ran.
vi.mock("@taskrx/convex", () => ({
  api: {
    reminders: {
      create: "api.reminders.create",
      update: "api.reminders.update",
      softDelete: "api.reminders.softDelete",
      getByLocalId: "api.reminders.getByLocalId",
    },
    reminderInstances: {
      create: "api.reminderInstances.create",
      markCompleted: "api.reminderInstances.markCompleted",
      snooze: "api.reminderInstances.snooze",
      markSkipped: "api.reminderInstances.markSkipped",
      markMissed: "api.reminderInstances.markMissed",
      getByLocalId: "api.reminderInstances.getByLocalId",
    },
  },
}));

vi.mock("../db/repos/reminders", () => ({
  reconcilePending: mocks.reconcileReminders,
  setConvexId: mocks.setReminderConvexId,
  getConvexId: mocks.getReminderConvexId,
  markSynced: mocks.markReminderSynced,
  markError: mocks.markReminderError,
}));

vi.mock("../db/repos/reminderInstances", () => ({
  reconcilePending: mocks.reconcileInstances,
  setConvexId: mocks.setInstanceConvexId,
  getConvexId: mocks.getInstanceConvexId,
  markSynced: mocks.markInstanceSynced,
  markError: mocks.markInstanceError,
}));

vi.mock("../db/repos/syncQueue", () => ({
  pending: mocks.pending,
  markFailed: mocks.markFailed,
  markDeadLettered: mocks.markDeadLettered,
  remove: mocks.remove,
}));

// The legacy repos and the two mirror modules used to be mocked here as well —
// they reached `getDb()` and pulled expo-sqlite, and through it react-native,
// into a node test's module graph. All four were deleted with the legacy
// generation, and the drain no longer runs their repair passes.

function resetRepoMocks(): void {
  mocks.markFailed.mockResolvedValue(undefined);
  mocks.markDeadLettered.mockResolvedValue(undefined);
  mocks.remove.mockResolvedValue(undefined);
  mocks.setReminderConvexId.mockResolvedValue(undefined);
  mocks.getReminderConvexId.mockResolvedValue(null);
  mocks.markReminderSynced.mockResolvedValue(undefined);
  mocks.markReminderError.mockResolvedValue(undefined);
  mocks.setInstanceConvexId.mockResolvedValue(undefined);
  mocks.getInstanceConvexId.mockResolvedValue(null);
  mocks.markInstanceSynced.mockResolvedValue(undefined);
  mocks.markInstanceError.mockResolvedValue(undefined);
}

interface FakeConvexClient {
  readonly mutation: ReturnType<typeof vi.fn>;
  readonly query: ReturnType<typeof vi.fn>;
}

function fakeClient(): FakeConvexClient {
  return { mutation: vi.fn(), query: vi.fn() };
}

/**
 * The constructor takes a client so a test can supply one; this cast is only
 * about the type. `SyncWorker` calls exactly two of `ConvexReactClient`'s
 * methods, and implementing the rest to satisfy the interface would be a page
 * of surface nothing reads.
 */
function workerWith(client: FakeConvexClient): SyncWorker {
  return new SyncWorker(client as unknown as ConvexReactClient);
}

function queueEntry(overrides: Partial<SyncQueueEntry>): SyncQueueEntry {
  return {
    id: 1,
    entityType: "reminder",
    entityId: "local-reminder-1",
    operation: "create",
    payload: "{}",
    attempts: 0,
    lastError: null,
    createdAt: 1_700_000_000_000,
    deadLetter: false,
    ...overrides,
  };
}

/** A `reminder` create payload, in the shape `db/repos/reminders.ts` writes. */
function reminderPayload(
  overrides: Record<string, unknown> = {}
): Record<string, unknown> {
  return {
    localId: "local-reminder-1",
    userId: "user-1",
    kind: "medication",
    name: "Metformin",
    rxcui: null,
    dosage: null,
    instructions: null,
    schedule: { type: "daily", times: ["09:00"] },
    priority: null,
    tags: null,
    pillsRemaining: null,
    pillsPerDose: null,
    refillReminderDays: null,
    caregiverIds: null,
    active: true,
    createdAt: 1_700_000_000_000,
    updatedAt: 1_700_000_000_000,
    ...overrides,
  };
}

/** A `reminder_instance` payload, as `db/repos/reminderInstances.ts` writes it. */
function instancePayload(
  overrides: Record<string, unknown> = {}
): Record<string, unknown> {
  return {
    localId: "local-instance-1",
    userId: "user-1",
    reminderId: "local-reminder-1",
    reminderKind: "medication",
    scheduledTime: 1_700_000_100_000,
    status: "pending",
    completedAt: null,
    snoozedUntil: null,
    snoozeCount: 0,
    notes: null,
    createdAt: 1_700_000_000_000,
    updatedAt: 1_700_000_000_000,
    ...overrides,
  };
}

describe("SyncWorker.drain", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The happy path by default; each test degrades the one thing it is about.
    mocks.netInfoFetch.mockResolvedValue({ isConnected: true });
    mocks.pending.mockResolvedValue([]);
    mocks.reconcileReminders.mockResolvedValue(undefined);
    mocks.reconcileInstances.mockResolvedValue(undefined);
    resetRepoMocks();
    vi.spyOn(console, "error").mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("reaches the queue and reports idle when connected", async () => {
    const worker = new SyncWorker();

    await worker.drain();

    expect(mocks.pending).toHaveBeenCalledTimes(1);
    expect(worker.currentStatus).toBe("idle");
  });

  it("releases the guard after an offline drain", async () => {
    // The shipped regression: launching the app offline left the flag set and
    // every later drain short-circuited, for the life of the process.
    mocks.netInfoFetch.mockResolvedValue({ isConnected: false });
    const worker = new SyncWorker();

    await worker.drain();
    expect(worker.currentStatus).toBe("offline");
    expect(mocks.pending).not.toHaveBeenCalled();

    mocks.netInfoFetch.mockResolvedValue({ isConnected: true });
    await worker.drain();

    expect(mocks.pending).toHaveBeenCalledTimes(1);
    expect(worker.currentStatus).toBe("idle");
  });

  it("releases the guard when the connectivity check throws", async () => {
    mocks.netInfoFetch.mockRejectedValue(new Error("netinfo unavailable"));
    const worker = new SyncWorker();

    await worker.drain();
    expect(worker.currentStatus).toBe("error");
    expect(mocks.pending).not.toHaveBeenCalled();

    mocks.netInfoFetch.mockResolvedValue({ isConnected: true });
    await worker.drain();

    expect(mocks.pending).toHaveBeenCalledTimes(1);
  });

  it("releases the guard when the drain body throws", async () => {
    mocks.pending.mockRejectedValueOnce(new Error("database is gone"));
    const worker = new SyncWorker();

    await worker.drain();
    expect(worker.currentStatus).toBe("error");

    await worker.drain();

    expect(mocks.pending).toHaveBeenCalledTimes(2);
    expect(worker.currentStatus).toBe("idle");
  });

  it("does not drain concurrently", async () => {
    // `start()` fires `void this.drain()` while the NetInfo listener may fire
    // another. Without the guard both drain the same queue snapshot.
    let releaseQueue: () => void = () => undefined;
    const queued = new Promise<never[]>((resolve) => {
      releaseQueue = () => {
        resolve([]);
      };
    });
    mocks.pending.mockReturnValue(queued);
    const worker = new SyncWorker();

    // `drain()` claims the flag synchronously, before its first await, so the
    // second call short-circuits without waiting for anything.
    const first = worker.drain();
    const second = worker.drain();
    await vi.waitFor(() => {
      expect(mocks.pending).toHaveBeenCalled();
    });
    releaseQueue();
    await Promise.all([first, second]);

    expect(mocks.pending).toHaveBeenCalledTimes(1);
  });

  it("still drains when reconciliation fails", async () => {
    // Both reconcile passes are best-effort: the queue as it already stands is
    // worth draining, and the next drain retries them.
    mocks.reconcileReminders.mockRejectedValueOnce(new Error("reconcile blew"));
    mocks.reconcileInstances.mockRejectedValueOnce(new Error("also blew"));
    const worker = new SyncWorker();

    await worker.drain();

    expect(mocks.pending).toHaveBeenCalledTimes(1);
    expect(worker.currentStatus).toBe("idle");
  });
});

/**
 * Executable coverage for the push loop itself — the body of the `for` over
 * `syncQueueRepo.pending()`.
 *
 * The suite above drains an EMPTY queue on every path, because its subject is
 * the re-entrancy guard. That left the part of the worker issue #7 is actually
 * about — which unified mutation an entry reaches, and what happens to an entry
 * that keeps failing — as TypeScript nothing executed. Two of that ticket's
 * acceptance criteria rest on this loop: "push drains to the unified backend
 * functions" and "entries that fail repeatedly still dead-letter rather than
 * retrying forever".
 *
 * The queue and the repositories are mocked, as above. The Convex client is
 * not a mock of a real deployment either — it is a pair of spies, and what
 * these tests assert is the *dispatch*: entity type and operation in, one named
 * mutation and one argument object out. Whether `api.reminders.create` then
 * does the right thing is `packages/convex/convex/reminders.test.ts`.
 *
 * That is squarely the shape `docs/specs/fused-timeline.md` warns against —
 * "never [assert] on ... which collaborator was called". The exception is
 * deliberate and narrow: dispatch is the entire behaviour under review here.
 * "A completed Instance is pushed as `markCompleted`, not `markSkipped`" has no
 * return value and nothing a person could observe short of a live deployment;
 * the mutation reference IS the outcome. Held to the rule literally, the branch
 * that routes four statuses to four mutations could not be tested at all, and
 * `e371caa` — every push resetting the server's `priority` — is exactly the
 * class of bug that costs.
 */
describe("SyncWorker push", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.netInfoFetch.mockResolvedValue({ isConnected: true });
    mocks.pending.mockResolvedValue([]);
    mocks.reconcileReminders.mockResolvedValue(undefined);
    mocks.reconcileInstances.mockResolvedValue(undefined);
    resetRepoMocks();
    vi.spyOn(console, "error").mockImplementation(() => undefined);
  });

  afterEach(() => {
    // Before `restoreAllMocks`: the failing-entry tests install fake timers to
    // skip the retry backoff, and leaking them into the suite above breaks the
    // `vi.waitFor` in "does not drain concurrently".
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("sends a reminder create to api.reminders.create and records the id", async () => {
    mocks.pending.mockResolvedValue([
      queueEntry({ payload: JSON.stringify(reminderPayload()) }),
    ]);
    const client = fakeClient();
    client.mutation.mockResolvedValue("convex-reminder-1");

    await workerWith(client).drain();

    expect(client.mutation).toHaveBeenCalledTimes(1);
    expect(client.mutation).toHaveBeenCalledWith(
      "api.reminders.create",
      expect.objectContaining({
        localId: "local-reminder-1",
        userId: "user-1",
        kind: "medication",
        name: "Metformin",
        schedule: { type: "daily", times: ["09:00"] },
        active: true,
      })
    );
    // The returned document id has to reach the row, or every later update
    // re-resolves it over the network.
    expect(mocks.setReminderConvexId).toHaveBeenCalledWith(
      "local-reminder-1",
      "convex-reminder-1"
    );
  });

  it("omits the fields this device has no value for", async () => {
    // The regression in `e371caa`: these three were sent unconditionally, so
    // every push reset the server's copy to normal/[]/[]. A null payload field
    // means "nothing to offer", which is an OMITTED argument, not a null one.
    mocks.pending.mockResolvedValue([
      queueEntry({ payload: JSON.stringify(reminderPayload()) }),
    ]);
    const client = fakeClient();
    client.mutation.mockResolvedValue("convex-reminder-1");

    await workerWith(client).drain();

    const args = client.mutation.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(args).not.toHaveProperty("priority");
    expect(args).not.toHaveProperty("tags");
    expect(args).not.toHaveProperty("caregiverIds");
    expect(args).not.toHaveProperty("dosage");
  });

  it("sends a value this device does author", async () => {
    // The other half of the pair above: omission has to be conditional on the
    // null, not unconditional. Dropping the field always would also pass the
    // previous test.
    mocks.pending.mockResolvedValue([
      queueEntry({
        payload: JSON.stringify(
          reminderPayload({ priority: "critical", dosage: "500mg" })
        ),
      }),
    ]);
    const client = fakeClient();
    client.mutation.mockResolvedValue("convex-reminder-1");

    await workerWith(client).drain();

    expect(client.mutation).toHaveBeenCalledWith(
      "api.reminders.create",
      expect.objectContaining({ priority: "critical", dosage: "500mg" })
    );
  });

  it("clears the entry and marks the source row synced after a push", async () => {
    mocks.pending.mockResolvedValue([
      queueEntry({ id: 42, payload: JSON.stringify(reminderPayload()) }),
    ]);
    const client = fakeClient();
    client.mutation.mockResolvedValue("convex-reminder-1");

    const worker = workerWith(client);
    await worker.drain();

    expect(mocks.remove).toHaveBeenCalledWith(42);
    expect(mocks.markReminderSynced).toHaveBeenCalledWith("local-reminder-1");
    expect(mocks.markDeadLettered).not.toHaveBeenCalled();
    expect(worker.currentStatus).toBe("idle");
  });

  it("resolves the parent to a Convex id before creating an instance", async () => {
    // `eecee06`: the pull path once wrote a Convex id where a local UUID
    // belonged. This is the same join in the other direction — the mutation
    // takes an `Id<"reminders">`, and the payload names the parent by LOCAL id.
    mocks.pending.mockResolvedValue([
      queueEntry({
        id: 7,
        entityType: "reminder_instance",
        entityId: "local-instance-1",
        payload: JSON.stringify(instancePayload()),
      }),
    ]);
    mocks.getReminderConvexId.mockResolvedValue("convex-reminder-1");
    const client = fakeClient();
    client.mutation.mockResolvedValue("convex-instance-1");

    await workerWith(client).drain();

    expect(mocks.getReminderConvexId).toHaveBeenCalledWith("local-reminder-1");
    expect(client.mutation).toHaveBeenCalledWith(
      "api.reminderInstances.create",
      expect.objectContaining({
        localId: "local-instance-1",
        reminderId: "convex-reminder-1",
        scheduledTime: 1_700_000_100_000,
      })
    );
    expect(mocks.setInstanceConvexId).toHaveBeenCalledWith(
      "local-instance-1",
      "convex-instance-1"
    );
  });

  it("asks the server for a parent the local row has not cached", async () => {
    mocks.pending.mockResolvedValue([
      queueEntry({
        entityType: "reminder_instance",
        entityId: "local-instance-1",
        payload: JSON.stringify(instancePayload()),
      }),
    ]);
    mocks.getReminderConvexId.mockResolvedValue(null);
    const client = fakeClient();
    client.query.mockResolvedValue({ _id: "convex-reminder-9" });
    client.mutation.mockResolvedValue("convex-instance-1");

    await workerWith(client).drain();

    expect(client.query).toHaveBeenCalledWith("api.reminders.getByLocalId", {
      localId: "local-reminder-1",
    });
    // Cached on the way through, so the next entry does not repeat the query.
    expect(mocks.setReminderConvexId).toHaveBeenCalledWith(
      "local-reminder-1",
      "convex-reminder-9"
    );
    expect(client.mutation).toHaveBeenCalledWith(
      "api.reminderInstances.create",
      expect.objectContaining({ reminderId: "convex-reminder-9" })
    );
  });

  it.each([
    {
      status: "completed",
      extra: { completedAt: 1_700_000_200_000 },
      mutation: "api.reminderInstances.markCompleted",
      args: { completedAt: 1_700_000_200_000 },
    },
    {
      status: "snoozed",
      extra: { snoozedUntil: 1_700_000_300_000 },
      mutation: "api.reminderInstances.snooze",
      args: { snoozedUntil: 1_700_000_300_000 },
    },
    {
      status: "skipped",
      extra: { notes: "felt unwell" },
      mutation: "api.reminderInstances.markSkipped",
      args: { notes: "felt unwell" },
    },
    {
      status: "missed",
      extra: {},
      mutation: "api.reminderInstances.markMissed",
      args: {},
    },
  ])(
    "routes a $status instance update to $mutation",
    async ({ status, extra, mutation, args }) => {
      mocks.pending.mockResolvedValue([
        queueEntry({
          entityType: "reminder_instance",
          entityId: "local-instance-1",
          operation: "update",
          payload: JSON.stringify(instancePayload({ status, ...extra })),
        }),
      ]);
      mocks.getInstanceConvexId.mockResolvedValue("convex-instance-1");
      const client = fakeClient();
      client.mutation.mockResolvedValue(undefined);

      const worker = workerWith(client);
      await worker.drain();

      expect(client.mutation).toHaveBeenCalledWith(mutation, {
        id: "convex-instance-1",
        ...args,
      });
      expect(worker.currentStatus).toBe("idle");
    }
  );

  it("does not send a snooze that carries no snoozedUntil", async () => {
    // `snooze` requires the wake time; sending the mutation without it would
    // be rejected at the validator, so the entry fails here instead.
    mocks.pending.mockResolvedValue([
      queueEntry({
        entityType: "reminder_instance",
        entityId: "local-instance-1",
        operation: "update",
        payload: JSON.stringify(
          instancePayload({ status: "snoozed", snoozedUntil: null })
        ),
      }),
    ]);
    mocks.getInstanceConvexId.mockResolvedValue("convex-instance-1");
    const client = fakeClient();
    vi.useFakeTimers();

    const worker = workerWith(client);
    const draining = worker.drain();
    await vi.runAllTimersAsync();
    await draining;

    expect(client.mutation).not.toHaveBeenCalled();
    expect(mocks.markFailed).toHaveBeenCalledWith(
      1,
      "Snoozed instance is missing snoozedUntil"
    );
  });

  it("dead-letters an entry on its fifth failure", async () => {
    // MAX_ATTEMPTS is 5 and `attempts` counts the failures BEFORE this one, so
    // 4 makes this the fifth and last.
    mocks.pending.mockResolvedValue([
      queueEntry({
        id: 9,
        attempts: 4,
        payload: JSON.stringify(reminderPayload()),
      }),
    ]);
    const client = fakeClient();
    client.mutation.mockRejectedValue(new Error("server said no"));
    vi.useFakeTimers();

    const worker = workerWith(client);
    const draining = worker.drain();
    await vi.runAllTimersAsync();
    await draining;

    expect(mocks.markFailed).toHaveBeenCalledWith(9, "server said no");
    expect(mocks.markDeadLettered).toHaveBeenCalledWith(9);
    // The row itself has to carry the error, not just the queue: the queue
    // entry is invisible to the screens.
    expect(mocks.markReminderError).toHaveBeenCalledWith(
      "local-reminder-1",
      "server said no"
    );
    expect(mocks.remove).not.toHaveBeenCalled();
    expect(worker.currentStatus).toBe("error");
  });

  it("leaves an entry with attempts remaining in the queue", async () => {
    mocks.pending.mockResolvedValue([
      queueEntry({
        id: 9,
        attempts: 3,
        payload: JSON.stringify(reminderPayload()),
      }),
    ]);
    const client = fakeClient();
    client.mutation.mockRejectedValue(new Error("server said no"));
    vi.useFakeTimers();

    const worker = workerWith(client);
    const draining = worker.drain();
    await vi.runAllTimersAsync();
    await draining;

    expect(mocks.markFailed).toHaveBeenCalledWith(9, "server said no");
    expect(mocks.markDeadLettered).not.toHaveBeenCalled();
    expect(mocks.markReminderError).not.toHaveBeenCalled();
  });

  it("delivers a change made offline once connectivity returns", async () => {
    // Issue #7's third criterion, end to end through the worker: the entry is
    // written while offline, survives a drain that cannot reach the network,
    // and pushes on the next one.
    const entry = queueEntry({ payload: JSON.stringify(reminderPayload()) });
    mocks.pending.mockResolvedValue([entry]);
    mocks.netInfoFetch.mockResolvedValue({ isConnected: false });
    const client = fakeClient();
    client.mutation.mockResolvedValue("convex-reminder-1");

    const worker = workerWith(client);
    await worker.drain();

    expect(client.mutation).not.toHaveBeenCalled();
    expect(mocks.remove).not.toHaveBeenCalled();
    expect(worker.currentStatus).toBe("offline");

    mocks.netInfoFetch.mockResolvedValue({ isConnected: true });
    await worker.drain();

    expect(client.mutation).toHaveBeenCalledWith(
      "api.reminders.create",
      expect.objectContaining({ localId: "local-reminder-1" })
    );
    expect(mocks.remove).toHaveBeenCalledWith(entry.id);
    expect(worker.currentStatus).toBe("idle");
  });
});
