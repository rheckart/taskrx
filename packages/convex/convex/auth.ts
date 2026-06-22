import type { QueryCtx, MutationCtx, ActionCtx } from "./_generated/server.js";

/**
 * Extract the current Clerk user ID from the Convex auth context.
 *
 * Every public query, mutation, and action should call this helper first and
 * then filter reads/writes by the returned `userId`. This enforces per-user
 * data isolation.
 *
 * @throws {Error} "Not authenticated" when the request has no valid Clerk JWT.
 */
export async function getCurrentUserId(
  ctx: QueryCtx | MutationCtx | ActionCtx
): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (identity === null) {
    throw new Error("Not authenticated");
  }
  // The JWT `sub` claim is the Clerk user ID.
  return identity.subject;
}
