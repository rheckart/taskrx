import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";
import {
  noticeFields,
  noticeResolutionValidator,
  sourceKindValidator,
} from "./schema.js";

/**
 * Notices: outside messages a watched source admitted, kept as evidence rather than as
 * commitments.
 *
 * All functions enforce per-user access via `getCurrentUserId`. Reads are scoped by index
 * and bounded: a notice is history, and the screen shows the recent ones rather than
 * everything ever received.
 *
 * `localId` is the device's UUID, so a `create` for one it already sent is an upsert and the
 * sync worker can retry idempotently, exactly as `reminders.create`.
 */

const noticeDoc = v.object({
  _id: v.id("notices"),
  _creationTime: v.number(),
  ...noticeFields,
});

/**
 * The notices the person has, newest received first.
 *
 * `limit` bounds the read; the default is the screen's page. The server does not filter by
 * resolution because the client holds both lists (undecided and settled) from the same
 * subscription and switching between them should not need another round trip.
 */
export const list = query({
  args: { limit: v.optional(v.number()) },
  returns: v.array(noticeDoc),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const limit = Math.min(Math.max(args.limit ?? 200, 1), 500);
    return await ctx.db
      .query("notices")
      .withIndex("by_user_received", (q) => q.eq("userId", userId))
      .order("desc")
      .take(limit);
  },
});

export const getByLocalId = query({
  args: { localId: v.string() },
  returns: v.union(noticeDoc, v.null()),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const notice = await ctx.db
      .query("notices")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    return notice?.userId === userId ? notice : null;
  },
});

/**
 * Stores one notice, or applies the payload to the row with the same `localId`.
 *
 * A repeated create is an upsert for the same reason as reminders: an edit made while the
 * first create is still queued enqueues a second create. `resolution` is part of the payload
 * and is applied like every other field: the queue drains in order, so a resolution queued
 * after the create reaches the server after it. `sentence` and `externalId` are only written
 * when supplied - they are evidence, not a value the caller clears.
 */
export const create = mutation({
  args: {
    localId: v.string(),
    userId: v.string(),
    sourceKind: sourceKindValidator,
    sourceValue: v.string(),
    title: v.string(),
    sentence: v.optional(v.string()),
    receivedAt: v.number(),
    externalId: v.optional(v.string()),
    resolution: noticeResolutionValidator,
    resolvedAt: v.optional(v.number()),
    createdAt: v.number(),
    updatedAt: v.number(),
  },
  returns: v.id("notices"),
  handler: async (ctx, args) => {
    const currentUserId = await getCurrentUserId(ctx);
    if (args.userId !== currentUserId) {
      throw new Error("Cannot create a notice for another user");
    }

    const existing = await ctx.db
      .query("notices")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    if (existing !== null) {
      if (existing.userId !== currentUserId) {
        throw new Error("Notice localId already exists for another user");
      }
      await ctx.db.patch(existing._id, {
        sourceKind: args.sourceKind,
        sourceValue: args.sourceValue,
        title: args.title,
        receivedAt: args.receivedAt,
        resolution: args.resolution,
        updatedAt: args.updatedAt,
        ...(args.sentence !== undefined ? { sentence: args.sentence } : {}),
        ...(args.externalId !== undefined
          ? { externalId: args.externalId }
          : {}),
        ...(args.resolvedAt !== undefined
          ? { resolvedAt: args.resolvedAt }
          : {}),
      });
      return existing._id;
    }

    return await ctx.db.insert("notices", {
      userId: currentUserId,
      localId: args.localId,
      sourceKind: args.sourceKind,
      sourceValue: args.sourceValue,
      title: args.title,
      receivedAt: args.receivedAt,
      resolution: args.resolution,
      createdAt: args.createdAt,
      updatedAt: args.updatedAt,
      ...(args.sentence !== undefined ? { sentence: args.sentence } : {}),
      ...(args.externalId !== undefined ? { externalId: args.externalId } : {}),
      ...(args.resolvedAt !== undefined ? { resolvedAt: args.resolvedAt } : {}),
    });
  },
});

/** Resolves a notice: applied to a task, or dismissed. The sentence and source never change. */
export const update = mutation({
  args: {
    id: v.id("notices"),
    resolution: v.optional(noticeResolutionValidator),
    resolvedAt: v.optional(v.number()),
    updatedAt: v.optional(v.number()),
  },
  returns: v.id("notices"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Notice not found");
    }

    await ctx.db.patch(args.id, {
      updatedAt: args.updatedAt ?? Date.now(),
      ...(args.resolution !== undefined ? { resolution: args.resolution } : {}),
      ...(args.resolvedAt !== undefined ? { resolvedAt: args.resolvedAt } : {}),
    });
    return args.id;
  },
});

/** Removes a notice outright: the person asked for it not to be kept. */
export const remove = mutation({
  args: { id: v.id("notices") },
  returns: v.id("notices"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Notice not found");
    }
    await ctx.db.delete(args.id);
    return args.id;
  },
});
