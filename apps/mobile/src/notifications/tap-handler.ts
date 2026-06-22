import notifee, { EventType, type Event } from "@notifee/react-native";
import * as Linking from "expo-linking";
import type { ImperativeRouter } from "expo-router";

export type NavigateFunction = ImperativeRouter["navigate"];

function extractDoseData(
  event: Event
): { medicationId: string; scheduledTime: number } | null {
  const data = event.detail.notification?.data;
  if (data === undefined) {
    return null;
  }

  const medicationId = data.medicationId;
  const scheduledTimeRaw = data.scheduledTime;
  if (
    typeof medicationId !== "string" ||
    typeof scheduledTimeRaw !== "string"
  ) {
    return null;
  }

  const scheduledTime = Number.parseInt(scheduledTimeRaw, 10);
  if (Number.isNaN(scheduledTime)) {
    return null;
  }

  return { medicationId, scheduledTime };
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
    navigate({ pathname: "/dose/log" as never, params: dose });
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
