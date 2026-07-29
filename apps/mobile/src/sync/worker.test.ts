import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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
  pending: vi.fn<() => Promise<never[]>>(),
  reconcileReminders: vi.fn<() => Promise<void>>(),
  reconcileInstances: vi.fn<() => Promise<void>>(),
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

vi.mock("@taskrx/convex", () => ({ api: {} }));

vi.mock("../db/repos/reminders", () => ({
  reconcilePending: mocks.reconcileReminders,
}));

vi.mock("../db/repos/reminderInstances", () => ({
  reconcilePending: mocks.reconcileInstances,
}));

vi.mock("../db/repos/syncQueue", () => ({
  pending: mocks.pending,
  markFailed: vi.fn(),
  markDeadLettered: vi.fn(),
  remove: vi.fn(),
}));

vi.mock("../db/repos/medications", () => ({}));
vi.mock("../db/repos/doseLogs", () => ({}));

// The two mirrors are mocked for the same reason every other repo here is:
// they reach `getDb()` and pull expo-sqlite, and through it react-native, into
// a node test's module graph. `repairMissingProjections` resolves rather than
// throws so the default case exercises the drain's ordinary path; the tests
// that care about a failing repair override it per case.
vi.mock("../db/repos/medication-mirror", () => ({
  repairMissingProjections: vi.fn(() => Promise.resolve(0)),
}));
vi.mock("../db/repos/dose-log-mirror", () => ({
  repairMissingProjections: vi.fn(() => Promise.resolve(0)),
}));

describe("SyncWorker.drain", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The happy path by default; each test degrades the one thing it is about.
    mocks.netInfoFetch.mockResolvedValue({ isConnected: true });
    mocks.pending.mockResolvedValue([]);
    mocks.reconcileReminders.mockResolvedValue(undefined);
    mocks.reconcileInstances.mockResolvedValue(undefined);
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
