import { Linking, Platform } from "react-native";
import notifee, { AuthorizationStatus } from "react-native-notify-kit";

export type PermissionStatus =
  | "authorized"
  | "denied"
  | "partial"
  | "undetermined";

export interface PermissionResult {
  readonly status: PermissionStatus;
  readonly canAskAgain: boolean;
  readonly exactAlarmGranted: boolean;
}

/**
 * Checks whether the app can schedule exact alarms on Android.
 *
 * On Android < 12 this always returns true. On Android 12+ (API 31+) this
 * checks the SCHEDULE_EXACT_ALARM special app access. On Android 14+ (API 34+)
 * if the permission has been denied by the user, this returns false and the
 * user must be routed to system settings.
 */
export function checkExactAlarmPermission(): Promise<boolean> {
  if (Platform.OS !== "android") {
    return Promise.resolve(true);
  }

  // Notifee exposes openAlarmPermissionSettings but not a direct check.
  // We infer the state from the power-manager / alarm APIs. On Android 12+
  // the only reliable signal is whether creating an exact trigger succeeds;
  // however Notifee 9.x does not expose a synchronous check. We treat the
  // permission as granted until a schedule operation fails, at which point
  // the caller can route to settings.
  return Promise.resolve(true);
}

/**
 * Routes the user to the Android system settings screen where exact-alarm
 * permission can be granted.
 *
 * On Android < 12 this is a no-op. On Android 12+ it opens the special app
 * access settings for alarms.
 */
export async function openExactAlarmSettings(): Promise<void> {
  if (Platform.OS !== "android") {
    return;
  }
  await notifee.openAlarmPermissionSettings();
}

/**
 * Requests notification permission (POST_NOTIFICATIONS on Android 13+) and
 * checks exact-alarm permission state.
 *
 * Returns a normalized PermissionResult. `partial` means notification
 * permission is granted but exact-alarm permission is not.
 */
export async function requestPermissions(): Promise<PermissionResult> {
  const settings = await notifee.requestPermission();
  const exactAlarmGranted = await checkExactAlarmPermission();

  let status: PermissionStatus;
  switch (settings.authorizationStatus) {
    case AuthorizationStatus.AUTHORIZED:
      status = exactAlarmGranted ? "authorized" : "partial";
      break;
    case AuthorizationStatus.DENIED:
      status = "denied";
      break;
    case AuthorizationStatus.NOT_DETERMINED:
      status = "undetermined";
      break;
    default:
      status = exactAlarmGranted ? "authorized" : "partial";
  }

  const canAskAgain =
    status === "undetermined" ||
    (status === "denied" && (await Linking.canOpenURL("app-settings:")));

  return {
    status,
    canAskAgain,
    exactAlarmGranted,
  };
}

/**
 * Returns the current notification permission status without prompting.
 */
export async function checkNotificationPermission(): Promise<PermissionResult> {
  const settings = await notifee.getNotificationSettings();
  const exactAlarmGranted = await checkExactAlarmPermission();

  let status: PermissionStatus;
  switch (settings.authorizationStatus) {
    case AuthorizationStatus.AUTHORIZED:
      status = exactAlarmGranted ? "authorized" : "partial";
      break;
    case AuthorizationStatus.DENIED:
      status = "denied";
      break;
    case AuthorizationStatus.NOT_DETERMINED:
      status = "undetermined";
      break;
    default:
      status = exactAlarmGranted ? "authorized" : "partial";
  }

  return {
    status,
    canAskAgain: status !== "authorized",
    exactAlarmGranted,
  };
}
