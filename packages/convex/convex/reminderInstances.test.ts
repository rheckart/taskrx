/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";

import { api } from "./_generated/api.js";
import schema from "./schema.js";

/**
 * `reminderInstances` — one materialized occurrence of a reminder: the 08:00
 * dose on a particular day, not the standing prescription.
 *
 * Three things here are not just `doseLogs` with new column names.
 *
 * `reminderKind` is denormalized from the parent so a day view can split
 * medications from tasks without loading every reminder, and `create` reads it
 * off the parent on *both* the insert and the upsert path rather than accepting
 * it from the client — the client cannot send it at all. The tests below prove
 * the denormalized copy tracks the parent instead of a caller's claim.
 *
 * `snoozeCount` is the field the upsert path treats differently from every
 * other optional: `status` is defaulted on patch (that is the whole point of
 * the branch), while `snoozeCount` is spread conditionally, because defaulting
 * it to 0 on a patch would reset a count the server had already accumulated.
 * The device's pull path projects dose logs, which have no snooze column, so
 * payloads omitting it are routine rather than hypothetical.
 *
 * `snooze` itself increments server-side so two devices deferring the same
 * occurrence both land.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const OTHER = "user_other";
const NOW = Date.UTC(2026, 6, 29, 12, 0, 0);
const HOUR = 3_600_000;

function deployment() {
  const t = convexTest(schema, modules);
  return {
    anonymous: t,
    owner: t.withIdentity({ subject: OWNER }),
    other: t.withIdentity({ subject: OTHER }),
  };
}

/** A reminder owned by `subject`, plus the instance payload pointing at it. */
async function seed(
  as: ReturnType<typeof deployment>["owner"],
  subject: string,
  {
    kind = "medication" as const,
    suffix = "",
  }: { kind?: "medication" | "task"; suffix?: string } = {}
) {
  const reminderId = await as.mutation(api.reminders.create, {
    localId: `local-reminder${suffix}`,
    userId: subject,
    kind,
    name: kind === "task" ? "Check blood pressure" : "Metformin",
    schedule: { type: "daily", times: ["08:00"] },
    active: true,
    createdAt: NOW,
    updatedAt: NOW,
  });

  return {
    reminderId,
    instance: {
      localId: `local-instance${suffix}`,
      userId: subject,
      reminderId,
      scheduledTime: NOW,
      status: "pending" as const,
      snoozeCount: 0,
      createdAt: NOW,
      updatedAt: NOW,
    },
  };
}

describe("reminderInstances.create", () => {
  it("refuses a payload addressed to another user", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);

    await expect(
      owner.mutation(api.reminderInstances.create, {
        ...instance,
        userId: OTHER,
      })
    ).rejects.toThrow(/Cannot create reminder instance for another user/);
  });

  /**
   * The check the payload's own `userId` does not make: a self-consistent
   * payload hung off someone else's reminder. Only the parent lookup rejects it.
   */
  it("refuses to attach an instance to a reminder the caller does not own", async () => {
    const { owner, other } = deployment();
    const { reminderId } = await seed(owner, OWNER);

    await expect(
      other.mutation(api.reminderInstances.create, {
        localId: "local-instance-intruder",
        userId: OTHER,
        reminderId,
        scheduledTime: NOW,
        status: "completed",
        createdAt: NOW,
        updatedAt: NOW,
      })
    ).rejects.toThrow(/Reminder not found/);
  });

  it("refuses to take over a localId another user already holds", async () => {
    const { owner, other } = deployment();
    const { instance } = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, instance);

    const theirs = await seed(other, OTHER, { suffix: "-other" });

    await expect(
      other.mutation(api.reminderInstances.create, {
        ...theirs.instance,
        localId: instance.localId,
      })
    ).rejects.toThrow(/localId already exists for another user/);
  });

  it("denormalizes reminderKind off the parent", async () => {
    const { owner } = deployment();
    const med = await seed(owner, OWNER);
    const task = await seed(owner, OWNER, { kind: "task", suffix: "-task" });

    await owner.mutation(api.reminderInstances.create, med.instance);
    await owner.mutation(api.reminderInstances.create, task.instance);

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: med.instance.localId,
      })
    ).toMatchObject({ reminderKind: "medication" });
    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: task.instance.localId,
      })
    ).toMatchObject({ reminderKind: "task" });
  });

  it("defaults status and snoozeCount on a first insert", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const { status: _s, snoozeCount: _c, ...bare } = instance;

    await owner.mutation(api.reminderInstances.create, bare);

    const row = await owner.query(api.reminderInstances.getByLocalId, {
      localId: instance.localId,
    });
    expect(row).toMatchObject({ status: "pending", snoozeCount: 0 });
    expect(row).not.toHaveProperty("completedAt");
    expect(row).not.toHaveProperty("snoozedUntil");
    expect(row).not.toHaveProperty("notes");
  });
});

describe("reminderInstances.create replayed for the same localId", () => {
  it("applies a status that changed before the first create drained", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const first = await owner.mutation(api.reminderInstances.create, instance);

    const second = await owner.mutation(api.reminderInstances.create, {
      ...instance,
      status: "completed",
      completedAt: NOW + 5 * 60_000,
      updatedAt: NOW + 5 * 60_000,
    });

    expect(second).toEqual(first);
    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ status: "completed", completedAt: NOW + 5 * 60_000 });
  });

  /**
   * The distinction the handler's comment draws, executed.
   *
   * A snooze accumulated on the server between the first create and the
   * duplicate must survive a payload that does not mention `snoozeCount` —
   * which is every payload the pull path projects out of a dose log, since that
   * table has no snooze column. Defaulting it to 0 on patch, the way `status`
   * is defaulted, would silently forget the deferral.
   */
  it("keeps a snoozeCount the server accumulated when the payload omits it", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);
    await owner.mutation(api.reminderInstances.snooze, {
      id,
      snoozedUntil: NOW + HOUR,
    });

    const { snoozeCount: _omitted, ...withoutCount } = instance;
    await owner.mutation(api.reminderInstances.create, {
      ...withoutCount,
      updatedAt: NOW + 60_000,
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ snoozeCount: 1 });
  });

  it("still honours a snoozeCount the payload does supply", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, instance);

    await owner.mutation(api.reminderInstances.create, {
      ...instance,
      snoozeCount: 3,
      updatedAt: NOW + 60_000,
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ snoozeCount: 3 });
  });

  it("defaults an omitted status to pending, unlike its optional siblings", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);
    await owner.mutation(api.reminderInstances.markCompleted, {
      id,
      completedAt: NOW + 60_000,
    });

    const { status: _dropped, ...withoutStatus } = instance;
    await owner.mutation(api.reminderInstances.create, {
      ...withoutStatus,
      updatedAt: NOW + 120_000,
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ status: "pending" });
  });

  it("leaves an omitted completedAt, snoozedUntil and notes alone", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, {
      ...instance,
      status: "completed",
      completedAt: NOW + 60_000,
      snoozedUntil: NOW + HOUR,
      notes: "with breakfast",
    });

    await owner.mutation(api.reminderInstances.create, {
      ...instance,
      status: "completed",
      updatedAt: NOW + 120_000,
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({
      completedAt: NOW + 60_000,
      snoozedUntil: NOW + HOUR,
      notes: "with breakfast",
    });
  });

  it("never rewrites createdAt, which is identity rather than payload", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, instance);

    await owner.mutation(api.reminderInstances.create, {
      ...instance,
      createdAt: NOW + 999_999,
      updatedAt: NOW + 60_000,
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ createdAt: NOW });
  });

  /**
   * Re-pointing at a different parent re-reads the kind from that parent, so
   * the denormalized copy cannot be left describing the reminder the instance
   * used to belong to.
   */
  it("re-reads reminderKind when the payload names a different reminder", async () => {
    const { owner } = deployment();
    const med = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, med.instance);
    const task = await seed(owner, OWNER, { kind: "task", suffix: "-task" });

    await owner.mutation(api.reminderInstances.create, {
      ...med.instance,
      reminderId: task.reminderId,
      updatedAt: NOW + 60_000,
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: med.instance.localId,
      })
    ).toMatchObject({ reminderId: task.reminderId, reminderKind: "task" });
  });
});

describe("reminderInstances.list", () => {
  /**
   * Four instances across six hours: two pending, two taken, one of them a
   * task. Both statuses appear *twice*, at times that straddle the window the
   * tests ask for — otherwise a status query and a status-plus-window query
   * return the same single row, and the window half of the index builder could
   * be deleted without any assertion noticing.
   */
  async function seedWindow(owner: ReturnType<typeof deployment>["owner"]) {
    const med = await seed(owner, OWNER);
    const task = await seed(owner, OWNER, { kind: "task", suffix: "-task" });

    await owner.mutation(api.reminderInstances.create, med.instance);
    await owner.mutation(api.reminderInstances.create, {
      ...med.instance,
      localId: "local-instance-2",
      scheduledTime: NOW + 2 * HOUR,
      status: "completed",
    });
    await owner.mutation(api.reminderInstances.create, {
      ...task.instance,
      localId: "local-instance-3",
      scheduledTime: NOW + 4 * HOUR,
    });
    await owner.mutation(api.reminderInstances.create, {
      ...med.instance,
      localId: "local-instance-4",
      scheduledTime: NOW + 6 * HOUR,
      status: "completed",
    });
    return { med, task };
  }

  it("returns only the caller's own rows", async () => {
    const { owner, other } = deployment();
    const mine = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, mine.instance);
    const theirs = await seed(other, OTHER, { suffix: "-other" });
    await other.mutation(api.reminderInstances.create, theirs.instance);

    expect(await owner.query(api.reminderInstances.list, {})).toMatchObject([
      { userId: OWNER },
    ]);
    expect(await other.query(api.reminderInstances.list, {})).toMatchObject([
      { userId: OTHER },
    ]);
  });

  it("narrows on the status index", async () => {
    const { owner } = deployment();
    await seedWindow(owner);

    expect(
      await owner.query(api.reminderInstances.list, { status: "pending" })
    ).toMatchObject([
      { localId: "local-instance" },
      { localId: "local-instance-3" },
    ]);
    expect(
      await owner.query(api.reminderInstances.list, { status: "completed" })
    ).toMatchObject([
      { localId: "local-instance-2" },
      { localId: "local-instance-4" },
    ]);
  });

  it("applies since and until, together and separately", async () => {
    const { owner } = deployment();
    await seedWindow(owner);

    expect(
      await owner.query(api.reminderInstances.list, { since: NOW + HOUR })
    ).toHaveLength(3);
    expect(
      await owner.query(api.reminderInstances.list, { until: NOW + HOUR })
    ).toHaveLength(1);
    expect(
      await owner.query(api.reminderInstances.list, {
        since: NOW + HOUR,
        until: NOW + 3 * HOUR,
      })
    ).toMatchObject([{ localId: "local-instance-2" }]);
  });

  it("combines a status narrowing with a time window", async () => {
    const { owner } = deployment();
    await seedWindow(owner);

    expect(
      await owner.query(api.reminderInstances.list, {
        status: "pending",
        since: NOW + 3 * HOUR,
      })
    ).toMatchObject([{ localId: "local-instance-3" }]);
    expect(
      await owner.query(api.reminderInstances.list, {
        status: "pending",
        until: NOW + HOUR,
      })
    ).toMatchObject([{ localId: "local-instance" }]);
    // Both `taken` rows sit at or after `since`; only the window's upper bound
    // separates them, so this is the assertion that holds `until` in place on
    // the status index.
    expect(
      await owner.query(api.reminderInstances.list, {
        status: "completed",
        since: NOW + HOUR,
        until: NOW + 3 * HOUR,
      })
    ).toMatchObject([{ localId: "local-instance-2" }]);
  });

  it("filters by reminderKind on top of whichever index served the read", async () => {
    const { owner } = deployment();
    await seedWindow(owner);

    expect(
      await owner.query(api.reminderInstances.list, { reminderKind: "task" })
    ).toMatchObject([{ localId: "local-instance-3" }]);
    expect(
      await owner.query(api.reminderInstances.list, {
        reminderKind: "medication",
      })
    ).toHaveLength(3);
    // The kind filter runs in memory, so it has to compose with the status
    // index rather than replace it.
    expect(
      await owner.query(api.reminderInstances.list, {
        status: "pending",
        reminderKind: "medication",
      })
    ).toMatchObject([{ localId: "local-instance" }]);
  });
});

describe("reminderInstances.listForReminder", () => {
  /**
   * `by_reminder` is not user-scoped, so the `userId` filter hung off it is the
   * only thing between a guessed reminder id and another patient's history.
   */
  it("returns nothing for a reminder belonging to someone else", async () => {
    const { owner, other } = deployment();
    const mine = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, mine.instance);

    expect(
      await owner.query(api.reminderInstances.listForReminder, {
        reminderId: mine.reminderId,
      })
    ).toHaveLength(1);
    expect(
      await other.query(api.reminderInstances.listForReminder, {
        reminderId: mine.reminderId,
      })
    ).toEqual([]);
  });
});

describe("reminderInstances.getByLocalId", () => {
  it("does not resolve another user's localId", async () => {
    const { owner, other } = deployment();
    const { instance } = await seed(owner, OWNER);
    await owner.mutation(api.reminderInstances.create, instance);

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ userId: OWNER });
    expect(
      await other.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toBeNull();
  });
});

describe("reminderInstances.markCompleted", () => {
  it("refuses an instance belonging to someone else", async () => {
    const { owner, other } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    await expect(
      other.mutation(api.reminderInstances.markCompleted, { id })
    ).rejects.toThrow(/Reminder instance not found/);
    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ status: "pending" });
  });

  it("honours an explicit completedAt", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    await owner.mutation(api.reminderInstances.markCompleted, {
      id,
      completedAt: NOW + 60_000,
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ status: "completed", completedAt: NOW + 60_000 });
  });

  it("falls back to wall-clock time when completedAt is omitted", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    const before = Date.now();
    await owner.mutation(api.reminderInstances.markCompleted, { id });
    const after = Date.now();

    const row = await owner.query(api.reminderInstances.getByLocalId, {
      localId: instance.localId,
    });
    expect(row?.completedAt).toBeGreaterThanOrEqual(before);
    expect(row?.completedAt).toBeLessThanOrEqual(after);
  });
});

describe("reminderInstances.markSkipped", () => {
  it("refuses an instance belonging to someone else", async () => {
    const { owner, other } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    await expect(
      other.mutation(api.reminderInstances.markSkipped, { id })
    ).rejects.toThrow(/Reminder instance not found/);
  });

  it("records the reason when one is given", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    await owner.mutation(api.reminderInstances.markSkipped, {
      id,
      notes: "nauseous",
    });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ status: "skipped", notes: "nauseous" });
  });

  /**
   * The conditional spread `doseLogs.markSkipped` does not have. Re-skipping
   * without repeating the reason must not erase it.
   */
  it("keeps an existing note when re-skipped without one", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);
    await owner.mutation(api.reminderInstances.markSkipped, {
      id,
      notes: "nauseous",
    });

    await owner.mutation(api.reminderInstances.markSkipped, { id });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({ status: "skipped", notes: "nauseous" });
  });
});

describe("reminderInstances.markMissed", () => {
  it("refuses an instance belonging to someone else", async () => {
    const { owner, other } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    await expect(
      other.mutation(api.reminderInstances.markMissed, { id })
    ).rejects.toThrow(/Reminder instance not found/);
  });

  it("sets the status without disturbing the snooze bookkeeping", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);
    await owner.mutation(api.reminderInstances.snooze, {
      id,
      snoozedUntil: NOW + HOUR,
    });

    await owner.mutation(api.reminderInstances.markMissed, { id });

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({
      status: "missed",
      snoozeCount: 1,
      snoozedUntil: NOW + HOUR,
    });
  });
});

describe("reminderInstances.snooze", () => {
  it("refuses an instance belonging to someone else", async () => {
    const { owner, other } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    await expect(
      other.mutation(api.reminderInstances.snooze, {
        id,
        snoozedUntil: NOW + HOUR,
      })
    ).rejects.toThrow(/Reminder instance not found/);
  });

  /**
   * The increment is server-side and cumulative, which is what makes
   * `snoozeCount` an honest measure of how many times this one occurrence was
   * pushed back — rather than a value two devices can each overwrite with 1.
   */
  it("counts each deferral and returns the running total", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, instance);

    expect(
      await owner.mutation(api.reminderInstances.snooze, {
        id,
        snoozedUntil: NOW + HOUR,
      })
    ).toBe(1);
    expect(
      await owner.mutation(api.reminderInstances.snooze, {
        id,
        snoozedUntil: NOW + 2 * HOUR,
      })
    ).toBe(2);

    expect(
      await owner.query(api.reminderInstances.getByLocalId, {
        localId: instance.localId,
      })
    ).toMatchObject({
      status: "snoozed",
      snoozeCount: 2,
      snoozedUntil: NOW + 2 * HOUR,
    });
  });

  it("counts from whatever the row already carried, not from zero", async () => {
    const { owner } = deployment();
    const { instance } = await seed(owner, OWNER);
    const id = await owner.mutation(api.reminderInstances.create, {
      ...instance,
      snoozeCount: 4,
    });

    expect(
      await owner.mutation(api.reminderInstances.snooze, {
        id,
        snoozedUntil: NOW + HOUR,
      })
    ).toBe(5);
  });
});

describe("reminderInstances without a signed-in identity", () => {
  it("rejects reads", async () => {
    const { anonymous } = deployment();

    await expect(
      anonymous.query(api.reminderInstances.list, {})
    ).rejects.toThrow(/Not authenticated/);
    await expect(
      anonymous.query(api.reminderInstances.getByLocalId, {
        localId: "local-instance",
      })
    ).rejects.toThrow(/Not authenticated/);
  });
});
