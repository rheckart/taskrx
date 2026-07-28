import * as Linking from "expo-linking";
import type { ImperativeRouter } from "expo-router";
import notifee, { EventType, type Event } from "react-native-notify-kit";

export type NavigateFunction = ImperativeRouter["navigate"];

function extractDoseData(
  event: Event
): { medicationId: string; scheduledTime: number } | null {
  const data = event.detail.notification?.data;
  if (data === undefined) {
    return null;
  }

  // `reminderId` is the post-cutover key; `medicationId` is what alarms
  // scheduled before the unified migration carry. Both resolve to the same
  // value on a migrated device (005 preserves the primary key), so preferring
  // the new key while still accepting the old one means a tap on an alarm
  // armed by the previous build is still handled instead of silently dropped.
  const reminderId = data.reminderId ?? data.medicationId;
  const scheduledTimeRaw = data.scheduledTime;
  if (typeof reminderId !== "string" || typeof scheduledTimeRaw !== "string") {
    return null;
  }

  const scheduledTime = Number.parseInt(scheduledTimeRaw, 10);
  if (Number.isNaN(scheduledTime)) {
    return null;
  }

  // The route param stays `medicationId`: `/dose/log` still reads through
  // `medicationsRepo`, which the unified cutover deliberately left in place.
  return { medicationId: reminderId, scheduledTime };
}

function handleEvent(event: Event, navigate?: NavigateFunction): void {
  if (event.type !== EventType.PRESS) {
    return;
  }

  const dose = extractDoseData(event);
  if (dose === null) {
    return;
  }

  const path = `/dose/log?medicationId=${encodeURIComponent(
    dose.medicationId
  )}&scheduledTime=${encodeURIComponent(String(dose.scheduledTime))}`;

  if (navigate !== undefined) {
    navigate({ pathname: "/dose/log", params: dose });
  } else {
    void Linking.openURL(`taskrx://${path}`);
  }
}

/**
 * Registers the foreground notification tap handler.
 *
 * Must be called before scheduling notifications to avoid missing taps that
 * occur immediately after launch. Returns an unsubscribe function.
 */
export function registerNotificationResponseHandler(
  navigate: NavigateFunction
): () => void {
  return notifee.onForegroundEvent((event) => {
    handleEvent(event, navigate);
  });
}

/**
 * Registers the background notification event handler.
 *
 * Notifee requires this to be registered as a top-level function (not inside
 * a component) so the JS bundle can execute it when a background event wakes
 * the app. It deep-links to the dose logging screen via the app scheme.
 */
export function registerBackgroundNotificationHandler(): void {
  notifee.onBackgroundEvent((event) => {
    handleEvent(event);
    return Promise.resolve();
  });
}
