import { useUser } from "@clerk/clerk-expo";
import type { Reminder, ReminderInstance } from "@taskrx/shared";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";

import { useToast } from "../../../src/components/useToast";
import * as instancesRepo from "../../../src/db/repos/reminderInstances";
import * as remindersRepo from "../../../src/db/repos/reminders";
import { formatDayTime } from "../../../src/format/datetime";
import { snoozeReminder } from "../../../src/notifications/snooze";

type ActionStatus = "idle" | "completing" | "skipping" | "snoozing" | "done";

/** How far a snooze defers a moment. Named once; the copy reads it too. */
const SNOOZE_MINUTES = 15;

/**
 * The answer surface: what became of one Occurrence, of either Kind.
 *
 * It is reached three ways — a tap on the timeline, a tap on a notification,
 * and the medication list's "log now" — and all three carry the same pairing of
 * Reminder and scheduled time. Nothing about it was ever medication-specific
 * except its words, which is why the fusion in #13 is a relabelling here rather
 * than a second screen: a task is completed, skipped and snoozed through
 * exactly these buttons.
 *
 * **The route path and the `medicationId` param are both frozen.** The param is
 * the key already-armed alarms carry. The path is what `tap-handler.ts` builds
 * its background deep link from *and* what the device harness types at `adb`
 * (`scripts/notification-harness.sh`), so renaming it would put the harness and
 * the app out of step on the one thing the harness exists to verify. Both names
 * therefore stay medication-flavoured while the screen is not.
 *
 * Reads and writes both go to the unified tables.
 *
 * Before the write-path cutover this screen read `reminder_instances` and
 * wrote a legacy `dose_logs` row, leaning on a mirror module to reconcile the
 * two. That split is what the dedupe dance here existed for: the screen had to
 * look for an existing dose log rather than an existing instance, because one
 * whose projection had failed would otherwise be duplicated on every retry.
 * Both the legacy table and the mirror are now deleted.
 *
 * With the instance written directly there is one row and one place to look
 * for it, so `findAnswerableForOccurrence` is both the dedupe and the load.
 *
 * What still has to hold for a pre-cutover dose to resolve is the *pairing*.
 * Migration 005 preserved the medication's primary key as the reminder's and
 * copied `dose_logs.medication_id` into `reminder_instances.reminder_id`
 * unchanged, so the `medicationId` an alarm armed by an older build carries
 * still names the reminder, and the instance backfilled from that dose still
 * sits at the same `(reminder, scheduled time)`.
 */
interface LoadedState {
  readonly kind: "loaded";
  readonly reminder: Reminder;
  readonly scheduledTime: number;
  readonly instance: ReminderInstance;
}

interface ErrorState {
  readonly kind: "error";
  readonly message: string;
}

type ScreenState = { readonly kind: "loading" } | ErrorState | LoadedState;

function parseParams(params: Record<string, string | string[]>): {
  medicationId: string;
  scheduledTime: number;
} | null {
  const rawMedicationId = params.medicationId;
  const rawScheduledTime = params.scheduledTime;
  if (
    typeof rawMedicationId !== "string" ||
    typeof rawScheduledTime !== "string"
  ) {
    return null;
  }
  const scheduledTime = Number.parseInt(rawScheduledTime, 10);
  if (Number.isNaN(scheduledTime)) {
    return null;
  }
  return { medicationId: rawMedicationId, scheduledTime };
}

/**
 * Every Kind-specific word on this screen, decided once.
 *
 * ADR 0001 made the *stored* vocabulary Kind-neutral so that exactly one place
 * per surface has to know the difference, and this is that place for the answer
 * screen — the job `statusLabel` does on the timeline. Scattering `kind ===
 * "medication" ?` through the render would put four of them back.
 *
 * "Dose" appears in none of them. CONTEXT.md defines a Dose as the quantity of
 * a drug and lists using it for "a recorded taking" under _Avoid_; what these
 * confirm is a Reminder Instance. ADR 0001 licenses showing a medication as
 * *taken*, which is what they say.
 *
 * `snooze` was here too while a task's deferral armed nothing — promising a
 * reminder in fifteen minutes would have been a lie on one Kind and a kept
 * promise on the other. #14 gave the task Tier a trigger, so the button means
 * the same thing on both and says so once.
 */
interface KindWords {
  readonly badge: string;
  readonly due: string;
  readonly complete: string;
  readonly completed: string;
  readonly skipped: string;
}

function wordsFor(kind: Reminder["kind"]): KindWords {
  return kind === "medication"
    ? {
        badge: "MEDICATION",
        due: "Time to take",
        complete: "Mark taken",
        completed: "Recorded as taken",
        skipped: "Recorded as skipped",
      }
    : {
        badge: "TASK",
        due: "Time to do it",
        complete: "Mark done",
        completed: "Recorded as done",
        skipped: "Recorded as skipped",
      };
}

function urgencyLabel(scheduledTime: number, words: KindWords): string {
  const now = Date.now();
  const diffMinutes = Math.round((now - scheduledTime) / 60_000);
  if (diffMinutes < -1) {
    return `Due in ${String(Math.abs(diffMinutes))} minutes`;
  }
  if (diffMinutes <= 1) {
    return words.due;
  }
  return `Overdue by ${String(diffMinutes)} minutes`;
}

export default function DoseLogScreen(): ReactElement {
  const router = useRouter();
  const params = useLocalSearchParams();
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;

  const [state, setState] = useState<ScreenState>({ kind: "loading" });
  const [actionStatus, setActionStatus] = useState<ActionStatus>("idle");
  const [completedAt, setCompletedAt] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { showToast, ToastContainer } = useToast();

  const parsedParams = useMemo(() => parseParams(params), [params]);

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined || parsedParams === null) {
      setState({ kind: "error", message: "Missing reminder information." });
      return;
    }

    try {
      const reminder = await remindersRepo.get(parsedParams.medicationId);
      if (reminder === null) {
        setState({ kind: "error", message: "This reminder no longer exists." });
        return;
      }

      // One lookup, not two: the unanswered instance for this exact
      // `(reminder, scheduled time)` pairing is the row the buttons below act
      // on. Minting it here rather than at alarm time is what keeps the table a
      // record of what the user did rather than a materialized copy of the
      // schedule.
      //
      // "Unanswered" includes `snoozed`, which is why this is
      // `findAnswerableForOccurrence` and not the `findPendingForOccurrence` it
      // replaces. A snooze is a deferral, not an answer, so coming back to the
      // same moment after one has to find the row it already made rather than
      // open a second beside it. That mattered the moment snoozing began
      // writing a status at all — before, a snoozed row stayed `pending` and
      // the narrower query happened to find it.
      //
      // What is still not covered, deliberately: reopening a moment already
      // completed or skipped mints a second, pending instance beside the
      // answered one. That is issue #21, it predates this screen, and the
      // timeline already deduplicates on exactly this pairing so it does not
      // render twice.
      const instance =
        (await instancesRepo.findAnswerableForOccurrence(
          userId,
          parsedParams.medicationId,
          parsedParams.scheduledTime
        )) ??
        (await instancesRepo.create({
          userId,
          reminderId: parsedParams.medicationId,
          // Off the reminder, never hardcoded: the same occurrence pairing
          // reaches this screen for a `task` reminder, and `reminder_kind` is
          // what decides whether the instance counts toward Adherence.
          reminderKind: reminder.kind,
          scheduledTime: parsedParams.scheduledTime,
        }));

      setState({
        kind: "loaded",
        reminder,
        scheduledTime: parsedParams.scheduledTime,
        instance,
      });
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to load reminder";
      setState({ kind: "error", message });
    }
  }, [parsedParams, userId]);

  useEffect(() => {
    if (isLoaded) {
      void load();
    }
  }, [load, isLoaded]);

  const handleComplete = useCallback(async (): Promise<void> => {
    if (state.kind !== "loaded") {
      return;
    }
    const words = wordsFor(state.reminder.kind);
    setActionStatus("completing");
    setError(null);
    try {
      const now = Date.now();
      await instancesRepo.markCompleted(state.instance.id, now);
      setCompletedAt(now);
      setActionStatus("done");
      showToast.success(words.completed);
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to record it";
      setError(message);
      setActionStatus("idle");
      showToast.error("Couldn't record it — will retry on next sync");
    }
  }, [state, showToast]);

  const handleSkip = useCallback(async (): Promise<void> => {
    if (state.kind !== "loaded") {
      return;
    }
    const words = wordsFor(state.reminder.kind);
    setActionStatus("skipping");
    setError(null);
    try {
      await instancesRepo.markSkipped(state.instance.id);
      setCompletedAt(Date.now());
      setActionStatus("done");
      showToast.success(words.skipped);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to skip it";
      setError(message);
      setActionStatus("idle");
      showToast.error("Couldn't record skip — will retry on next sync");
    }
  }, [state, showToast]);

  /**
   * Snoozing writes the deferral down and then arms the deferred reminder.
   *
   * The status write is the half that was missing. Snoozing used to arm a
   * notification and leave the Instance `pending`, so nothing about the deferral
   * was recorded: the timeline still showed the moment as unanswered, the sweep
   * would later call it Missed, and Adherence — which treats a snoozed dose as a
   * clinical fact and a snoozed task as a mere reschedule — never saw one of
   * either. `snoozed` is the status that makes both of those true.
   *
   * The order matters. The row is written first, so a failure to arm still
   * leaves the deferral recorded rather than losing it; an armed alarm with no
   * row would be the reverse trade, and the row is the thing that syncs.
   *
   * `armed` is false only for a reminder this device cannot find — unreachable
   * from here, since the screen refuses to render when that same lookup returns
   * null. The toast still branches on it rather than assuming, because the one
   * thing worse than an unhelpful message is promising a reminder that was
   * never armed.
   */
  const handleSnooze = useCallback(async (): Promise<void> => {
    if (state.kind !== "loaded") {
      return;
    }
    setActionStatus("snoozing");
    setError(null);
    try {
      // One instant, decided here and passed on. The stored deferral and the
      // armed alarm have to name the same moment, and two readings of the clock
      // would let them drift apart.
      const snoozeUntil = Date.now() + SNOOZE_MINUTES * 60_000;
      await instancesRepo.markSnoozed(state.instance.id, snoozeUntil);
      const armed = await snoozeReminder(
        state.reminder.id,
        state.scheduledTime,
        snoozeUntil
      );
      setCompletedAt(Date.now());
      setActionStatus("done");
      showToast.info(
        armed
          ? `Reminder snoozed ${String(SNOOZE_MINUTES)} minutes`
          : "Put off for now"
      );
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to snooze";
      setError(message);
      setActionStatus("idle");
      showToast.error("Couldn't snooze reminder");
    }
  }, [state, showToast]);

  /**
   * Back to the timeline, not to `/meds`.
   *
   * The timeline is where both Kinds live and where every route into this
   * screen but one now starts, so sending a finished task to the medication
   * list would strand it somewhere it does not appear. It is also the right
   * destination for a dose: the row just answered is on it, in its new state.
   */
  const handleDone = useCallback((): void => {
    router.replace("/");
  }, [router]);

  if (!isLoaded) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  if (!isSignedIn || userId === undefined) {
    return (
      <View style={styles.center}>
        <Text>Sign in to answer a reminder.</Text>
      </View>
    );
  }

  if (parsedParams === null) {
    return (
      <View style={styles.center}>
        <Text style={styles.errorTitle}>Invalid reminder link</Text>
        <Text style={styles.errorBody}>
          Open this screen from your timeline or from a reminder notification.
        </Text>
        <Pressable onPress={handleDone} style={styles.doneButton}>
          <Text style={styles.doneButtonText}>Go to my day</Text>
        </Pressable>
      </View>
    );
  }

  if (state.kind === "loading") {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  if (state.kind === "error") {
    return (
      <View style={styles.center}>
        <Text style={styles.errorTitle}>Could not load this reminder</Text>
        <Text style={styles.errorBody}>{state.message}</Text>
        <Pressable onPress={handleDone} style={styles.doneButton}>
          <Text style={styles.doneButtonText}>Go to my day</Text>
        </Pressable>
      </View>
    );
  }

  const { reminder, scheduledTime } = state;
  const words = wordsFor(reminder.kind);
  const isDone = actionStatus === "done";

  return (
    <>
      <ToastContainer />
      <ScrollView
        style={styles.container}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.card}>
          <Text style={styles.kindLabel}>{words.badge}</Text>
          <Text style={styles.reminderName}>{reminder.name}</Text>
          {reminder.dosage !== undefined ? (
            <Text style={styles.dosage}>{reminder.dosage}</Text>
          ) : null}
          <Text style={styles.scheduledTime}>
            Scheduled: {formatDayTime(scheduledTime)}
          </Text>
          <Text
            style={[
              styles.urgency,
              scheduledTime < Date.now() - 5 * 60_000 && styles.urgencyOverdue,
            ]}
          >
            {urgencyLabel(scheduledTime, words)}
          </Text>
        </View>

        {isDone && completedAt !== null ? (
          <View style={styles.successCard}>
            <Text style={styles.successTitle}>Recorded</Text>
            <Text style={styles.successBody}>{formatDayTime(completedAt)}</Text>
            <Pressable onPress={handleDone} style={styles.doneButton}>
              <Text style={styles.doneButtonText}>Done</Text>
            </Pressable>
          </View>
        ) : (
          <View style={styles.actions}>
            <Pressable
              onPress={() => void handleComplete()}
              disabled={actionStatus !== "idle"}
              style={[
                styles.actionButton,
                styles.completeButton,
                actionStatus !== "idle" && styles.actionButtonDisabled,
              ]}
            >
              {actionStatus === "completing" ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.actionButtonText}>{words.complete}</Text>
              )}
            </Pressable>

            <Pressable
              onPress={() => void handleSkip()}
              disabled={actionStatus !== "idle"}
              style={[
                styles.actionButton,
                styles.skipButton,
                actionStatus !== "idle" && styles.actionButtonDisabled,
              ]}
            >
              {actionStatus === "skipping" ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.actionButtonText}>Skip</Text>
              )}
            </Pressable>

            <Pressable
              onPress={() => void handleSnooze()}
              disabled={actionStatus !== "idle"}
              style={[
                styles.actionButton,
                styles.snoozeButton,
                actionStatus !== "idle" && styles.actionButtonDisabled,
              ]}
            >
              {actionStatus === "snoozing" ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.actionButtonText}>
                  {`Snooze ${String(SNOOZE_MINUTES)} min`}
                </Text>
              )}
            </Pressable>
          </View>
        )}

        {error !== null ? <Text style={styles.errorText}>{error}</Text> : null}
      </ScrollView>
    </>
  );
}

const styles = StyleSheet.create({
  actionButton: {
    alignItems: "center",
    borderRadius: 8,
    paddingVertical: 14,
  },
  actionButtonDisabled: {
    opacity: 0.6,
  },
  actionButtonText: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "600",
  },
  actions: {
    gap: 12,
    marginTop: 24,
  },
  card: {
    backgroundColor: "#f5f5f5",
    borderRadius: 12,
    padding: 20,
  },
  center: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    padding: 24,
  },
  container: {
    backgroundColor: "#fff",
    flex: 1,
  },
  content: {
    padding: 16,
    paddingBottom: 48,
  },
  dosage: {
    color: "#666",
    fontSize: 16,
    marginTop: 4,
  },
  doneButton: {
    alignItems: "center",
    backgroundColor: "#007aff",
    borderRadius: 8,
    marginTop: 16,
    paddingVertical: 14,
  },
  doneButtonText: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "600",
  },
  errorBody: {
    color: "#666",
    marginTop: 8,
    textAlign: "center",
  },
  errorText: {
    color: "#c00",
    marginTop: 16,
    textAlign: "center",
  },
  errorTitle: {
    fontSize: 18,
    fontWeight: "600",
  },
  kindLabel: {
    color: "#666",
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 1,
    marginBottom: 6,
  },
  reminderName: {
    fontSize: 22,
    fontWeight: "700",
  },
  scheduledTime: {
    color: "#444",
    fontSize: 15,
    marginTop: 12,
  },
  skipButton: {
    backgroundColor: "#ff9500",
  },
  snoozeButton: {
    backgroundColor: "#5856d6",
  },
  successBody: {
    color: "#666",
    marginTop: 4,
  },
  successCard: {
    alignItems: "center",
    backgroundColor: "#e8f5e9",
    borderRadius: 12,
    marginTop: 24,
    padding: 24,
  },
  successTitle: {
    color: "#2e7d32",
    fontSize: 18,
    fontWeight: "600",
  },
  completeButton: {
    backgroundColor: "#34c759",
  },
  urgency: {
    color: "#007aff",
    fontSize: 16,
    fontWeight: "600",
    marginTop: 8,
  },
  urgencyOverdue: {
    color: "#c00",
  },
});
