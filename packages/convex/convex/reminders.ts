import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";
import {
  priorityValidator,
  reminderFields,
  reminderKindValidator,
  scheduleTimesValidator,
} from "./schema.js";

/**
 * Reminder queries and mutations — the unified replacement for `medications`.
 *
 * All functions enforce per-user access via `getCurrentUserId`. Reads are
 * scoped to that user by index, never by a post-hoc `.filter`, and drop
 * soft-deleted rows. Writes verify ownership before mutating a document.
 *
 * The `localId` field stores the stable UUID generated on the device, so the
 * sync worker can map local SQLite rows to Convex documents and retry creates
 * idempotently — a create that already landed applies its payload to the
 * existing document and returns that `_id` instead of inserting a duplicate.
 *
 * `create` fills the same defaults as `createReminder` in `@taskrx/shared`: an
 * empty daily schedule, `normal` priority, no tags or caregivers, and active.
 * Both spellings of "a new reminder" therefore agree, whether the row is built
 * on the device or straight through the API.
 */

const reminderDoc = v.object({
  _id: v.id("reminders"),
  _creationTime: v.number(),
  ...reminderFields,
});

export const list = query({
  args: { kind: v.optional(reminderKindValidator) },
  returns: v.array(reminderDoc),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const { kind } = args;

    const rows = await (
      kind === undefined
        ? ctx.db
            .query("reminders")
            .withIndex("by_user", (q) => q.eq("userId", userId))
        : ctx.db
            .query("reminders")
            .withIndex("by_user_kind", (q) =>
              q.eq("userId", userId).eq("kind", kind)
            )
    ).collect();

    return rows.filter((row) => row.deletedAt === undefined);
  },
});

export const get = query({
  args: { id: v.id("reminders") },
  returns: v.union(reminderDoc, v.null()),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const reminder = await ctx.db.get(args.id);
    if (reminder?.userId !== userId) {
      return null;
    }
    return reminder;
  },
});

export const getByLocalId = query({
  args: { localId: v.string() },
  returns: v.union(reminderDoc, v.null()),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const reminder = await ctx.db
      .query("reminders")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    return reminder?.userId === userId ? reminder : null;
  },
});

export const create = mutation({
  args: {
    localId: v.string(),
    userId: v.string(),
    kind: reminderKindValidator,
    name: v.string(),
    rxcui: v.optional(v.string()),
    dosage: v.optional(v.string()),
    instructions: v.optional(v.string()),
    schedule: v.optional(scheduleTimesValidator),
    priority: v.optional(priorityValidator),
    tags: v.optional(v.array(v.string())),
    pillsRemaining: v.optional(v.number()),
    pillsPerDose: v.optional(v.number()),
    refillReminderDays: v.optional(v.number()),
    caregiverIds: v.optional(v.array(v.string())),
    active: v.optional(v.boolean()),
    createdAt: v.number(),
    updatedAt: v.number(),
  },
  returns: v.id("reminders"),
  handler: async (ctx, args) => {
    const currentUserId = await getCurrentUserId(ctx);
    if (args.userId !== currentUserId) {
      throw new Error("Cannot create reminder for another user");
    }

    const existing = await ctx.db
      .query("reminders")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    if (existing !== null) {
      if (existing.userId !== currentUserId) {
        throw new Error("Reminder localId already exists for another user");
      }

      /**
       * A repeated `create` for the same `localId` is an upsert, not a no-op.
       *
       * The device derives the operation from whether its local row has a
       * `convex_id` yet, so an edit made while the first create is still queued
       * enqueues a *second* create — delete a medication just after adding it
       * offline and the follow-up carries `active: false`. Returning
       * `existing._id` without writing left the server permanently behind: the
       * client marks the row synced and never re-sends, so a discontinued
       * medication would stay active on Convex forever.
       *
       * The payload is applied with the same defaults as the insert below,
       * rather than only where the client supplied a value. `enqueueExternalWrite`
       * always sends the whole row — `schedule`, `priority`, `tags`,
       * `caregiverIds` and `active` are never omitted — so the fallbacks are
       * unreachable from the sync worker, and making both paths agree means the
       * document ends up identical no matter which call landed first. The
       * optionals keep the insert's conditional spreads, so an omitted one is
       * left alone rather than cleared; that matches the worker, which encodes a
       * cleared field as an omission and already documents that clearing does
       * not round-trip. `localId`, `userId` and `createdAt` are identity, not
       * payload, and are never rewritten; `deletedAt` is likewise left in place
       * so a replayed create cannot resurrect a row deleted on this side.
       */
      await ctx.db.patch(existing._id, {
        kind: args.kind,
        name: args.name,
        schedule: args.schedule ?? { type: "daily", times: [] },
        priority: args.priority ?? "normal",
        tags: args.tags ?? [],
        caregiverIds: args.caregiverIds ?? [],
        active: args.active ?? true,
        updatedAt: args.updatedAt,
        ...(args.rxcui !== undefined ? { rxcui: args.rxcui } : {}),
        ...(args.dosage !== undefined ? { dosage: args.dosage } : {}),
        ...(args.instructions !== undefined
          ? { instructions: args.instructions }
          : {}),
        ...(args.pillsRemaining !== undefined
          ? { pillsRemaining: args.pillsRemaining }
          : {}),
        ...(args.pillsPerDose !== undefined
          ? { pillsPerDose: args.pillsPerDose }
          : {}),
        ...(args.refillReminderDays !== undefined
          ? { refillReminderDays: args.refillReminderDays }
          : {}),
      });

      return existing._id;
    }

    return await ctx.db.insert("reminders", {
      userId: currentUserId,
      localId: args.localId,
      kind: args.kind,
      name: args.name,
      schedule: args.schedule ?? { type: "daily", times: [] },
      priority: args.priority ?? "normal",
      tags: args.tags ?? [],
      caregiverIds: args.caregiverIds ?? [],
      active: args.active ?? true,
      createdAt: args.createdAt,
      updatedAt: args.updatedAt,
      ...(args.rxcui !== undefined ? { rxcui: args.rxcui } : {}),
      ...(args.dosage !== undefined ? { dosage: args.dosage } : {}),
      ...(args.instructions !== undefined
        ? { instructions: args.instructions }
        : {}),
      ...(args.pillsRemaining !== undefined
        ? { pillsRemaining: args.pillsRemaining }
        : {}),
      ...(args.pillsPerDose !== undefined
        ? { pillsPerDose: args.pillsPerDose }
        : {}),
      ...(args.refillReminderDays !== undefined
        ? { refillReminderDays: args.refillReminderDays }
        : {}),
    });
  },
});

export const update = mutation({
  args: {
    id: v.id("reminders"),
    name: v.optional(v.string()),
    rxcui: v.optional(v.string()),
    dosage: v.optional(v.string()),
    instructions: v.optional(v.string()),
    schedule: v.optional(scheduleTimesValidator),
    priority: v.optional(priorityValidator),
    tags: v.optional(v.array(v.string())),
    pillsRemaining: v.optional(v.number()),
    pillsPerDose: v.optional(v.number()),
    refillReminderDays: v.optional(v.number()),
    caregiverIds: v.optional(v.array(v.string())),
    active: v.optional(v.boolean()),
    updatedAt: v.optional(v.number()),
  },
  returns: v.id("reminders"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Reminder not found");
    }

    const now = Date.now();
    await ctx.db.patch(args.id, {
      updatedAt: args.updatedAt ?? now,
      ...(args.name !== undefined ? { name: args.name } : {}),
      ...(args.rxcui !== undefined ? { rxcui: args.rxcui } : {}),
      ...(args.dosage !== undefined ? { dosage: args.dosage } : {}),
      ...(args.instructions !== undefined
        ? { instructions: args.instructions }
        : {}),
      ...(args.schedule !== undefined ? { schedule: args.schedule } : {}),
      ...(args.priority !== undefined ? { priority: args.priority } : {}),
      ...(args.tags !== undefined ? { tags: args.tags } : {}),
      ...(args.pillsRemaining !== undefined
        ? { pillsRemaining: args.pillsRemaining }
        : {}),
      ...(args.pillsPerDose !== undefined
        ? { pillsPerDose: args.pillsPerDose }
        : {}),
      ...(args.refillReminderDays !== undefined
        ? { refillReminderDays: args.refillReminderDays }
        : {}),
      ...(args.caregiverIds !== undefined
        ? { caregiverIds: args.caregiverIds }
        : {}),
      ...(args.active !== undefined ? { active: args.active } : {}),
    });

    return args.id;
  },
});

/**
 * Records a dose being drawn from the on-hand supply.
 *
 * Kept apart from `update` because it is a read-modify-write against the
 * current count rather than an absolute set: two devices logging a dose while
 * offline should decrement twice, which they cannot express by each sending
 * their own idea of `pillsRemaining`. Returns `null` when the reminder does not
 * track a supply at all, and clamps at zero otherwise.
 */
export const consumePills = mutation({
  args: {
    id: v.id("reminders"),
    count: v.optional(v.number()),
  },
  returns: v.union(v.number(), v.null()),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const reminder = await ctx.db.get(args.id);
    if (reminder?.userId !== userId) {
      throw new Error("Reminder not found");
    }

    if (reminder.pillsRemaining === undefined) {
      return null;
    }

    const consumed = args.count ?? reminder.pillsPerDose ?? 1;
    const remaining = Math.max(0, reminder.pillsRemaining - consumed);

    await ctx.db.patch(args.id, {
      pillsRemaining: remaining,
      updatedAt: Date.now(),
    });

    return remaining;
  },
});

export const softDelete = mutation({
  args: { id: v.id("reminders") },
  returns: v.id("reminders"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Reminder not found");
    }

    await ctx.db.patch(args.id, {
      active: false,
      deletedAt: Date.now(),
      updatedAt: Date.now(),
    });

    return args.id;
  },
});
