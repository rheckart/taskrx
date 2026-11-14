import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";
import { sourceKindValidator, watchSourceFields } from "./schema.js";

/**
 * Watched sources: the senders and apps whose notices may be kept at all.
 *
 * This is the gate on storage, not on proposals. A notice from an unwatched source is
 * dropped before it is written, which is why the list has to sync: the server-side mailbox
 * poll filters by it, and a phone that is not running cannot be asked.
 *
 * A share is never a watched source: the person sharing something is the approval.
 */

const watchSourceDoc = v.object({
  _id: v.id("watchSources"),
  _creationTime: v.number(),
  ...watchSourceFields,
});

export const list = query({
  args: {},
  returns: v.array(watchSourceDoc),
  handler: async (ctx) => {
    const userId = await getCurrentUserId(ctx);
    return await ctx.db
      .query("watchSources")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .take(500);
  },
});

export const getByLocalId = query({
  args: { localId: v.string() },
  returns: v.union(watchSourceDoc, v.null()),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const source = await ctx.db
      .query("watchSources")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    return source?.userId === userId ? source : null;
  },
});

/** Adds a watched source, or applies the payload to the row with the same `localId`. */
export const create = mutation({
  args: {
    localId: v.string(),
    userId: v.string(),
    kind: sourceKindValidator,
    value: v.string(),
    label: v.string(),
    createdAt: v.number(),
    updatedAt: v.number(),
  },
  returns: v.id("watchSources"),
  handler: async (ctx, args) => {
    const currentUserId = await getCurrentUserId(ctx);
    if (args.userId !== currentUserId) {
      throw new Error("Cannot create a watched source for another user");
    }
    // A share is the person's own act, not a standing permission.
    if (args.kind === "share") {
      throw new Error("A share cannot be a watched source");
    }

    const existing = await ctx.db
      .query("watchSources")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    if (existing !== null) {
      if (existing.userId !== currentUserId) {
        throw new Error(
          "Watched source localId already exists for another user"
        );
      }
      await ctx.db.patch(existing._id, {
        kind: args.kind,
        value: args.value,
        label: args.label,
        updatedAt: args.updatedAt,
      });
      return existing._id;
    }

    return await ctx.db.insert("watchSources", {
      userId: currentUserId,
      localId: args.localId,
      kind: args.kind,
      value: args.value,
      label: args.label,
      createdAt: args.createdAt,
      updatedAt: args.updatedAt,
    });
  },
});

/** Renames a watched source. The address or package is identity and is not rewritten. */
export const update = mutation({
  args: {
    id: v.id("watchSources"),
    label: v.optional(v.string()),
    updatedAt: v.optional(v.number()),
  },
  returns: v.id("watchSources"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Watched source not found");
    }
    await ctx.db.patch(args.id, {
      updatedAt: args.updatedAt ?? Date.now(),
      ...(args.label !== undefined ? { label: args.label } : {}),
    });
    return args.id;
  },
});

/** Stops watching a source. Notices already kept stay: they are evidence of what arrived. */
export const remove = mutation({
  args: { id: v.id("watchSources") },
  returns: v.id("watchSources"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Watched source not found");
    }
    await ctx.db.delete(args.id);
    return args.id;
  },
});
