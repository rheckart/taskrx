import {
  ANDROID_ALARM_DISABLED,
  ANDROID_ALARM_ENABLED,
  ANDROID_ALARM_NOT_SUPPORTED,
  derivePermissionStatus,
  isExactAlarmGranted,
  type AndroidAlarmSetting,
  type NotificationAuthorization,
  type PermissionStatus,
} from "@taskrx/shared";
import { Linking, Platform } from "react-native";
import notifee, { AuthorizationStatus } from "react-native-notify-kit";

export type { PermissionStatus } from "@taskrx/shared";

export interface PermissionResult {
  readonly status: PermissionStatus;
  readonly canAskAgain: boolean;
  readonly exactAlarmGranted: boolean;
}

/**
 * Maps notify-kit's numeric AuthorizationStatus to our shared numeric enum.
 * notify-kit's enum values are 0/1/2/-1; this mapping guards against future
 * upstream drift and satisfies @typescript-eslint/no-unsafe-enum-comparison.
 */
function toNotificationAuthorization(
  status: AuthorizationStatus
): NotificationAuthorization {
  switch (status) {
    case AuthorizationStatus.AUTHORIZED:
      return 1;
    case AuthorizationStatus.PROVISIONAL:
      return 2;
    case AuthorizationStatus.DENIED:
      return 0;
    case AuthorizationStatus.NOT_DETERMINED:
    default:
      return -1;
  }
}

/**
 * Maps notify-kit's AndroidNotificationSetting numeric values (-1/0/1) to
 * the shared enum. Returns null on non-Android platforms because the exact-
 * alarm restriction is Android-specific.
 */
function toAndroidAlarm(alarm: number | undefined): AndroidAlarmSetting | null {
  if (Platform.OS !== "android" || alarm === undefined) {
    return null;
  }
  if (alarm === ANDROID_ALARM_ENABLED) {
    return ANDROID_ALARM_ENABLED;
  }
  if (alarm === ANDROID_ALARM_DISABLED) {
    return ANDROID_ALARM_DISABLED;
  }
  return ANDROID_ALARM_NOT_SUPPORTED;
}

/**
 * Returns whether the app currently holds SCHEDULE_EXACT_ALARM special
 * access. On iOS this returns true unconditionally; on Android < 12 it
 * returns true because the exact-alarm concept does not exist; on Android
 * 12+ it returns whether the user has granted the permission.
 *
 * Replaces the earlier POC stub which hardcoded `true` on every platform
 * (see `docs/architecture.md` and the plan in
 * `.omo/plans/convex-expo-revisit.md` todo 6). The stub existed because
 * upstream Notifee 9.x did not correctly report state before the user was
 * prompted; the notify-kit fork fixes that (upstream issue #1237), so the
 * real check is now trustworthy.
 */
export async function checkExactAlarmPermission(): Promise<boolean> {
  if (Platform.OS !== "android") {
    return true;
  }
  const settings = await notifee.getNotificationSettings();
  return isExactAlarmGranted(toAndroidAlarm(settings.android.alarm));
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

async function buildResult(
  settings: Awaited<ReturnType<typeof notifee.getNotificationSettings>>
): Promise<PermissionResult> {
  const snapshot = derivePermissionStatus({
    notificationAuthorization: toNotificationAuthorization(
      settings.authorizationStatus
    ),
    androidAlarm: toAndroidAlarm(settings.android.alarm),
  });

  const canAskAgain =
    snapshot.status === "undetermined" ||
    (snapshot.status === "denied" &&
      (await Linking.canOpenURL("app-settings:")));

  return {
    status: snapshot.status,
    canAskAgain,
    exactAlarmGranted: snapshot.exactAlarmGranted,
  };
}

/**
 * Requests notification permission (POST_NOTIFICATIONS on Android 13+) and
 * checks exact-alarm permission state.
 *
 * Returns a normalized PermissionResult. `partial` means notification
 * permission is granted but exact-alarm permission is not; alarms will
 * still be delivered (notify-kit falls back to setAndAllowWhileIdle) but
 * exact timing is not guaranteed.
 */
export async function requestPermissions(): Promise<PermissionResult> {
  await notifee.requestPermission();
  const settings = await notifee.getNotificationSettings();
  return buildResult(settings);
}

/**
 * Returns the current notification permission status without prompting.
 */
export async function checkNotificationPermission(): Promise<PermissionResult> {
  const settings = await notifee.getNotificationSettings();
  return buildResult(settings);
}
