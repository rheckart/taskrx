import { registerBackgroundNotificationHandler } from "./tap-handler";

/**
 * Side-effect module that registers the notifee background event handler.
 *
 * MUST be imported from the app entry point (apps/mobile/index.js), NOT from
 * a route or layout. When a trigger notification fires while the app process
 * is dead, notifee starts a headless JS context that evaluates the entry
 * bundle but never mounts expo-router — so a registration living in
 * app/_layout.tsx never runs. The symptom is:
 *
 *   W ReactNativeJS: No task registered for key app.notifee.notification-event
 *
 * Delivery still works in that state, but tapping the notification cannot be
 * handled, so tap-to-log silently does nothing after the app has been killed.
 */
registerBackgroundNotificationHandler();
