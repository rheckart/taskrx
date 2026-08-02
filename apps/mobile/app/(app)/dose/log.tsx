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
import { snoozeDose } from "../../../src/notifications/snooze";

type ActionStatus = "idle" | "taking" | "skipping" | "snoozing" | "done";

/**
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
 * for it, so `findPendingForOccurrence` is both the dedupe and the load.
 *
 * What still has to hold for a pre-cutover dose to resolve is the *pairing*.
 * Migration 005 preserved the medication's primary key as the reminder's and
 * copied `dose_logs.medication_id` into `reminder_instances.reminder_id`
 * unchanged, so the `medicationId` an alarm armed by an older build carries
 * still names the reminder, and the instance backfilled from that dose still
 * sits at the same `(reminder, scheduled time)`.
 *
 * The route param is still `medicationId` — it is the key the alarms already
 * armed on the device carry (`notifications/scheduler.ts`), and renaming it
 * would drop taps on those.
 */
interface LoadedState {
  readonly kind: "loaded";
  readonly medication: Reminder;
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

function formatDateTime(date: Date): string {
  return date.toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function urgencyLabel(scheduledTime: number): string {
  const now = Date.now();
  const diffMinutes = Math.round((now - scheduledTime) / 60_000);
  if (diffMinutes < -1) {
    return `Due in ${String(Math.abs(diffMinutes))} minutes`;
  }
  if (diffMinutes <= 1) {
    return "Time to take";
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
      setState({ kind: "error", message: "Missing dose information." });
      return;
    }

    try {
      const medication = await remindersRepo.get(parsedParams.medicationId);
      if (medication === null) {
        setState({
          kind: "error",
          message: "Medication not found. It may have been deleted.",
        });
        return;
      }

      // One lookup, not two: the pending instance for this exact
      // `(reminder, scheduled time)` pairing is the row the buttons below act
      // on. Minting it here rather than at alarm time is what keeps the table a
      // record of what the user did rather than a materialized copy of the
      // schedule.
      //
      // Be precise about what a miss means, because it is narrower than it
      // looks: `findPendingForOccurrence` filters `status = 'pending'`, so a
      // null is "no *unanswered* instance", not "this occurrence has never been
      // answered". Reopening an occurrence already taken or skipped therefore
      // mints a second, pending instance beside the answered one — reachable by
      // tapping the same alarm twice, since `autoCancel` does not retract an
      // alarm the user already acted on from a different surface.
      //
      // Carried over deliberately rather than introduced here: the dose-log
      // path this replaces filtered identically (`findPendingForDose`), so the
      // behaviour is unchanged, which is what this ticket asks for. The fix
      // belongs with the timeline projection, which deduplicates on exactly
      // this pairing across every status.
      const instance =
        (await instancesRepo.findPendingForOccurrence(
          userId,
          parsedParams.medicationId,
          parsedParams.scheduledTime
        )) ??
        (await instancesRepo.create({
          userId,
          reminderId: parsedParams.medicationId,
          // Off the reminder, never hardcoded: the same occurrence pairing
          // reaches this screen for a `task` reminder once the timeline
          // lands, and `reminder_kind` is what decides whether the instance
          // counts toward Adherence.
          reminderKind: medication.kind,
          scheduledTime: parsedParams.scheduledTime,
        }));

      setState({
        kind: "loaded",
        medication,
        scheduledTime: parsedParams.scheduledTime,
        instance,
      });
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to load dose";
      setState({ kind: "error", message });
    }
  }, [parsedParams, userId]);

  useEffect(() => {
    if (isLoaded) {
      void load();
    }
  }, [load, isLoaded]);

  const handleMarkTaken = useCallback(async (): Promise<void> => {
    if (state.kind !== "loaded") {
      return;
    }
    setActionStatus("taking");
    setError(null);
    try {
      const now = Date.now();
      await instancesRepo.markTaken(state.instance.id, now);
      setCompletedAt(now);
      setActionStatus("done");
      showToast.success("Dose recorded");
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to mark taken";
      setError(message);
      setActionStatus("idle");
      showToast.error("Couldn't record dose — will retry on next sync");
    }
  }, [state, showToast]);

  const handleSkip = useCallback(async (): Promise<void> => {
    if (state.kind !== "loaded") {
      return;
    }
    setActionStatus("skipping");
    setError(null);
    try {
      await instancesRepo.markSkipped(state.instance.id);
      setCompletedAt(Date.now());
      setActionStatus("done");
      showToast.success("Dose skipped");
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to skip dose";
      setError(message);
      setActionStatus("idle");
      showToast.error("Couldn't record skip — will retry on next sync");
    }
  }, [state, showToast]);

  const handleSnooze = useCallback(async (): Promise<void> => {
    if (state.kind !== "loaded") {
      return;
    }
    setActionStatus("snoozing");
    setError(null);
    try {
      await snoozeDose(state.medication.id, state.scheduledTime, 15);
      setCompletedAt(Date.now());
      setActionStatus("done");
      showToast.info("Reminder snoozed 15 minutes");
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to snooze";
      setError(message);
      setActionStatus("idle");
      showToast.error("Couldn't snooze reminder");
    }
  }, [state, showToast]);

  const handleDone = useCallback((): void => {
    router.replace("/meds");
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
        <Text>Sign in to log a dose.</Text>
      </View>
    );
  }

  if (parsedParams === null) {
    return (
      <View style={styles.center}>
        <Text style={styles.errorTitle}>Invalid dose link</Text>
        <Text style={styles.errorBody}>
          Open this screen from a medication reminder notification.
        </Text>
        <Pressable onPress={handleDone} style={styles.doneButton}>
          <Text style={styles.doneButtonText}>Go to medications</Text>
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
        <Text style={styles.errorTitle}>Could not load dose</Text>
        <Text style={styles.errorBody}>{state.message}</Text>
        <Pressable onPress={handleDone} style={styles.doneButton}>
          <Text style={styles.doneButtonText}>Go to medications</Text>
        </Pressable>
      </View>
    );
  }

  const { medication, scheduledTime } = state;
  const scheduledDate = new Date(scheduledTime);
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
          <Text style={styles.medName}>{medication.name}</Text>
          {medication.dosage !== undefined ? (
            <Text style={styles.dosage}>{medication.dosage}</Text>
          ) : null}
          <Text style={styles.scheduledTime}>
            Scheduled: {formatDateTime(scheduledDate)}
          </Text>
          <Text
            style={[
              styles.urgency,
              scheduledTime < Date.now() - 5 * 60_000 && styles.urgencyOverdue,
            ]}
          >
            {urgencyLabel(scheduledTime)}
          </Text>
        </View>

        {isDone && completedAt !== null ? (
          <View style={styles.successCard}>
            <Text style={styles.successTitle}>Recorded</Text>
            <Text style={styles.successBody}>
              {formatDateTime(new Date(completedAt))}
            </Text>
            <Pressable onPress={handleDone} style={styles.doneButton}>
              <Text style={styles.doneButtonText}>Done</Text>
            </Pressable>
          </View>
        ) : (
          <View style={styles.actions}>
            <Pressable
              onPress={() => void handleMarkTaken()}
              disabled={actionStatus !== "idle"}
              style={[
                styles.actionButton,
                styles.takenButton,
                actionStatus !== "idle" && styles.actionButtonDisabled,
              ]}
            >
              {actionStatus === "taking" ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.actionButtonText}>Mark Taken</Text>
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
                <Text style={styles.actionButtonText}>Snooze 15 min</Text>
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
  medName: {
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
  takenButton: {
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
