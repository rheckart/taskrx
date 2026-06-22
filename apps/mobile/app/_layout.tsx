import { ClerkProvider, useAuth, useUser } from "@clerk/clerk-expo";
import { tokenCache } from "@clerk/clerk-expo/token-cache";
import { ConvexProviderWithClerk } from "convex/react-clerk";
import { Slot, useRouter, useSegments } from "expo-router";
import { useEffect, useRef } from "react";
import type { ReactElement } from "react";
import { AppState, type AppStateStatus } from "react-native";

import { convex } from "../src/api/convex";
import { getDb } from "../src/db";
import {
  createNotificationChannel,
  registerBackgroundNotificationHandler,
  registerNotificationResponseHandler,
  rescheduleAll,
} from "../src/notifications";

registerBackgroundNotificationHandler();

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

/**
 * Creates the Android notification channel, registers the tap handler, and
 * refreshes the notification schedule whenever the app returns to the
 * foreground.
 */
function NotificationBootstrap(): null {
  const { user } = useUser();
  const router = useRouter();
  const unsubscribeRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    void createNotificationChannel().catch((error: unknown) => {
      console.error("[notifications] failed to create channel:", error);
    });

    unsubscribeRef.current = registerNotificationResponseHandler(
      router.navigate
    );

    return () => {
      unsubscribeRef.current?.();
      unsubscribeRef.current = null;
    };
  }, [router]);

  useEffect(() => {
    const userId = user?.id;
    if (userId === undefined) {
      return;
    }

    const handleAppStateChange = (nextAppState: AppStateStatus): void => {
      if (nextAppState === "active") {
        void rescheduleAll(userId).catch((error: unknown) => {
          console.error(
            "[notifications] reschedule on foreground failed:",
            error
          );
        });
      }
    };

    const subscription = AppState.addEventListener(
      "change",
      handleAppStateChange
    );

    void rescheduleAll(userId).catch((error: unknown) => {
      console.error("[notifications] initial reschedule failed:", error);
    });

    return () => {
      subscription.remove();
    };
  }, [user?.id]);

  return null;
}

export default function RootLayout(): ReactElement {
  return (
    <ClerkProvider publishableKey={publishableKey} tokenCache={clerkTokenCache}>
      <ConvexProviderWithClerk client={convex} useAuth={useAuth}>
        <DatabaseBootstrap />
        <NotificationBootstrap />
        <AuthRedirectGate />
      </ConvexProviderWithClerk>
    </ClerkProvider>
  );
}
