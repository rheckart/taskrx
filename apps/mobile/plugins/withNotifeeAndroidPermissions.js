const { withAndroidManifest } = require("expo/config-plugins");

/**
 * Expo config plugin that adds Android permissions required by Notifee for
 * reliable exact-alarm notifications.
 *
 * Notifee does not ship with an Expo config plugin, and its bundled manifest
 * does not declare SCHEDULE_EXACT_ALARM. This plugin injects the required
 * permissions and receiver flags into the generated AndroidManifest.xml.
 */
function withNotifeeAndroidPermissions(config) {
  return withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;
    const permissions = manifest["uses-permission"] ?? [];

    const requiredPermissions = [
      "android.permission.POST_NOTIFICATIONS",
      "android.permission.SCHEDULE_EXACT_ALARM",
      "android.permission.USE_EXACT_ALARM",
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

    manifest["uses-permission"] = permissions;
    return config;
  });
}

module.exports = withNotifeeAndroidPermissions;
