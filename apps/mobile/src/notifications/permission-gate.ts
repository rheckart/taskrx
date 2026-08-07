import {
  assertNever,
  derivePermissionGap,
  shouldRequestNotificationPermission,
} from "@taskrx/shared";
import type {
  PermissionGap,
  PermissionRemedy,
  PermissionSnapshot,
  PermissionStatus,
} from "@taskrx/shared";

import {
  checkNotificationPermission,
  openExactAlarmSettings,
  openNotificationSettings,
  requestPermissions,
} from "./permissions";
import type { PermissionResult } from "./permissions";

/**
 * The one path in the app that asks whether it may notify anybody.
 *
 * Before issue #20 there was none. `requestPermissions()` was called from
 * `app/(app)/debug/notifications.tsx` and from nowhere else, so a person who
 * installed the app and never opened a debug screen never granted
 * POST_NOTIFICATIONS — and on Android 13+ that means the alarm fires exactly on
 * time, notify-kit runs, and the framework discards the notification with a
 * `SecurityException` nobody sees. No error, no empty state, no degraded-mode
 * warning: the person finds out by missing doses.
 *
 * Two properties are what make this a fix rather than a second place to get it
 * wrong:
 *
 * - **Every answer comes from a platform query.** There is no cached boolean
 *   here and no default — `checkNotificationPermission` reads
 *   `notifee.getNotificationSettings()` every time. The POC's hardcoded
 *   `checkExactAlarmPermission` returning `true` is the mistake this module is
 *   built not to repeat, and `.omo/plans/convex-expo-revisit.md` todo 6 forbids
 *   it in terms that apply here word for word.
 * - **A refusal is reported, not swallowed.** `syncNotificationPermission`
 *   always returns the gap, and the caller always renders it.
 *
 * The decisions themselves — when to prompt, what to say — are in
 * `@taskrx/shared`, where a test can run them. This file is the platform half:
 * the query, the prompt, and the route to the right Settings screen.
 */

export interface PermissionGateState {
  readonly status: PermissionStatus;
  readonly exactAlarmGranted: boolean;
  /**
   * Whether the OS prompt has been shown during this run of the app.
   *
   * Threaded through rather than stored here because a module-level flag would
   * outlive the screen that owns the state and is exactly the kind of hidden
   * cache the paragraph above rules out.
   */
  readonly asked: boolean;
  readonly gap: PermissionGap | null;
}

function snapshotOf(result: PermissionResult): PermissionSnapshot {
  return {
    status: result.status,
    notificationGranted: result.notificationGranted,
    exactAlarmGranted: result.exactAlarmGranted,
  };
}

function stateOf(
  result: PermissionResult,
  asked: boolean
): PermissionGateState {
  const snapshot = snapshotOf(result);
  return {
    status: snapshot.status,
    exactAlarmGranted: snapshot.exactAlarmGranted,
    asked,
    gap: derivePermissionGap({ snapshot, asked }),
  };
}

/**
 * Reads the current permission state, showing the OS prompt if this is the
 * first time and nothing has been decided yet.
 *
 * Safe to call on every transition to `active`, which is how it is used: the
 * prompt is shown at most once per run, and every later call is a re-read. That
 * re-read is what picks up a permission the person granted from Settings
 * without reinstalling anything.
 *
 * `onPrompt` fires IMMEDIATELY BEFORE the dialog is raised, and the caller uses
 * it to record that it has asked. That timing is the whole reason the callback
 * exists rather than the caller simply reading `asked` off the returned state.
 *
 * On Android the permission dialog is its own activity, so the host activity
 * pauses and resumes around it and React Native emits AppState `background` →
 * `active`. Dismissing the dialog with Back therefore delivers an `active`
 * event while `requestPermissions()` is still awaiting — and a caller that
 * learned it had asked only from the resolved result would still be holding
 * `asked === false` at that moment, call this again, and put the dialog
 * straight back on screen. A prompt somebody cannot get away from is worse than
 * the missing prompt this module was written to add.
 */
export async function syncNotificationPermission(
  asked: boolean,
  onPrompt?: () => void
): Promise<PermissionGateState> {
  const current = await checkNotificationPermission();

  if (
    !shouldRequestNotificationPermission({
      snapshot: snapshotOf(current),
      asked,
    })
  ) {
    return stateOf(current, asked);
  }

  onPrompt?.();
  return stateOf(await requestPermissions(), true);
}

/**
 * Acts on the button attached to a gap, then re-reads the platform.
 *
 * The re-read is the point of returning a state at all: two of the three
 * remedies hand the person off to a Settings screen and come back, and what
 * they come back to must be what the OS now holds.
 */
export async function applyPermissionRemedy(
  remedy: PermissionRemedy
): Promise<PermissionGateState> {
  switch (remedy) {
    case "request":
      return stateOf(await requestPermissions(), true);
    case "notification-settings":
      await openNotificationSettings();
      return stateOf(await checkNotificationPermission(), true);
    case "alarm-settings":
      await openExactAlarmSettings();
      return stateOf(await checkNotificationPermission(), true);
    default:
      return assertNever(remedy);
  }
}
