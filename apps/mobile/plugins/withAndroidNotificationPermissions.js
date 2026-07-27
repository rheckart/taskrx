const { withAndroidManifest } = require("expo/config-plugins");

/**
 * Expo config plugin that adds Android permissions required for reliable
 * exact-alarm medication notifications when the app is in Doze or killed.
 *
 * react-native-notify-kit's own config plugin only handles iOS NSE and Android
 * foreground-service manifest opt-in — it does not add these top-level
 * permissions, so we still need this custom plugin.
 *
 * Permissions declared here:
 *   - POST_NOTIFICATIONS      (Android 13+ notification permission)
 *   - SCHEDULE_EXACT_ALARM    (user-granted special access; documented lifecycle
 *                              is declare -> canScheduleExactAlarms() ->
 *                              ACTION_REQUEST_SCHEDULE_EXACT_ALARM -> recheck ->
 *                              reschedule -> degrade if denied)
 *   - RECEIVE_BOOT_COMPLETED  (re-arm alarms after device reboot)
 *   - WAKE_LOCK               (deliver notifications from AlarmManager while
 *                              the device wakes from idle)
 *
 * Deliberately NOT declared (see .omo/plans/convex-expo-revisit.md, D9):
 *   - USE_EXACT_ALARM         (auto-granted but restricted by Google Play to
 *                              apps whose core function is an alarm clock or
 *                              calendar; medication reminders are not a
 *                              verifiably approved category, so declaring it
 *                              is a Play-review liability. SET_ALARM_CLOCK
 *                              works with SCHEDULE_EXACT_ALARM alone, and
 *                              notify-kit falls back to setAndAllowWhileIdle
 *                              if that permission is denied rather than
 *                              silently dropping the trigger.)
 */
function withAndroidNotificationPermissions(config) {
  return withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;
    const permissions = manifest["uses-permission"] ?? [];

    const requiredPermissions = [
      "android.permission.POST_NOTIFICATIONS",
      "android.permission.SCHEDULE_EXACT_ALARM",
      "android.permission.RECEIVE_BOOT_COMPLETED",
      "android.permission.WAKE_LOCK",
    ];

    for (const name of requiredPermissions) {
      const exists = permissions.some(
        (permission) => permission.$ && permission.$["android:name"] === name
      );
      if (!exists) {
        permissions.push({ $: { "android:name": name } });
      }
    }

    // Actively remove USE_EXACT_ALARM if a stale build left it behind.
    manifest["uses-permission"] = permissions.filter(
      (permission) =>
        !(
          permission.$ &&
          permission.$["android:name"] === "android.permission.USE_EXACT_ALARM"
        )
    );
    return config;
  });
}

module.exports = withAndroidNotificationPermissions;
