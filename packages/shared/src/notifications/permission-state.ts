/**
 * Pure exact-alarm permission derivation used by the mobile permissions
 * module.
 *
 * Kept in `@taskrx/shared` (rather than inline in `apps/mobile`) so it can be
 * unit-tested without a native module. The mobile adapter is a thin wrapper
 * around `notifee.getNotificationSettings().android.alarm`, which returns
 * numeric enum values matching the ones defined here.
 *
 * On Android < 12 the exact-alarm concept did not exist. notify-kit's docs
 * state that `alarm` defaults to a granted-equivalent state on those
 * versions, which is represented here by `NOT_SUPPORTED`.
 */

export const ANDROID_ALARM_NOT_SUPPORTED = -1;
export const ANDROID_ALARM_DISABLED = 0;
export const ANDROID_ALARM_ENABLED = 1;

export type AndroidAlarmSetting =
  | typeof ANDROID_ALARM_NOT_SUPPORTED
  | typeof ANDROID_ALARM_DISABLED
  | typeof ANDROID_ALARM_ENABLED;

export const NOTIFICATION_AUTH_NOT_DETERMINED = -1;
export const NOTIFICATION_AUTH_DENIED = 0;
export const NOTIFICATION_AUTH_AUTHORIZED = 1;
export const NOTIFICATION_AUTH_PROVISIONAL = 2;

export type NotificationAuthorization =
  | typeof NOTIFICATION_AUTH_NOT_DETERMINED
  | typeof NOTIFICATION_AUTH_DENIED
  | typeof NOTIFICATION_AUTH_AUTHORIZED
  | typeof NOTIFICATION_AUTH_PROVISIONAL;

export type PermissionStatus =
  | "authorized"
  | "denied"
  | "partial"
  | "undetermined";

export interface PermissionSnapshotInput {
  readonly notificationAuthorization: NotificationAuthorization;
  /** null on iOS or when the platform does not expose an alarm setting. */
  readonly androidAlarm: AndroidAlarmSetting | null;
}

export interface PermissionSnapshot {
  readonly status: PermissionStatus;
  readonly notificationGranted: boolean;
  readonly exactAlarmGranted: boolean;
}

/**
 * Returns true when the app is permitted to schedule exact alarms. On iOS
 * (`alarm === null`) and Android < 12 (`NOT_SUPPORTED`), this returns true
 * because the exact-alarm restriction does not apply. On Android 12+ only
 * `ENABLED` counts as granted.
 */
export function isExactAlarmGranted(
  alarm: AndroidAlarmSetting | null
): boolean {
  if (alarm === null) {
    return true;
  }
  return alarm !== ANDROID_ALARM_DISABLED;
}

/**
 * Derives the normalized permission state used by the app's UI from the raw
 * numeric values notify-kit returns. `partial` means the OS granted
 * notification permission but exact-alarm permission is denied — reminders
 * will still be delivered but timing may drift (notify-kit falls back to
 * `setAndAllowWhileIdle`).
 */
export function derivePermissionStatus(
  input: PermissionSnapshotInput
): PermissionSnapshot {
  const exactAlarmGranted = isExactAlarmGranted(input.androidAlarm);

  let status: PermissionStatus;
  switch (input.notificationAuthorization) {
    case NOTIFICATION_AUTH_AUTHORIZED:
    case NOTIFICATION_AUTH_PROVISIONAL:
      status = exactAlarmGranted ? "authorized" : "partial";
      break;
    case NOTIFICATION_AUTH_DENIED:
      status = "denied";
      break;
    case NOTIFICATION_AUTH_NOT_DETERMINED:
      status = "undetermined";
      break;
    default:
      status = exactAlarmGranted ? "authorized" : "partial";
  }

  const notificationGranted =
    input.notificationAuthorization === NOTIFICATION_AUTH_AUTHORIZED ||
    input.notificationAuthorization === NOTIFICATION_AUTH_PROVISIONAL;

  return { status, notificationGranted, exactAlarmGranted };
}
