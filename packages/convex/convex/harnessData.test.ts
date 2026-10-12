/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";

import { internal } from "./_generated/api.js";
import schema from "./schema.js";

/**
 * The purge that `harnessData.purgeSeeded` performs is the only hard delete
 * anywhere in this deployment, so it is the one function here whose blast
 * radius is worth pinning with tests rather than reasoning about.
 *
 * Two properties matter and they pull in opposite directions: it has to remove
 * every trace of a seeded reminder, including the instances that would
 * otherwise be orphaned and go on counting toward adherence, and it has to be
 * incapable of touching anything a person created. The prefix is the only thing
 * separating the two, so both sides are asserted.
 */

const modules = import.meta.glob("./**/*.ts");

const OWNER = "user_owner";
const NOW = Date.UTC(2026, 7, 3, 12, 0, 0);

function deployment() {
  return convexTest(schema, modules);
}

/** Mirrors `HARNESS_REMINDER_MARKER` in archive/expo-rn:apps/mobile/src/notifications/harness.ts. */
const MARKER = "[harness]";

async function seed(
  t: ReturnType<typeof convexTest>,
  name: string,
  userId = OWNER
): Promise<{ reminderId: string; instanceId: string }> {
  return t.run(async (ctx) => {
    const reminderId = await ctx.db.insert("reminders", {
      localId: `local-${name}`,
      userId,
      kind: "medication",
      name,
      schedule: { type: "daily", times: ["09:00"] },
      priority: "normal",
      tags: [],
      caregiverIds: [],
      active: true,
      createdAt: NOW,
      updatedAt: NOW,
    });
    const instanceId = await ctx.db.insert("reminderInstances", {
      localId: `local-inst-${name}`,
      userId,
      reminderId,
      reminderKind: "medication",
      scheduledTime: NOW,
      status: "pending",
      snoozeCount: 0,
      createdAt: NOW,
      updatedAt: NOW,
    });
    return { reminderId, instanceId };
  });
}

describe("purgeSeeded", () => {
  it("deletes a seeded reminder and its instances", async () => {
    const t = deployment();
    await seed(t, `${MARKER} tap-1`);

    const result = await t.mutation(internal.harnessData.purgeSeeded, {});

    expect(result).toEqual({ reminders: 1, instances: 1 });
    await t.run(async (ctx) => {
      expect(await ctx.db.query("reminders").take(10)).toHaveLength(0);
      expect(await ctx.db.query("reminderInstances").take(10)).toHaveLength(0);
    });
  });

  it("leaves a real medication and its doses alone", async () => {
    // The whole safety argument in one case. A prefix match is a blunt
    // instrument, and the only thing standing between this function and
    // someone's medication history is that it matches the START of the name.
    const t = deployment();
    await seed(t, "metformin Pill");
    await seed(t, "My [harness] notes");

    const result = await t.mutation(internal.harnessData.purgeSeeded, {});

    expect(result).toEqual({ reminders: 0, instances: 0 });
    await t.run(async (ctx) => {
      expect(await ctx.db.query("reminders").take(10)).toHaveLength(2);
      expect(await ctx.db.query("reminderInstances").take(10)).toHaveLength(2);
    });
  });

  it("deletes instances belonging to a seeded reminder across every user", async () => {
    // Not user-scoped, deliberately: it runs as an internal mutation from the
    // CLI, where there is no identity to scope to. A reminder carrying the
    // marker is rig output whoever owns it.
    const t = deployment();
    await seed(t, `${MARKER} drain-1`, "user_a");
    await seed(t, `${MARKER} drain-2`, "user_b");

    const result = await t.mutation(internal.harnessData.purgeSeeded, {});

    expect(result).toEqual({ reminders: 2, instances: 2 });
  });

  it("removes an instance whose parent is seeded even when it is answered", async () => {
    // A completed instance is the one a purge is most tempted to keep and the
    // one that most needs to go: it is a fabricated dose, and adherence is
    // computed from exactly these rows.
    const t = deployment();
    const { reminderId } = await seed(t, `${MARKER} tap-2`);
    await t.run(async (ctx) => {
      await ctx.db.insert("reminderInstances", {
        localId: "local-answered",
        userId: OWNER,
        reminderId: reminderId as never,
        reminderKind: "medication",
        scheduledTime: NOW,
        status: "completed",
        completedAt: NOW,
        snoozeCount: 0,
        createdAt: NOW,
        updatedAt: NOW,
      });
    });

    const result = await t.mutation(internal.harnessData.purgeSeeded, {});

    expect(result).toEqual({ reminders: 1, instances: 2 });
  });

  it("is a no-op on a deployment with nothing seeded", async () => {
    const t = deployment();

    await expect(
      t.mutation(internal.harnessData.purgeSeeded, {})
    ).resolves.toEqual({ reminders: 0, instances: 0 });
  });
});
