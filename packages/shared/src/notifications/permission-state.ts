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
  const notificationGranted =
    input.notificationAuthorization === NOTIFICATION_AUTH_AUTHORIZED ||
    input.notificationAuthorization === NOTIFICATION_AUTH_PROVISIONAL;

  // Written as a chain rather than a `switch` with a `default` arm, because the
  // arm this replaces was both unreachable and wrong in the same direction the
  // POC's hardcoded `true` was: it read an authorization value outside the
  // union as authorized. A value nobody recognises means the app does not know
  // whether it may notify, and "does not know" is `undetermined` — which asks,
  // where the old answer would have gone on believing it had permission.
  let status: PermissionStatus;
  if (notificationGranted) {
    status = exactAlarmGranted ? "authorized" : "partial";
  } else if (input.notificationAuthorization === NOTIFICATION_AUTH_DENIED) {
    status = "denied";
  } else {
    status = "undetermined";
  }

  return { status, notificationGranted, exactAlarmGranted };
}

/**
 * What the app can do about a permission the person has not granted.
 *
 * `request` is the OS prompt; the other two are Settings screens, because once
 * a runtime permission has been refused the prompt is spent — Android 13+
 * answers a repeat request without showing anything.
 */
export type PermissionRemedy =
  | "request"
  | "notification-settings"
  | "alarm-settings";

/**
 * `blocking` means no reminder reaches the person at all. `degraded` means they
 * arrive, late.
 *
 * The distinction is the whole reason this is two words rather than a boolean:
 * an app that describes a late reminder in the same terms as a missing one
 * teaches people to read past the message that matters.
 */
export type PermissionSeverity = "blocking" | "degraded";

export interface PermissionGap {
  readonly severity: PermissionSeverity;
  readonly title: string;
  readonly body: string;
  readonly remedy: PermissionRemedy;
  readonly remedyLabel: string;
}

export interface PermissionGateInput {
  readonly snapshot: PermissionSnapshot;
  /**
   * Whether this run of the app has already put the OS prompt in front of the
   * person.
   *
   * Not derivable from the snapshot, and that is the point: `undetermined`
   * before the prompt and `undetermined` after a dismissed prompt are the same
   * platform state and call for opposite behaviour — ask, then stop asking.
   */
  readonly asked: boolean;
}

/**
 * Whether to show the OS notification prompt now.
 *
 * True in exactly one state: nothing has been decided and nothing has been
 * asked. Every other answer is either already settled or no longer askable —
 * a refused runtime permission cannot be re-prompted on Android 13+, so a
 * second request would return "denied" without the person seeing anything, and
 * an app that read that as a fresh refusal would be inventing an answer nobody
 * gave.
 */
export function shouldRequestNotificationPermission(
  input: PermissionGateInput
): boolean {
  return input.snapshot.status === "undetermined" && !input.asked;
}

/**
 * What to tell the person, when the permissions they hold mean a reminder
 * cannot arrive on time — or `null` when nothing needs saying.
 *
 * The ordering is severity-first: a missing POST_NOTIFICATIONS makes every
 * reminder vanish, so it is named before a missing exact alarm, which only
 * makes them late. Issue #20 exists because the first of those was happening
 * to every fresh install with no error, no empty state and no warning; a gap
 * this returns is a gap somebody can see and act on.
 */
export function derivePermissionGap(
  input: PermissionGateInput
): PermissionGap | null {
  const { snapshot, asked } = input;

  if (snapshot.status === "denied") {
    return {
      severity: "blocking",
      title: "Reminders are turned off",
      // No operating system is named here, and that is a rule rather than a
      // style choice: `@taskrx/shared` is the half of this app that must stay
      // portable, and `.omo/plans/convex-expo-revisit.md` keeps Android-only
      // concerns behind the `src/notifications/` boundary precisely so an iOS
      // client can be added alongside rather than by surgery. This string is
      // reachable on iOS today — `permissions.ts` has iOS branches — and
      // "Android is discarding your reminders" would be false there.
      body: "Notifications are switched off for this app, so every reminder it posts is discarded. Doses and tasks are still scheduled, and none of them will reach you.",
      remedy: "notification-settings",
      remedyLabel: "Open notification settings",
    };
  }

  if (snapshot.status === "undetermined") {
    // Before the prompt this is not a gap, it is a question about to be asked.
    if (!asked) {
      return null;
    }
    return {
      severity: "blocking",
      title: "Reminders are turned off",
      body: "Notifications have not been allowed yet, so nothing this app schedules will reach you.",
      remedy: "request",
      remedyLabel: "Allow notifications",
    };
  }

  if (!snapshot.exactAlarmGranted) {
    return {
      severity: "degraded",
      title: "Reminders may arrive late",
      body: "Exact alarms are turned off for this app, so a reminder can be held back by the system until the phone is next awake. It will still arrive.",
      remedy: "alarm-settings",
      remedyLabel: "Open alarm settings",
    };
  }

  return null;
}
