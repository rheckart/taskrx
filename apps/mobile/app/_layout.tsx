import { ClerkProvider, useAuth } from "@clerk/clerk-expo";
import { tokenCache } from "@clerk/clerk-expo/token-cache";
import { ConvexProviderWithClerk } from "convex/react-clerk";
import { Slot, useRouter, useSegments } from "expo-router";
import { useEffect } from "react";
import type { ReactElement } from "react";

import { convex } from "../src/api/convex";
import { getDb } from "../src/db";

const rawPublishableKey = process.env.EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY;

if (!rawPublishableKey) {
  throw new Error(
    "Missing EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY. Copy apps/mobile/.env.example to apps/mobile/.env and add your Clerk publishable key."
  );
}

const publishableKey = rawPublishableKey;

if (!tokenCache) {
  throw new Error(
    "Clerk token cache is unavailable. Ensure expo-secure-store is installed and you are running on a native platform."
  );
}

const clerkTokenCache = tokenCache;

function AuthRedirectGate(): ReactElement {
  const { isLoaded, isSignedIn } = useAuth();
  const segments = useSegments();
  const router = useRouter();

  useEffect(() => {
    if (!isLoaded) {
      return;
    }

    const currentSegment = segments[0] as string | undefined;
    const inAuthGroup = currentSegment === "(auth)";

    if (isSignedIn && inAuthGroup) {
      router.replace({ pathname: "/(app)" });
    } else if (!isSignedIn && !inAuthGroup) {
      router.replace({ pathname: "/(auth)/sign-in" });
    }
  }, [isLoaded, isSignedIn, segments, router]);

  return <Slot />;
}

/**
 * Opens the local SQLite database on mount so migrations run before any
 * screen tries to read from it. Errors are logged but do not block the
 * UI — the app can still render the auth flow even if the DB is broken.
 */
function DatabaseBootstrap(): null {
  useEffect(() => {
    void getDb().catch((error: unknown) => {
      console.error("[db] failed to initialize SQLite:", error);
    });
  }, []);
  return null;
}

export default function RootLayout(): ReactElement {
  return (
    <ClerkProvider publishableKey={publishableKey} tokenCache={clerkTokenCache}>
      <ConvexProviderWithClerk client={convex} useAuth={useAuth}>
        <DatabaseBootstrap />
        <AuthRedirectGate />
      </ConvexProviderWithClerk>
    </ClerkProvider>
  );
}
