import { tokenCache } from "@clerk/clerk-expo/token-cache";

import { isConvexConfigured } from "../api/convex";

/**
 * Whether this build mounts the authenticated tree, decided once at module
 * load.
 *
 * Extracted from `app/_layout.tsx` because a second caller now needs the same
 * answer for a reason that is easy to get wrong: `HarnessScreen` renders in
 * BOTH trees, and reading the signed-in subject means calling `useUser()`,
 * which THROWS when no `ClerkProvider` is above it. So the harness route has to
 * know which tree it is in before it calls the hook — and it has to agree with
 * the layout exactly, since a false positive takes down `/harness` in
 * local-only mode, which is where harness cases 1-7 run.
 *
 * That agreement is what this module is for. The condition lives here once
 * rather than being restated at each call site, and the shape is
 * "config-or-null" rather than a boolean beside a separately-exported key so
 * that the key cannot be read without having checked.
 */
const rawPublishableKey = process.env.EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY;

const publishableKey: string | undefined =
  rawPublishableKey !== undefined &&
  rawPublishableKey.length > 0 &&
  !rawPublishableKey.includes("replace_me")
    ? rawPublishableKey
    : undefined;

/**
 * Whether a usable Clerk publishable key is present. Diagnostics only — it
 * says nothing about whether the authenticated tree mounts, which also needs a
 * token cache and a Convex deployment. Use `authConfig` for that.
 */
export const isClerkKeyConfigured = publishableKey !== undefined;

/**
 * The values `ClerkProvider` needs, or `null` when the app must run local-only.
 *
 * Non-null is precisely the condition under which `RootLayout` mounts
 * `ClerkProvider`, so a component may read `authConfig !== null` as "a Clerk
 * hook is safe to call here".
 */
export const authConfig =
  publishableKey !== undefined && tokenCache !== undefined && isConvexConfigured
    ? ({ publishableKey, tokenCache } as const)
    : null;
