import { describe, expect, it } from "vitest";

import {
  ANDROID_ALARM_DISABLED,
  ANDROID_ALARM_ENABLED,
  ANDROID_ALARM_NOT_SUPPORTED,
  NOTIFICATION_AUTH_AUTHORIZED,
  NOTIFICATION_AUTH_DENIED,
  NOTIFICATION_AUTH_NOT_DETERMINED,
  NOTIFICATION_AUTH_PROVISIONAL,
  derivePermissionGap,
  derivePermissionStatus,
  isExactAlarmGranted,
  shouldRequestNotificationPermission,
} from "./permission-state";
import type { PermissionSnapshot } from "./permission-state";

describe("isExactAlarmGranted", () => {
  it("returns true on iOS (alarm === null)", () => {
    expect(isExactAlarmGranted(null)).toBe(true);
  });

  it("returns true on Android < 12 (NOT_SUPPORTED)", () => {
    expect(isExactAlarmGranted(ANDROID_ALARM_NOT_SUPPORTED)).toBe(true);
  });

  it("returns true when the user has enabled the permission", () => {
    expect(isExactAlarmGranted(ANDROID_ALARM_ENABLED)).toBe(true);
  });

  it("returns false when the user has denied the permission", () => {
    expect(isExactAlarmGranted(ANDROID_ALARM_DISABLED)).toBe(false);
  });
});

describe("derivePermissionStatus", () => {
  it("authorized: notifications granted + alarm enabled", () => {
    expect(
      derivePermissionStatus({
        notificationAuthorization: NOTIFICATION_AUTH_AUTHORIZED,
        androidAlarm: ANDROID_ALARM_ENABLED,
      })
    ).toEqual({
      status: "authorized",
      notificationGranted: true,
      exactAlarmGranted: true,
    });
  });

  it("authorized on iOS (alarm null) with notifications granted", () => {
    expect(
      derivePermissionStatus({
        notificationAuthorization: NOTIFICATION_AUTH_AUTHORIZED,
        androidAlarm: null,
      })
    ).toEqual({
      status: "authorized",
      notificationGranted: true,
      exactAlarmGranted: true,
    });
  });

  it("partial: notifications granted but exact-alarm denied", () => {
    expect(
      derivePermissionStatus({
        notificationAuthorization: NOTIFICATION_AUTH_AUTHORIZED,
        androidAlarm: ANDROID_ALARM_DISABLED,
      })
    ).toEqual({
      status: "partial",
      notificationGranted: true,
      exactAlarmGranted: false,
    });
  });

  it("partial: provisional notifications + alarm denied", () => {
    expect(
      derivePermissionStatus({
        notificationAuthorization: NOTIFICATION_AUTH_PROVISIONAL,
        androidAlarm: ANDROID_ALARM_DISABLED,
      })
    ).toEqual({
      status: "partial",
      notificationGranted: true,
      exactAlarmGranted: false,
    });
  });

  it("denied: notifications explicitly denied (regardless of alarm state)", () => {
    expect(
      derivePermissionStatus({
        notificationAuthorization: NOTIFICATION_AUTH_DENIED,
        androidAlarm: ANDROID_ALARM_ENABLED,
      }).status
    ).toBe("denied");
  });

  it("undetermined: notifications not yet decided (fix for upstream #1237)", () => {
    // Notify-kit's fix ensures this state is reported correctly before the
    // user has been prompted, rather than the old Notifee behaviour of
    // returning DENIED on Android 13+.
    expect(
      derivePermissionStatus({
        notificationAuthorization: NOTIFICATION_AUTH_NOT_DETERMINED,
        androidAlarm: ANDROID_ALARM_ENABLED,
      }).status
    ).toBe("undetermined");
  });

  it("Android < 12 with granted notifications is authorized (NOT_SUPPORTED alarm)", () => {
    expect(
      derivePermissionStatus({
        notificationAuthorization: NOTIFICATION_AUTH_AUTHORIZED,
        androidAlarm: ANDROID_ALARM_NOT_SUPPORTED,
      }).status
    ).toBe("authorized");
  });
});

/**
 * The two decisions issue #20 turns on: whether to put the OS prompt in front
 * of someone, and what to tell them when the answer leaves reminders unable to
 * arrive.
 *
 * Both are here rather than in `apps/mobile` because both are pure functions of
 * a snapshot, and the mobile side has no runner that can render a React tree —
 * a rule that lives in a component is a rule nothing executes.
 */
function snapshot(
  status: PermissionSnapshot["status"],
  exactAlarmGranted = true
): PermissionSnapshot {
  return {
    status,
    notificationGranted: status === "authorized" || status === "partial",
    exactAlarmGranted,
  };
}

describe("shouldRequestNotificationPermission", () => {
  it("asks when the person has never been asked", () => {
    expect(
      shouldRequestNotificationPermission({
        snapshot: snapshot("undetermined"),
        asked: false,
      })
    ).toBe(true);
  });

  it("does not ask twice in one session", () => {
    // Android leaves the state undetermined when the dialog is dismissed
    // without an answer, so this is reachable, and a re-ask on every return to
    // the foreground would be a dialog someone cannot get away from.
    expect(
      shouldRequestNotificationPermission({
        snapshot: snapshot("undetermined"),
        asked: true,
      })
    ).toBe(false);
  });

  it("does not ask again after a denial", () => {
    // Android 13+ answers a repeat request for a denied permission without
    // showing anything, so asking would look to the app like a fresh refusal
    // and to the person like nothing at all. Settings is the only route left.
    expect(
      shouldRequestNotificationPermission({
        snapshot: snapshot("denied"),
        asked: false,
      })
    ).toBe(false);
  });

  it("does not ask when notifications are already granted", () => {
    expect(
      shouldRequestNotificationPermission({
        snapshot: snapshot("authorized"),
        asked: false,
      })
    ).toBe(false);
  });

  it("does not ask when only the exact-alarm half is missing", () => {
    // `partial` means POST_NOTIFICATIONS is granted. The gap is real and is
    // reported below, but it is not a thing this prompt can fix.
    expect(
      shouldRequestNotificationPermission({
        snapshot: snapshot("partial", false),
        asked: false,
      })
    ).toBe(false);
  });
});

describe("derivePermissionGap", () => {
  it("reports nothing when reminders can arrive on time", () => {
    expect(
      derivePermissionGap({ snapshot: snapshot("authorized"), asked: true })
    ).toBeNull();
  });

  it("stays quiet until the prompt has actually been shown", () => {
    // The one moment a gap is real and must not be rendered: the app is about
    // to ask. A banner here would be shouting at someone a half-second before
    // the dialog they are going to say yes to.
    expect(
      derivePermissionGap({ snapshot: snapshot("undetermined"), asked: false })
    ).toBeNull();
  });

  it("reports a blocking gap once the prompt has been shown and answered with nothing", () => {
    const gap = derivePermissionGap({
      snapshot: snapshot("undetermined"),
      asked: true,
    });
    expect(gap?.severity).toBe("blocking");
    expect(gap?.remedy).toBe("request");
  });

  it("sends a denial to the notification settings, not back to the prompt", () => {
    const gap = derivePermissionGap({
      snapshot: snapshot("denied"),
      asked: true,
    });
    expect(gap?.severity).toBe("blocking");
    expect(gap?.remedy).toBe("notification-settings");
  });

  it("reports a denial even when the app has not asked yet", () => {
    // Revoked from Settings between two foregrounds — the app never asked in
    // this session and the reminders are dropped all the same.
    expect(
      derivePermissionGap({ snapshot: snapshot("denied"), asked: false })
        ?.severity
    ).toBe("blocking");
  });

  it("calls a missing exact alarm degraded rather than blocking", () => {
    // Reminders still arrive; they arrive late. Saying "reminders cannot be
    // shown" here would be false, and a person who has learned that the app
    // cries wolf reads past the message that means it.
    const gap = derivePermissionGap({
      snapshot: snapshot("partial", false),
      asked: true,
    });
    expect(gap?.severity).toBe("degraded");
    expect(gap?.remedy).toBe("alarm-settings");
  });

  it("names the notification gap first when both halves are missing", () => {
    // A denial makes every reminder vanish; a missing exact alarm makes it
    // late. Offering the alarm-settings route first would send someone to fix
    // the smaller of two problems.
    expect(
      derivePermissionGap({ snapshot: snapshot("denied", false), asked: true })
        ?.remedy
    ).toBe("notification-settings");
  });

  it("says what happens to the reminders, not what the permission is called", () => {
    // The person reading this has no reason to know what POST_NOTIFICATIONS
    // is. What they need to know is that doses will not reach them.
    const gap = derivePermissionGap({
      snapshot: snapshot("denied"),
      asked: true,
    });
    expect(gap?.body).toMatch(/reminder/i);
    expect(gap?.body).not.toMatch(/POST_NOTIFICATIONS/);
  });

  it("gives every gap a label for the button that resolves it", () => {
    for (const input of [
      { snapshot: snapshot("undetermined"), asked: true },
      { snapshot: snapshot("denied"), asked: true },
      { snapshot: snapshot("partial", false), asked: true },
    ]) {
      const gap = derivePermissionGap(input);
      expect(gap?.title).not.toBe("");
      expect(gap?.remedyLabel).not.toBe("");
    }
  });
});
