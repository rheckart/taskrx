import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";

/**
 * Medication queries and mutations.
 *
 * All functions enforce per-user access via `getCurrentUserId`. Reads filter
 * out soft-deleted rows (`deletedAt` is undefined). Writes verify ownership
 * before mutating a document.
 *
 * The `localId` field stores the stable UUID generated on the device so the
 * sync worker can map local SQLite rows to Convex documents and retry creates
 * idempotently. This deviates from the original POC schema (see schema.ts).
 */

const medicationValidator = v.object({
  _id: v.id("medications"),
  _creationTime: v.number(),
  userId: v.string(),
  localId: v.string(),
  name: v.string(),
  rxcui: v.optional(v.string()),
  dosage: v.optional(v.string()),
  scheduleType: v.union(v.literal("daily"), v.literal("interval")),
  scheduleTimes: v.any(),
  active: v.boolean(),
  createdAt: v.number(),
  updatedAt: v.number(),
  deletedAt: v.optional(v.number()),
});

void medicationValidator;

export const list = query({
  args: {},
  handler: async (ctx) => {
    const userId = await getCurrentUserId(ctx);
    return await ctx.db
      .query("medications")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .filter((q) => q.eq(q.field("deletedAt"), undefined))
      .collect();
  },
});

export const get = query({
  args: { id: v.id("medications") },
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const med = await ctx.db.get(args.id);
    if (med?.userId !== userId) {
      return null;
    }
    return med;
  },
});

export const getByLocalId = query({
  args: { localId: v.string() },
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    return await ctx.db
      .query("medications")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .filter((q) => q.eq(q.field("userId"), userId))
      .first();
  },
});

export const create = mutation({
  args: {
    localId: v.string(),
    userId: v.string(),
    name: v.string(),
    rxcui: v.optional(v.string()),
    dosage: v.optional(v.string()),
    scheduleType: v.union(v.literal("daily"), v.literal("interval")),
    scheduleTimes: v.any(),
    active: v.boolean(),
    createdAt: v.number(),
    updatedAt: v.number(),
  },
  returns: v.id("medications"),
  handler: async (ctx, args) => {
    const currentUserId = await getCurrentUserId(ctx);
    if (args.userId !== currentUserId) {
      throw new Error("Cannot create medication for another user");
    }

    const existing = await ctx.db
      .query("medications")
      .withIndex("by_local_id", (q) => q.eq("localId", args.localId))
      .first();
    if (existing !== null) {
      if (existing.userId !== currentUserId) {
        throw new Error("Medication localId already exists for another user");
      }

      /**
       * A repeated `create` for the same `localId` is an upsert, not a no-op —
       * the same rule, and for the same reason, as `reminders.create`, which
       * documents it at length.
       *
       * In short: the device picks `create` over `update` by whether its local
       * row has a `convex_id` yet, so an edit made while the first create is
       * still queued enqueues a *second* create. Returning `existing._id`
       * without writing left the server permanently behind, because the client
       * then marks the row synced and never re-sends it.
       *
       * `localId`, `userId` and `createdAt` are identity, not payload, and are
       * never rewritten. The optionals keep the insert's conditional spreads,
       * so an omitted field is left alone rather than cleared — matching the
       * worker, which encodes a cleared field as an omission.
       */
      await ctx.db.patch(existing._id, {
        name: args.name,
        scheduleType: args.scheduleType,
        scheduleTimes: args.scheduleTimes as unknown,
        active: args.active,
        updatedAt: args.updatedAt,
        ...(args.rxcui !== undefined ? { rxcui: args.rxcui } : {}),
        ...(args.dosage !== undefined ? { dosage: args.dosage } : {}),
      });
      return existing._id;
    }

    return await ctx.db.insert("medications", {
      userId: currentUserId,
      localId: args.localId,
      name: args.name,
      scheduleType: args.scheduleType,
      scheduleTimes: args.scheduleTimes as unknown,
      active: args.active,
      createdAt: args.createdAt,
      updatedAt: args.updatedAt,
      ...(args.rxcui !== undefined ? { rxcui: args.rxcui } : {}),
      ...(args.dosage !== undefined ? { dosage: args.dosage } : {}),
    });
  },
});

export const update = mutation({
  args: {
    id: v.id("medications"),
    name: v.optional(v.string()),
    rxcui: v.optional(v.string()),
    dosage: v.optional(v.string()),
    scheduleType: v.optional(
      v.union(v.literal("daily"), v.literal("interval"))
    ),
    scheduleTimes: v.optional(v.any()),
    active: v.optional(v.boolean()),
    updatedAt: v.optional(v.number()),
  },
  returns: v.id("medications"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Medication not found");
    }

    const now = Date.now();
    await ctx.db.patch(args.id, {
      updatedAt: args.updatedAt ?? now,
      ...(args.name !== undefined ? { name: args.name } : {}),
      ...(args.rxcui !== undefined ? { rxcui: args.rxcui } : {}),
      ...(args.dosage !== undefined ? { dosage: args.dosage } : {}),
      ...(args.scheduleType !== undefined
        ? { scheduleType: args.scheduleType }
        : {}),
      ...(args.scheduleTimes !== undefined
        ? { scheduleTimes: args.scheduleTimes as unknown }
        : {}),
      ...(args.active !== undefined ? { active: args.active } : {}),
    });

    return args.id;
  },
});

export const softDelete = mutation({
  args: { id: v.id("medications") },
  returns: v.id("medications"),
  handler: async (ctx, args) => {
    const userId = await getCurrentUserId(ctx);
    const existing = await ctx.db.get(args.id);
    if (existing?.userId !== userId) {
      throw new Error("Medication not found");
    }

    await ctx.db.patch(args.id, {
      active: false,
      deletedAt: Date.now(),
      updatedAt: Date.now(),
    });

    return args.id;
  },
});
