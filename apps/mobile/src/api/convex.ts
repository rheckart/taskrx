import { useAuth } from "@clerk/clerk-expo";
import { ConvexReactClient } from "convex/react";

const convexUrl = process.env.EXPO_PUBLIC_CONVEX_URL as string | undefined;

if (!convexUrl) {
  throw new Error(
    "Missing EXPO_PUBLIC_CONVEX_URL. Copy apps/mobile/.env.example to apps/mobile/.env and add your Convex deployment URL."
  );
}

export const convex = new ConvexReactClient(convexUrl);

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
