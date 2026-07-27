import { describe, expect, it } from "vitest";

import {
  ANDROID_ALARM_DISABLED,
  ANDROID_ALARM_ENABLED,
  ANDROID_ALARM_NOT_SUPPORTED,
  NOTIFICATION_AUTH_AUTHORIZED,
  NOTIFICATION_AUTH_DENIED,
  NOTIFICATION_AUTH_NOT_DETERMINED,
  NOTIFICATION_AUTH_PROVISIONAL,
  derivePermissionStatus,
  isExactAlarmGranted,
} from "./permission-state";

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
