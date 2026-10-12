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
 * ownership of the parent reminder before writing a child that points at it.
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
 * `completed` is terminal: no later write may move an instance out of it.
 *
 * This is the server half of the rule `shouldApplyRemoteReminderInstance`
 * enforces on the device (`archive/expo-rn:packages/shared/src/sync/conflict.ts`), and what
 * story 44 of `docs/specs/fused-timeline.md` asks for — "a taken dose stays
 * taken". The device guard protects only the device that recorded the taking.
 * Without this one, a second device draining a queue it filled before it
 * learned of the completion patches the server copy instead: a sweep verdict of
 * `missed`, a `skipped`, or a `snooze` all land unconditionally. Any third
 * install then pulls that status for a dose that was actually taken, and its
 * own local guard cannot help — a fresh install has no local row to protect.
 *
 * Refusing is a silent no-op rather than a throw. The caller is the sync worker
 * draining its queue, where a stale entry is routine rather than exceptional;
 * throwing would retry it to the dead-letter threshold in `worker.ts` and raise
 * a data-loss warning for a write the server was right to ignore.
 */
function isCompleted(instance: { status: string }): boolean {
  return instance.status === "completed";
}

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
    completedAt: v.optional(v.number()),
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
       * whenever its local row still has no `convex_id`, so an instance completed
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
      /**
       * `completed` is terminal on this path too — see `isCompleted`. Unlike
       * the three `mark*` mutations this is defence in depth rather than a live
       * hole: a replayed `create` carries the newest status its device holds and
       * the queue drains in order, so a stale one landing after a completion
       * needs a reordering the worker does not produce. Guarded anyway, so the
       * rule is a property of the table rather than of three functions.
       *
       * `completedAt` is pinned with it. A replay of the completion itself
       * carries the same instant, and a stale payload has no business moving
       * when the dose was taken.
       */
      const settled = isCompleted(existing);

      await ctx.db.patch(existing._id, {
        reminderId: args.reminderId,
        reminderKind: reminder.kind,
        scheduledTime: args.scheduledTime,
        status: settled ? "completed" : (args.status ?? "pending"),
        updatedAt: args.updatedAt,
        /**
         * Spread like its optional siblings, not defaulted like `status`.
         *
         * On insert `?? 0` is the correct seed for a field the row does not
         * have yet. On patch it is a reset: an omitted `snoozeCount` would
         * overwrite a count the server had already accumulated — including one
         * `markSnoozed` incremented between this row's first create and the
         * duplicate create that lands here. The device's pull path is a live
         * source of such payloads, since it projects dose logs, which have no
         * snooze column to send.
         *
         * `status` is genuinely different: it is the field this branch exists
         * to stop dropping, so defaulting it to `pending` is the intent.
         */
        ...(args.snoozeCount !== undefined
          ? { snoozeCount: args.snoozeCount }
          : {}),
        ...(!settled && args.completedAt !== undefined
          ? { completedAt: args.completedAt }
          : {}),
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
      ...(args.completedAt !== undefined
        ? { completedAt: args.completedAt }
        : {}),
      ...(args.snoozedUntil !== undefined
        ? { snoozedUntil: args.snoozedUntil }
        : {}),
      ...(args.notes !== undefined ? { notes: args.notes } : {}),
    });
  },
});

/**
 * Named for the status it writes, not for medication. A task completes through
 * this same mutation; ADR 0001 is why there is only one.
 */
export const markCompleted = mutation({
  args: {
    id: v.id("reminderInstances"),
    completedAt: v.optional(v.number()),
  },
  returns: v.id("reminderInstances"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const instance = await ctx.db.get(args.id);
    if (instance?.userId !== userId) {
      throw new Error("Reminder instance not found");
    }

    await ctx.db.patch(args.id, {
      status: "completed",
      completedAt: args.completedAt ?? Date.now(),
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
    if (isCompleted(instance)) {
      return args.id;
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
    if (isCompleted(instance)) {
      return args.id;
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
    if (isCompleted(instance)) {
      return instance.snoozeCount;
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
