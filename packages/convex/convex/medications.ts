import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";

/**
 * Medication queries and mutations.
 *
 * All functions enforce per-user access via `getCurrentUserId`. These are
 * currently stubbed and throw "not implemented"; the bodies will be filled in
 * during the sync implementation (Day 7).
 */

export const list = query({
  args: {},
  returns: v.array(
    v.object({
      _id: v.id("medications"),
      _creationTime: v.number(),
      userId: v.string(),
      name: v.string(),
      rxcui: v.optional(v.string()),
      dosage: v.optional(v.string()),
      scheduleType: v.union(v.literal("daily"), v.literal("interval")),
      scheduleTimes: v.any(),
      active: v.boolean(),
      createdAt: v.number(),
      updatedAt: v.number(),
      deletedAt: v.optional(v.number()),
    })
  ),
  handler: async (ctx) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const get = query({
  args: { id: v.id("medications") },
  returns: v.union(
    v.object({
      _id: v.id("medications"),
      _creationTime: v.number(),
      userId: v.string(),
      name: v.string(),
      rxcui: v.optional(v.string()),
      dosage: v.optional(v.string()),
      scheduleType: v.union(v.literal("daily"), v.literal("interval")),
      scheduleTimes: v.any(),
      active: v.boolean(),
      createdAt: v.number(),
      updatedAt: v.number(),
      deletedAt: v.optional(v.number()),
    }),
    v.null()
  ),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const create = mutation({
  args: {
    name: v.string(),
    rxcui: v.optional(v.string()),
    dosage: v.optional(v.string()),
    scheduleType: v.union(v.literal("daily"), v.literal("interval")),
    scheduleTimes: v.any(),
    active: v.boolean(),
  },
  returns: v.id("medications"),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
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
  },
  returns: v.id("medications"),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const softDelete = mutation({
  args: { id: v.id("medications") },
  returns: v.id("medications"),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});
