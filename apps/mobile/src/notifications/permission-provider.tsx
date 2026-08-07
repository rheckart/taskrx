import { useUser } from "@clerk/clerk-expo";
import type { PermissionRemedy } from "@taskrx/shared";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import type { ReactElement, ReactNode } from "react";
import { AppState } from "react-native";

import {
  applyPermissionRemedy,
  syncNotificationPermission,
} from "./permission-gate";
import type { PermissionGateState } from "./permission-gate";

/**
 * Holds the notification-permission state for the signed-in tree, and is the
 * thing that finally asks.
 *
 * **Why it is mounted here and gated on a session.** Issue #20 asks for the
 * request to happen "at a point where the request makes sense to the person —
 * after sign-in ... not on cold start before there is anything to be reminded
 * about". A fresh install's cold start IS the sign-in screen, so a provider
 * that ran unconditionally would put an Android permission dialog in front of
 * someone who has not yet told the app who they are. Gating on `isSignedIn`
 * makes the first prompt land on the first screen that belongs to them.
 *
 * **Why it re-reads on every foreground.** The permission is revocable from
 * Settings at any moment, and a person who refused it and then changed their
 * mind must not have to reinstall. `AppState` "active" is the same trigger the
 * reschedule and the missed sweep already use, for the same reason: nothing in
 * this app runs while the process is asleep.
 *
 * **Why `asked` is a ref and not state.** It exists to stop the app asking
 * twice in one run, and a re-render must not reset it. It is deliberately NOT
 * persisted: across runs the platform's own answer is the authority, and a
 * stored "we already asked" would be a cached permission verdict — the exact
 * shape of the POC stub that `.omo/plans/convex-expo-revisit.md` todo 6
 * forbids.
 */

interface NotificationPermissionValue {
  readonly state: PermissionGateState | null;
  readonly resolve: (remedy: PermissionRemedy) => void;
}

const NotificationPermissionContext =
  createContext<NotificationPermissionValue | null>(null);

export function NotificationPermissionProvider({
  children,
}: {
  readonly children: ReactNode;
}): ReactElement {
  const { isSignedIn } = useUser();
  const [state, setState] = useState<PermissionGateState | null>(null);
  const asked = useRef<boolean>(false);

  const sync = useCallback((): void => {
    void syncNotificationPermission(asked.current, () => {
      // Recorded as the dialog goes up, not when it comes down. Dismissing an
      // Android permission dialog resumes the host activity, which fires
      // AppState "active" and re-enters this function while the request is
      // still awaiting — and a flag set only in the `then` below would still
      // read false there, putting the dialog straight back on screen.
      asked.current = true;
    })
      .then((next) => {
        asked.current = next.asked;
        setState(next);
      })
      .catch((error: unknown) => {
        // Logged and dropped, like the reschedule and the sweep beside it. A
        // failed read leaves the banner absent rather than claiming a state
        // nobody measured, and the next foreground runs it again.
        console.error("[notifications] permission check failed:", error);
      });
  }, []);

  useEffect(() => {
    if (isSignedIn !== true) {
      return;
    }

    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") {
        sync();
      }
    });

    sync();

    return () => {
      subscription.remove();
    };
  }, [isSignedIn, sync]);

  const resolve = useCallback((remedy: PermissionRemedy): void => {
    void applyPermissionRemedy(remedy)
      .then((next) => {
        asked.current = next.asked;
        setState(next);
      })
      .catch((error: unknown) => {
        console.error("[notifications] permission remedy failed:", error);
      });
  }, []);

  return (
    <NotificationPermissionContext.Provider value={{ state, resolve }}>
      {children}
    </NotificationPermissionContext.Provider>
  );
}

/**
 * Returns null outside the provider, which is a real case rather than an
 * oversight: `/harness` renders in the local-only tree where there is no
 * ClerkProvider and therefore no provider above it.
 */
export function useNotificationPermission(): NotificationPermissionValue | null {
  return useContext(NotificationPermissionContext);
}
