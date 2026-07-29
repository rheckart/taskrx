import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";
import {
  instanceStatusValidator,
  reminderInstanceFields,
  reminderKindValidator,
} from "./schema.js";

/**
 * Reminder instance queries and mutations — the unified replacement for
 * `doseLogs`. An instance is one materialized occurrence of a reminder: the
 * 08:00 dose on a particular day, not the standing prescription.
 *
 * All functions enforce per-user access via `getCurrentUserId`. Writes verify
 * ownership before mutating a document, and `create` additionally verifies
 * ownership of the parent reminder before writing a child that points at it —
 * the same check `doseLogs.create` does against `medications`.
 *
 * `localId` carries the device-generated UUID and makes `create` idempotent
 * across app restarts; see `reminders.ts` for the full note.
 *
 * The status vocabulary gains `snoozed`, which is why `snooze` exists as its
 * own mutation rather than a `status` argument to a generic setter: deferring
 * an instance has to bump `snoozeCount` in the same transaction, and the count
 * is what distinguishes "reminded once more" from "ignored all morning".
 */

const reminderInstanceDoc = v.object({
  _id: v.id("reminderInstances"),
  _creationTime: v.number(),
  ...reminderInstanceFields,
});

/**
 * Reads a user's instances over a time window.
 *
 * Every branch is served by an index rather than a table scan: a `status`
 * filter narrows on `by_user_status_time` — the stand-in for the archived
 * partial index on pending rows — and everything else narrows on
 * `by_user_time`. `reminderKind` is applied in memory, since it only ever
 * halves an already-bounded window.
 */
export const list = query({
  args: {
    status: v.optional(instanceStatusValidator),
    reminderKind: v.optional(reminderKindValidator),
    since: v.optional(v.number()),
    until: v.optional(v.number()),
  },
  returns: v.array(reminderInstanceDoc),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const { status, reminderKind, since, until } = args;

    const rows = await (
      status === undefined
        ? ctx.db.query("reminderInstances").withIndex("by_user_time", (q) => {
            const scoped = q.eq("userId", userId);
            if (since !== undefined && until !== undefined) {
              return scoped
                .gte("scheduledTime", since)
                .lte("scheduledTime", until);
            }
            if (since !== undefined) return scoped.gte("scheduledTime", since);
            if (until !== undefined) return scoped.lte("scheduledTime", until);
            return scoped;
          })
        : ctx.db
            .query("reminderInstances")
            .withIndex("by_user_status_time", (q) => {
              const scoped = q.eq("userId", userId).eq("status", status);
              if (since !== undefined && until !== undefined) {
                return scoped
                  .gte("scheduledTime", since)
                  .lte("scheduledTime", until);
              }
              if (since !== undefined)
                return scoped.gte("scheduledTime", since);
              if (until !== undefined)
                return scoped.lte("scheduledTime", until);
              return scoped;
            })
    ).collect();

    return reminderKind === undefined
      ? rows
      : rows.filter((row) => row.reminderKind === reminderKind);
  },
});

export const listForReminder = query({
  args: { reminderId: v.id("reminders") },
  returns: v.array(reminderInstanceDoc),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const rows = await ctx.db
      .query("reminderInstances")
      .withIndex("by_reminder", (q) => q.eq("reminderId", args.reminderId))
      .collect();

    return rows.filter((row) => row.userId === userId);
  },
});

export const getByLocalId = query({
  args: { localId: v.string() },
  returns: v.union(reminderInstanceDoc, v.null()),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const instance = await ctx.db
      .query("reminderInstances")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    return instance?.userId === userId ? instance : null;
  },
});

/**
 * Materializes one occurrence of a reminder.
 *
 * `reminderKind` is read off the parent rather than accepted from the client,
 * so the denormalized copy cannot disagree with the reminder it came from.
 */
export const create = mutation({
  args: {
    localId: v.string(),
    userId: v.string(),
    reminderId: v.id("reminders"),
    scheduledTime: v.number(),
    status: v.optional(instanceStatusValidator),
    takenAt: v.optional(v.number()),
    snoozedUntil: v.optional(v.number()),
    snoozeCount: v.optional(v.number()),
    notes: v.optional(v.string()),
    createdAt: v.number(),
    updatedAt: v.number(),
  },
  returns: v.id("reminderInstances"),
  handler: async (ctx, args) => {
    const currentUserId = await getCurrentUserId(ctx);
    if (args.userId !== currentUserId) {
      throw new Error("Cannot create reminder instance for another user");
    }

    const reminder = await ctx.db.get(args.reminderId);
    if (reminder?.userId !== currentUserId) {
      throw new Error("Reminder not found");
    }

    const existing = await ctx.db
      .query("reminderInstances")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    if (existing !== null) {
      if (existing.userId !== currentUserId) {
        throw new Error(
          "Reminder instance localId already exists for another user"
        );
      }

      /**
       * A repeated `create` for the same `localId` is an upsert, not a no-op,
       * for the reason spelled out in `reminders.ts`: the device picks `create`
       * whenever its local row still has no `convex_id`, so an instance taken
       * or snoozed while its first create is still queued enqueues a *second*
       * create carrying the newer status. Returning `existing._id` without
       * writing dropped that status on the floor, and the client marks the row
       * synced and never re-sends it.
       *
       * Defaults match the insert below for the same reason they do there — the
       * device always sends `status` and `snoozeCount`, so the fallbacks are
       * unreachable in practice and both paths produce the same document.
       * `reminderKind` is re-read off the parent rather than trusted from the
       * payload, exactly as on insert. `localId`, `userId` and `createdAt` are
       * identity and stay put.
       */
      await ctx.db.patch(existing._id, {
        reminderId: args.reminderId,
        reminderKind: reminder.kind,
        scheduledTime: args.scheduledTime,
        status: args.status ?? "pending",
        snoozeCount: args.snoozeCount ?? 0,
        updatedAt: args.updatedAt,
        ...(args.takenAt !== undefined ? { takenAt: args.takenAt } : {}),
        ...(args.snoozedUntil !== undefined
          ? { snoozedUntil: args.snoozedUntil }
          : {}),
        ...(args.notes !== undefined ? { notes: args.notes } : {}),
      });

      return existing._id;
    }

    return await ctx.db.insert("reminderInstances", {
      userId: currentUserId,
      localId: args.localId,
      reminderId: args.reminderId,
      reminderKind: reminder.kind,
      scheduledTime: args.scheduledTime,
      status: args.status ?? "pending",
      snoozeCount: args.snoozeCount ?? 0,
      createdAt: args.createdAt,
      updatedAt: args.updatedAt,
      ...(args.takenAt !== undefined ? { takenAt: args.takenAt } : {}),
      ...(args.snoozedUntil !== undefined
        ? { snoozedUntil: args.snoozedUntil }
        : {}),
      ...(args.notes !== undefined ? { notes: args.notes } : {}),
    });
  },
});

export const markTaken = mutation({
  args: {
    id: v.id("reminderInstances"),
    takenAt: v.optional(v.number()),
  },
  returns: v.id("reminderInstances"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const instance = await ctx.db.get(args.id);
    if (instance?.userId !== userId) {
      throw new Error("Reminder instance not found");
    }

    await ctx.db.patch(args.id, {
      status: "taken",
      takenAt: args.takenAt ?? Date.now(),
      updatedAt: Date.now(),
    });

    return args.id;
  },
});

export const markSkipped = mutation({
  args: {
    id: v.id("reminderInstances"),
    notes: v.optional(v.string()),
  },
  returns: v.id("reminderInstances"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const instance = await ctx.db.get(args.id);
    if (instance?.userId !== userId) {
      throw new Error("Reminder instance not found");
    }

    await ctx.db.patch(args.id, {
      status: "skipped",
      updatedAt: Date.now(),
      ...(args.notes !== undefined ? { notes: args.notes } : {}),
    });

    return args.id;
  },
});

export const markMissed = mutation({
  args: {
    id: v.id("reminderInstances"),
  },
  returns: v.id("reminderInstances"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const instance = await ctx.db.get(args.id);
    if (instance?.userId !== userId) {
      throw new Error("Reminder instance not found");
    }

    await ctx.db.patch(args.id, {
      status: "missed",
      updatedAt: Date.now(),
    });

    return args.id;
  },
});

/**
 * Defers an instance and counts the deferral.
 *
 * The increment is server-side so concurrent snoozes from two devices both
 * land, and `snoozeCount` stays an honest measure of how many times the user
 * pushed this one occurrence back.
 */
export const snooze = mutation({
  args: {
    id: v.id("reminderInstances"),
    snoozedUntil: v.number(),
  },
  returns: v.number(),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const instance = await ctx.db.get(args.id);
    if (instance?.userId !== userId) {
      throw new Error("Reminder instance not found");
    }

    const snoozeCount = instance.snoozeCount + 1;
    await ctx.db.patch(args.id, {
      status: "snoozed",
      snoozedUntil: args.snoozedUntil,
      snoozeCount,
      updatedAt: Date.now(),
    });

    return snoozeCount;
  },
});
