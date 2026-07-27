import { ClerkProvider, useAuth, useUser } from "@clerk/clerk-expo";
import { tokenCache } from "@clerk/clerk-expo/token-cache";
import { ConvexProviderWithClerk } from "convex/react-clerk";
import { Redirect, Slot, useRouter, useSegments } from "expo-router";
import { Component, useEffect, useRef } from "react";
import type { ErrorInfo, ReactElement, ReactNode } from "react";
import {
  AppState,
  LogBox,
  StyleSheet,
  Text,
  View,
  type AppStateStatus,
} from "react-native";

import { convex, isConvexConfigured } from "../src/api/convex";
import { getDb } from "../src/db";
import {
  createNotificationChannel,
  registerNotificationResponseHandler,
  rescheduleAll,
} from "../src/notifications";
import { syncWorker } from "../src/sync/worker";

const rawPublishableKey = process.env.EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY;

// Kept as `string | undefined` rather than a boolean flag so TypeScript can
// narrow it to string in RootLayout's configured branch without an assertion.
const clerkPublishableKey: string | undefined =
  rawPublishableKey !== undefined &&
  rawPublishableKey.length > 0 &&
  !rawPublishableKey.includes("replace_me")
    ? rawPublishableKey
    : undefined;

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
    // Security carve-out: /harness is invoked only by the adb test harness
    // and performs local-only notification operations, so it must bypass
    // the auth redirect to run against a fresh install.
    if (currentSegment === "harness") {
      return;
    }

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

/**
 * Creates the notification channel in local-only mode. Mirrors
 * NotificationBootstrap's channel setup without the Clerk-dependent
 * rescheduling, so the harness can schedule against a real channel.
 */
function LocalNotificationBootstrap(): null {
  useEffect(() => {
    void createNotificationChannel().catch((error: unknown) => {
      console.error("[notifications] failed to create channel:", error);
    });
  }, []);
  return null;
}

/**
 * Starts the push sync worker when a user is signed in and stops it on
 * sign-out or unmount. The worker drains the local sync_queue to Convex.
 */
function SyncBootstrap(): null {
  const { isSignedIn } = useUser();

  useEffect(() => {
    if (!isSignedIn) {
      syncWorker.stop();
      return;
    }

    syncWorker.start();
    return () => {
      syncWorker.stop();
    };
  }, [isSignedIn]);

  return null;
}

interface RouteErrorBoundaryState {
  readonly message: string | null;
}

/**
 * Prevents a single failing route from escalating into a dev-client error
 * screen that swallows all subsequent navigation. Without this, a route that
 * depends on a provider absent in local-only mode (e.g. any `useUser()` call
 * with no ClerkProvider) takes down the whole router, making even
 * provider-free routes such as `/harness` unreachable.
 */
class RouteErrorBoundary extends Component<
  { readonly children: ReactNode },
  RouteErrorBoundaryState
> {
  constructor(props: { readonly children: ReactNode }) {
    super(props);
    this.state = { message: null };
  }

  static getDerivedStateFromError(error: unknown): RouteErrorBoundaryState {
    return {
      message: error instanceof Error ? error.message : String(error),
    };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo): void {
    console.warn(
      `[route-error] ${error instanceof Error ? error.message : String(error)}`,
      info.componentStack
    );
  }

  override render(): ReactNode {
    const { message } = this.state;
    if (message !== null) {
      return (
        <View style={styles.errorContainer}>
          <Text style={styles.errorTitle}>Route unavailable</Text>
          <Text style={styles.errorBody}>{message}</Text>
          <Text style={styles.errorHint}>
            Running in local-only mode. Copy apps/mobile/.env.example to
            apps/mobile/.env to enable auth and cloud sync.
          </Text>
        </View>
      );
    }
    return this.props.children;
  }
}

/**
 * Local-only tree, mounted when Clerk/Convex are unconfigured.
 *
 * Redirects to `/harness` rather than rendering the normal routes, because
 * those depend on ClerkProvider and would throw. In a dev build such a throw
 * surfaces as a full-screen LogBox overlay that intercepts navigation,
 * making the harness unreachable — hence both the redirect and the LogBox
 * suppression below.
 */
function LocalOnlyTree(): ReactElement {
  const segments = useSegments();
  const inHarness = segments[0] === "harness";

  useEffect(() => {
    LogBox.ignoreAllLogs(true);
  }, []);

  return (
    <>
      <DatabaseBootstrap />
      <LocalNotificationBootstrap />
      {inHarness ? (
        <RouteErrorBoundary>
          <Slot />
        </RouteErrorBoundary>
      ) : (
        <Redirect href="/harness" />
      )}
    </>
  );
}

export default function RootLayout(): ReactElement {
  if (
    clerkPublishableKey === undefined ||
    tokenCache === undefined ||
    !isConvexConfigured
  ) {
    console.warn(
      `[config] running local-only: clerk=${String(clerkPublishableKey !== undefined)} convex=${String(isConvexConfigured)}. ` +
        "Copy apps/mobile/.env.example to apps/mobile/.env to enable auth and cloud sync. " +
        "Redirecting to /harness; SQLite and local notifications still work."
    );
    return <LocalOnlyTree />;
  }

  return (
    <ClerkProvider publishableKey={clerkPublishableKey} tokenCache={tokenCache}>
      <ConvexProviderWithClerk client={convex} useAuth={useAuth}>
        <DatabaseBootstrap />
        <NotificationBootstrap />
        <SyncBootstrap />
        <AuthRedirectGate />
      </ConvexProviderWithClerk>
    </ClerkProvider>
  );
}

const styles = StyleSheet.create({
  errorContainer: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 24,
    gap: 12,
  },
  errorTitle: {
    fontSize: 18,
    fontWeight: "600",
  },
  errorBody: {
    fontSize: 14,
    textAlign: "center",
  },
  errorHint: {
    fontSize: 12,
    opacity: 0.7,
    textAlign: "center",
  },
});
