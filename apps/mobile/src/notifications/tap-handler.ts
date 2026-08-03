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

  // The route param stays `medicationId` even though `/dose/log` reads and
  // writes the unified tables: it is the name the alarms already armed on the
  // device carry, and renaming it would drop a tap on any of them.
  return { medicationId: reminderId, scheduledTime };
}

function handleEvent(event: Event, navigate?: NavigateFunction): void {
  // Logged at every exit, because until this existed a tap that went nowhere
  // was completely silent: the app came to the foreground on whatever route it
  // was last showing, and there was no way to tell whether the event never
  // arrived, arrived as something other than a press, carried unusable data,
  // or navigated and lost. Three of those four are real possibilities and they
  // have different fixes. One line per tap is a price worth paying for a
  // handler whose failure mode is "the medication reminder does nothing".
  const surface = navigate === undefined ? "background" : "foreground";
  if (event.type !== EventType.PRESS) {
    console.log(`[tap] ${surface} ignored: type=${String(event.type)}`);
    return;
  }

  const dose = extractDoseData(event);
  if (dose === null) {
    console.log(`[tap] ${surface} press with no usable dose data`);
    return;
  }

  const path = `/dose/log?medicationId=${encodeURIComponent(
    dose.medicationId
  )}&scheduledTime=${encodeURIComponent(String(dose.scheduledTime))}`;

  if (navigate !== undefined) {
    console.log(`[tap] foreground navigating to ${path}`);
    navigate({ pathname: "/dose/log", params: dose });
  } else {
    console.log(`[tap] background opening taskrx://${path}`);
    void Linking.openURL(`taskrx://${path}`).catch((error: unknown) => {
      // Previously fire-and-forget. A rejection here is the one failure that
      // leaves no trace at all — the app is already coming to the foreground
      // because of the notification's own launch intent, so a dropped deep
      // link looks exactly like a tap that opened the app on the wrong screen.
      console.log(
        `[tap] background openURL failed: ${
          error instanceof Error ? error.message : String(error)
        }`
      );
    });
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
