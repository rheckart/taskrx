import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Executable coverage for the harness action layer's AUTH-DEPENDENT actions.
 *
 * The actions that existed before this file (`schedule`, `pending-count`, ...)
 * are local-only and are already exercised on-device by harness cases 1-7. The
 * ones added here are not: they write to SQLite as a signed-in user, so a
 * mistake in them corrupts the very rows the case under test then reads back,
 * and the failure surfaces as a product FAIL in an evidence artifact rather
 * than as a broken test.
 *
 * Two properties are worth stating because they are the ones that make the
 * device cases trustworthy rather than merely green:
 *
 * - **A signed-out run must write NOTHING.** `docs/poc-results.md` records
 *   criteria as pending rather than failed when they were never run, and the
 *   same discipline applies here: an action that cannot identify the user must
 *   report that and stop, not seed rows under a guessed id.
 * - **`seed-due-reminder` arms through `rescheduleAll`, not through notifee
 *   directly.** In the signed-in tree `rescheduleAll` runs on every foreground
 *   and cancels every trigger outside the schedule-derived desired set, so an
 *   alarm armed any other way is swept away before it can fire (harness
 *   constraint 11). The assertion that it delegates is therefore load-bearing.
 *
 * Everything below the repositories is mocked: the subject is which rows and
 * calls each action produces, and a real `expo-sqlite` would pull React Native
 * into a node test.
 */

const mocks = vi.hoisted(() => ({
  getTriggerNotificationIds: vi.fn<() => Promise<string[]>>(),
  createTriggerNotification: vi.fn<() => Promise<void>>(),
  createNotificationChannel: vi.fn<() => Promise<void>>(),
  cancelAll: vi.fn<() => Promise<void>>(),
  scheduleForReminder: vi.fn<() => Promise<void>>(),
  rescheduleAll: vi.fn<(userId: string) => Promise<void>>(),
  checkExactAlarmPermission: vi.fn<() => Promise<boolean>>(),
  remindersCreate: vi.fn(),
  remindersList: vi.fn(),
  remindersSoftDelete: vi.fn<(id: string) => Promise<void>>(),
  instancesList: vi.fn(),
  queuePending: vi.fn(),
  queueDeadLettered: vi.fn(),
  syncStatus: vi.fn<() => string>(),
  dbRunAsync:
    vi.fn<
      (
        sql: string,
        ...args: unknown[]
      ) => Promise<{ changes: number; lastInsertRowId: number }>
    >(),
  dbGetAllAsync:
    vi.fn<(sql: string, ...args: unknown[]) => Promise<unknown[]>>(),
}));

vi.mock("../db", () => ({
  getDb: () =>
    Promise.resolve({
      runAsync: mocks.dbRunAsync,
      getAllAsync: mocks.dbGetAllAsync,
    }),
}));

vi.mock("react-native-notify-kit", () => ({
  default: {
    getTriggerNotificationIds: mocks.getTriggerNotificationIds,
    createTriggerNotification: mocks.createTriggerNotification,
  },
  AlarmType: { SET_ALARM_CLOCK: "SET_ALARM_CLOCK" },
  AndroidImportance: { HIGH: 4 },
  TriggerType: { TIMESTAMP: 0 },
}));

vi.mock("./index", () => ({
  MEDICATION_REMINDER_CHANNEL_ID: "medication_reminders",
  createNotificationChannel: mocks.createNotificationChannel,
  cancelAll: mocks.cancelAll,
  scheduleForReminder: mocks.scheduleForReminder,
  rescheduleAll: mocks.rescheduleAll,
}));

vi.mock("./permissions", () => ({
  checkExactAlarmPermission: mocks.checkExactAlarmPermission,
}));

vi.mock("../db/repos/reminders", () => ({
  create: mocks.remindersCreate,
  list: mocks.remindersList,
  softDelete: mocks.remindersSoftDelete,
}));

vi.mock("../db/repos/reminderInstances", () => ({
  list: mocks.instancesList,
}));

vi.mock("../db/repos/syncQueue", () => ({
  pending: mocks.queuePending,
  deadLettered: mocks.queueDeadLettered,
}));

vi.mock("../sync/worker", () => ({
  syncWorker: {
    get currentStatus() {
      return mocks.syncStatus();
    },
  },
}));

const { HARNESS_REMINDER_MARKER, parseHarnessSpec, runHarnessAction } =
  await import("./harness");

const USER = "user_test_1";
const SIGNED_IN = { userId: USER };
const SIGNED_OUT = { userId: null };

/** 2026-08-03T14:20:30Z. The suite runs with TZ=UTC (vitest.config.ts). */
const NOW = Date.UTC(2026, 7, 3, 14, 20, 30);

function spec(action: string, id = "x", third = "90000"): string[] {
  return [action, id, third, "nonce"];
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  mocks.getTriggerNotificationIds.mockResolvedValue([]);
  mocks.remindersList.mockResolvedValue([]);
  mocks.instancesList.mockResolvedValue([]);
  mocks.queuePending.mockResolvedValue([]);
  mocks.queueDeadLettered.mockResolvedValue([]);
  mocks.syncStatus.mockReturnValue("idle");
  mocks.dbGetAllAsync.mockResolvedValue([]);
  mocks.dbRunAsync.mockResolvedValue({ changes: 0, lastInsertRowId: 0 });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("parseHarnessSpec", () => {
  it("recognises the auth-dependent actions", () => {
    for (const action of [
      "auth-status",
      "seed-due-reminder",
      "instance-at",
      "queue-count",
      "sync-status",
      "cleanup-seeded",
    ]) {
      expect(parseHarnessSpec(spec(action)).action).toBe(action);
    }
  });

  it("falls back to pending-count for an unknown action", () => {
    expect(parseHarnessSpec(spec("not-an-action")).action).toBe(
      "pending-count"
    );
  });

  it("exposes the third segment both clamped and raw", () => {
    // `delayMs` is a relative delay and is floored at 1s so a scheduling action
    // cannot arm an alarm in the past. `value` is the same segment unclamped,
    // because `instance-at` reads it as an ABSOLUTE epoch and clamping an
    // epoch would be meaningless — they must not be the same number by
    // accident.
    const parsed = parseHarnessSpec(spec("instance-at", "r1", "5"));
    expect(parsed.delayMs).toBe(1000);
    expect(parsed.value).toBe(5);
  });
});

describe("auth-status", () => {
  it("reports the signed-in subject", async () => {
    await expect(
      runHarnessAction(parseHarnessSpec(spec("auth-status")), SIGNED_IN)
    ).resolves.toBe(`signed_in=true user_id=${USER}`);
  });

  it("reports a signed-out app", async () => {
    await expect(
      runHarnessAction(parseHarnessSpec(spec("auth-status")), SIGNED_OUT)
    ).resolves.toBe("signed_in=false user_id=none");
  });
});

describe("seed-due-reminder", () => {
  it("writes nothing when signed out", async () => {
    const result = await runHarnessAction(
      parseHarnessSpec(spec("seed-due-reminder", "tap-test")),
      SIGNED_OUT
    );

    expect(result).toBe("signed_in=false user_id=none");
    expect(mocks.remindersCreate).not.toHaveBeenCalled();
    expect(mocks.rescheduleAll).not.toHaveBeenCalled();
  });

  it("seeds a daily reminder on the first minute at or after now+delay", async () => {
    mocks.remindersCreate.mockResolvedValue({ id: "seed-1" });

    await runHarnessAction(
      parseHarnessSpec(spec("seed-due-reminder", "tap-test", "90000")),
      SIGNED_IN
    );

    // 14:20:30 + 90s = 14:22:00 exactly. Rounding UP is what guarantees the
    // caller gets at least the lead time it asked for: rounding down would
    // land at 14:21 and fire 30s early, before a case had finished its setup.
    expect(mocks.remindersCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        userId: USER,
        kind: "medication",
        name: `${HARNESS_REMINDER_MARKER} tap-test`,
        schedule: { type: "daily", times: ["14:22"] },
      })
    );
  });

  it("rounds up to the next whole minute when now+delay lands mid-minute", async () => {
    mocks.remindersCreate.mockResolvedValue({ id: "seed-1" });

    await runHarnessAction(
      parseHarnessSpec(spec("seed-due-reminder", "tap-test", "100000")),
      SIGNED_IN
    );

    // 14:20:30 + 100s = 14:22:10 -> 14:23, never 14:22.
    expect(mocks.remindersCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        schedule: { type: "daily", times: ["14:23"] },
      })
    );
  });

  it("arms through rescheduleAll and reports the fire time it observes", async () => {
    mocks.remindersCreate.mockResolvedValue({ id: "seed-1" });
    const fireAt = Date.UTC(2026, 7, 3, 14, 22, 0);
    mocks.getTriggerNotificationIds.mockResolvedValue([
      "other-reminder-123",
      `seed-1-${String(fireAt)}`,
    ]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("seed-due-reminder", "tap-test", "90000")),
      SIGNED_IN
    );

    // Delegation, not a direct notifee write: an alarm armed outside the
    // schedule-derived set does not survive the next foreground.
    expect(mocks.rescheduleAll).toHaveBeenCalledWith(USER);
    expect(mocks.createTriggerNotification).not.toHaveBeenCalled();
    expect(result).toBe(
      `seeded reminder_id=seed-1 fire_at=${String(fireAt)} hhmm=14:22 armed=1`
    );
  });

  it("reports fire_at=none when the scheduler armed nothing for the seed", async () => {
    // Not an error the action can diagnose, and not one it may paper over: a
    // reported fire time that no alarm backs would make the device case wait
    // for a notification that was never going to post, then blame delivery.
    mocks.remindersCreate.mockResolvedValue({ id: "seed-1" });
    mocks.getTriggerNotificationIds.mockResolvedValue(["someone-else-999"]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("seed-due-reminder", "tap-test", "90000")),
      SIGNED_IN
    );

    expect(result).toBe(
      "seeded reminder_id=seed-1 fire_at=none hhmm=14:22 armed=0"
    );
  });

  it("reports the soonest armed occurrence when several are pending", async () => {
    mocks.remindersCreate.mockResolvedValue({ id: "seed-1" });
    const today = Date.UTC(2026, 7, 3, 14, 22, 0);
    const tomorrow = Date.UTC(2026, 7, 4, 14, 22, 0);
    mocks.getTriggerNotificationIds.mockResolvedValue([
      `seed-1-${String(tomorrow)}`,
      `seed-1-${String(today)}`,
    ]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("seed-due-reminder", "tap-test", "90000")),
      SIGNED_IN
    );

    expect(result).toContain(`fire_at=${String(today)}`);
    expect(result).toContain("armed=2");
  });
});

describe("instance-at", () => {
  it("reports none when the occurrence has no instance", async () => {
    const at = Date.UTC(2026, 7, 3, 14, 22, 0);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("instance-at", "seed-1", String(at))),
      SIGNED_IN
    );

    expect(mocks.instancesList).toHaveBeenCalledWith({
      userId: USER,
      reminderId: "seed-1",
      since: at,
      until: at,
    });
    expect(result).toBe(
      "instance_at=none status=none count=0 completed=none statuses=none"
    );
  });

  it("reports the instance id and status for the exact pairing", async () => {
    // Any status, not just pending: case 9 reads this back AFTER the dose has
    // been answered, and `findAnswerableForOccurrence` would report none for a
    // row that exists and is completed.
    const at = Date.UTC(2026, 7, 3, 14, 22, 0);
    mocks.instancesList.mockResolvedValue([
      { id: "inst-1", status: "completed", createdAt: 1 },
    ]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("instance-at", "seed-1", String(at))),
      SIGNED_IN
    );

    expect(result).toBe(
      "instance_at=inst-1 status=completed count=1 completed=inst-1 statuses=completed"
    );
  });

  it("names the COMPLETED row when an occurrence carries several", async () => {
    // Not hypothetical, and the reason this field exists. Answering a dose
    // leaves TWO instances at one pairing on a real device — `/dose/log`
    // re-runs its load after the write, finds no *pending* instance because
    // the one it had is now completed, and mints a fresh one. Reporting
    // whichever row sorted first made case 9 report "the dose was not recorded
    // offline" for a dose that was recorded, so the answered row is named
    // explicitly rather than left to sort order.
    const at = Date.UTC(2026, 7, 3, 14, 22, 0);
    mocks.instancesList.mockResolvedValue([
      { id: "inst-dup", status: "pending", createdAt: 2 },
      { id: "inst-answered", status: "completed", createdAt: 1 },
    ]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("instance-at", "seed-1", String(at))),
      SIGNED_IN
    );

    // Oldest first, so `instance_at` is the row the screen minted on load
    // rather than whatever the SQL happened to return first.
    expect(result).toBe(
      "instance_at=inst-answered status=completed count=2 " +
        "completed=inst-answered statuses=completed,pending"
    );
  });

  it("refuses to answer while signed out", async () => {
    const result = await runHarnessAction(
      parseHarnessSpec(spec("instance-at", "seed-1", "1")),
      SIGNED_OUT
    );

    expect(result).toBe("signed_in=false user_id=none");
    expect(mocks.instancesList).not.toHaveBeenCalled();
  });
});

describe("queue-count", () => {
  it("reports the pending and dead-lettered depths separately", async () => {
    // Separately because they drain differently: a dead-lettered row will
    // never leave the queue on reconnect, so folding it into `pending` would
    // make case 9 wait out its whole budget for a row that cannot move.
    mocks.queuePending.mockResolvedValue([{ id: 1 }, { id: 2 }]);
    mocks.queueDeadLettered.mockResolvedValue([{ id: 3 }]);

    await expect(
      runHarnessAction(parseHarnessSpec(spec("queue-count")), SIGNED_IN)
    ).resolves.toBe("queue_pending=2 queue_dead=1");
  });

  it("reads the queue even when signed out", async () => {
    // The queue is not user-scoped and the count is diagnostic, so this one
    // deliberately answers rather than refusing.
    await expect(
      runHarnessAction(parseHarnessSpec(spec("queue-count")), SIGNED_OUT)
    ).resolves.toBe("queue_pending=0 queue_dead=0");
  });
});

describe("sync-status", () => {
  it("reports the worker's own status", async () => {
    mocks.syncStatus.mockReturnValue("offline");

    await expect(
      runHarnessAction(parseHarnessSpec(spec("sync-status")), SIGNED_IN)
    ).resolves.toBe("sync_status=offline");
  });
});

describe("purge-seeded", () => {
  it("hard-deletes only the seeded reminders, their instances and their queue entries", async () => {
    mocks.dbGetAllAsync
      .mockResolvedValueOnce([
        { id: "real-1", name: "metformin Pill" },
        { id: "seed-1", name: `${HARNESS_REMINDER_MARKER} tap-test` },
      ])
      .mockResolvedValueOnce([{ id: "inst-1" }, { id: "inst-2" }]);
    // The counts must come from SQLite, not from the id lists handed to each
    // statement. Deliberately mismatched: the queue delete is given THREE ids
    // (one reminder + two instances) and removes ONE row, which is the normal
    // case — most seeded rows have already synced and left the queue. Reporting
    // the id count said `purged_queue=29` on a queue holding a single row.
    mocks.dbRunAsync
      .mockResolvedValueOnce({ changes: 1, lastInsertRowId: 0 })
      .mockResolvedValueOnce({ changes: 2, lastInsertRowId: 0 })
      .mockResolvedValueOnce({ changes: 1, lastInsertRowId: 0 });

    const result = await runHarnessAction(
      parseHarnessSpec(spec("purge-seeded")),
      SIGNED_IN
    );

    const statements = mocks.dbRunAsync.mock.calls.map((call) =>
      call[0].replace(/\s+/g, " ").trim()
    );
    // Children before parents: an instance whose reminder is already gone is
    // the orphan the purge exists to remove, and the foreign key would reject
    // the parent delete anyway.
    expect(statements[0]).toContain("DELETE FROM sync_queue");
    expect(statements[1]).toContain("DELETE FROM reminder_instances");
    expect(statements[2]).toContain("DELETE FROM reminders");
    // Every statement is scoped to the ids gathered above — never a bare
    // DELETE, and never a LIKE against the name, which would put the marker's
    // brackets into SQL and the matching rule in two places.
    for (const statement of statements) {
      expect(statement).toContain("IN (");
    }
    expect(result).toBe("purged_reminders=1 purged_instances=2 purged_queue=1");
  });

  it("matches the marker as a prefix, not anywhere in the name", async () => {
    // The only thing standing between this and someone's medication history is
    // that the match is anchored. "My [harness] notes" is a name a person could
    // plausibly type, and a substring match would delete it and every dose
    // recorded against it.
    mocks.dbGetAllAsync.mockResolvedValueOnce([
      { id: "real-1", name: `My ${HARNESS_REMINDER_MARKER} notes` },
    ]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("purge-seeded")),
      SIGNED_IN
    );

    expect(mocks.dbRunAsync).not.toHaveBeenCalled();
    expect(result).toBe("purged_reminders=0 purged_instances=0 purged_queue=0");
  });

  it("touches nothing when no reminder carries the marker", async () => {
    mocks.dbGetAllAsync.mockResolvedValueOnce([
      { id: "real-1", name: "metformin Pill" },
    ]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("purge-seeded")),
      SIGNED_IN
    );

    expect(mocks.dbRunAsync).not.toHaveBeenCalled();
    expect(result).toBe("purged_reminders=0 purged_instances=0 purged_queue=0");
  });

  it("reads only the signed-in user's reminders", async () => {
    mocks.dbGetAllAsync.mockResolvedValueOnce([]);

    await runHarnessAction(parseHarnessSpec(spec("purge-seeded")), SIGNED_IN);

    expect(mocks.dbGetAllAsync).toHaveBeenCalledWith(
      expect.stringContaining("user_id = ?"),
      USER
    );
  });

  it("deletes nothing when signed out", async () => {
    const result = await runHarnessAction(
      parseHarnessSpec(spec("purge-seeded")),
      SIGNED_OUT
    );

    expect(result).toBe("signed_in=false user_id=none");
    expect(mocks.dbRunAsync).not.toHaveBeenCalled();
  });
});

describe("cleanup-seeded", () => {
  it("deletes only the reminders this harness seeded", async () => {
    mocks.remindersList.mockResolvedValue([
      { id: "real-1", name: "metformin Pill" },
      { id: "seed-1", name: `${HARNESS_REMINDER_MARKER} tap-test` },
      { id: "seed-2", name: `${HARNESS_REMINDER_MARKER} drain-test` },
    ]);

    const result = await runHarnessAction(
      parseHarnessSpec(spec("cleanup-seeded")),
      SIGNED_IN
    );

    expect(mocks.remindersSoftDelete).toHaveBeenCalledTimes(2);
    expect(mocks.remindersSoftDelete).toHaveBeenCalledWith("seed-1");
    expect(mocks.remindersSoftDelete).toHaveBeenCalledWith("seed-2");
    expect(mocks.remindersSoftDelete).not.toHaveBeenCalledWith("real-1");
    expect(result).toBe("cleaned=2");
  });

  it("re-arms the surviving schedule after deleting", async () => {
    // The delete alone leaves the seeded reminder's alarms pending, and they
    // would then fire on a device nobody is testing. `rescheduleAll` is what
    // retracts them.
    mocks.remindersList.mockResolvedValue([
      { id: "seed-1", name: `${HARNESS_REMINDER_MARKER} tap-test` },
    ]);

    await runHarnessAction(parseHarnessSpec(spec("cleanup-seeded")), SIGNED_IN);

    expect(mocks.rescheduleAll).toHaveBeenCalledWith(USER);
  });

  it("deletes nothing when signed out", async () => {
    const result = await runHarnessAction(
      parseHarnessSpec(spec("cleanup-seeded")),
      SIGNED_OUT
    );

    expect(result).toBe("signed_in=false user_id=none");
    expect(mocks.remindersSoftDelete).not.toHaveBeenCalled();
  });
});
