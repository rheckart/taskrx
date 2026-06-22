import { v } from "convex/values";

import { mutation, query } from "./_generated/server.js";
import { getCurrentUserId } from "./auth.js";

/**
 * Dose log queries and mutations.
 *
 * All functions enforce per-user access via `getCurrentUserId`. These are
 * currently stubbed and throw "not implemented"; the bodies will be filled in
 * during the sync implementation (Day 7).
 */

const doseLogValidator = v.object({
  _id: v.id("doseLogs"),
  _creationTime: v.number(),
  userId: v.string(),
  medicationId: v.id("medications"),
  scheduledTime: v.number(),
  takenAt: v.optional(v.number()),
  status: v.union(
    v.literal("pending"),
    v.literal("taken"),
    v.literal("skipped"),
    v.literal("missed")
  ),
  notes: v.optional(v.string()),
  createdAt: v.number(),
  updatedAt: v.number(),
});

export const list = query({
  args: {
    medicationId: v.optional(v.id("medications")),
  },
  returns: v.array(doseLogValidator),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const listForMedication = query({
  args: {
    medicationId: v.id("medications"),
  },
  returns: v.array(doseLogValidator),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const create = mutation({
  args: {
    medicationId: v.id("medications"),
    scheduledTime: v.number(),
    status: v.union(
      v.literal("pending"),
      v.literal("taken"),
      v.literal("skipped"),
      v.literal("missed")
    ),
    takenAt: v.optional(v.number()),
    notes: v.optional(v.string()),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const markTaken = mutation({
  args: {
    id: v.id("doseLogs"),
    takenAt: v.optional(v.number()),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const markSkipped = mutation({
  args: {
    id: v.id("doseLogs"),
    notes: v.optional(v.string()),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});

export const markMissed = mutation({
  args: {
    id: v.id("doseLogs"),
  },
  returns: v.id("doseLogs"),
  handler: async (ctx, _args) => {
    await getCurrentUserId(ctx);
    throw new Error("not implemented");
  },
});
