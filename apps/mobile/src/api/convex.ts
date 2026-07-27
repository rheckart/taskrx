import { useAuth } from "@clerk/clerk-expo";
import { ConvexReactClient } from "convex/react";

const convexUrl = process.env.EXPO_PUBLIC_CONVEX_URL;

const PLACEHOLDER_CONVEX_URL = "https://unconfigured.convex.cloud";

/**
 * Whether a real Convex deployment URL is configured.
 *
 * When false the app runs in local-only mode: SQLite reads/writes and local
 * notification scheduling still work, but nothing syncs to the cloud. Callers
 * that perform cloud work must gate on this.
 */
export const isConvexConfigured =
  convexUrl !== undefined &&
  convexUrl.length > 0 &&
  !convexUrl.includes("replace_me");

/**
 * Convex client, always constructed so that a missing deployment URL cannot
 * throw at module load. A module-scope throw here would take down the root
 * layout and therefore every route — including purely-local ones like
 * `/harness`, which need neither Convex nor Clerk. Guard cloud work behind
 * `isConvexConfigured` instead.
 */
export const convex = new ConvexReactClient(
  isConvexConfigured && convexUrl !== undefined
    ? convexUrl
    : PLACEHOLDER_CONVEX_URL
);

interface ClerkConvexAuth {
  readonly getToken: (options: {
    readonly template?: string;
  }) => Promise<string | null>;
  readonly isAuthenticated: boolean;
  readonly isLoaded: boolean;
}

export function useClerkConvexAuth(): ClerkConvexAuth {
  const { getToken, isLoaded, isSignedIn } = useAuth();

  return {
    getToken: async ({ template }: { readonly template?: string }) => {
      const options = template === undefined ? undefined : { template };
      return getToken(options);
    },
    isAuthenticated: Boolean(isSignedIn),
    isLoaded,
  };
}
