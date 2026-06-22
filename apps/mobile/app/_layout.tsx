import { ClerkProvider, useAuth } from "@clerk/clerk-expo";
import { tokenCache } from "@clerk/clerk-expo/token-cache";
import { Slot, useRouter, useSegments } from "expo-router";
import { useEffect } from "react";
import type { ReactElement } from "react";

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

export default function RootLayout(): ReactElement {
  return (
    <ClerkProvider publishableKey={publishableKey} tokenCache={clerkTokenCache}>
      <AuthRedirectGate />
    </ClerkProvider>
  );
}
