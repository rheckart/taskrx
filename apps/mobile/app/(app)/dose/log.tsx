import { useUser } from "@clerk/clerk-expo";
import type { DoseLog, Medication } from "@taskrx/shared";
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

import * as doseLogsRepo from "../../../src/db/repos/doseLogs";
import * as medicationsRepo from "../../../src/db/repos/medications";
import { snoozeDose } from "../../../src/notifications/snooze";

type ActionStatus = "idle" | "taking" | "skipping" | "snoozing" | "done";

interface LoadedState {
  readonly kind: "loaded";
  readonly medication: Medication;
  readonly scheduledTime: number;
  readonly doseLog: DoseLog;
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

  const parsedParams = useMemo(() => parseParams(params), [params]);

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined || parsedParams === null) {
      setState({ kind: "error", message: "Missing dose information." });
      return;
    }

    try {
      const medication = await medicationsRepo.get(parsedParams.medicationId);
      if (medication === null) {
        setState({
          kind: "error",
          message: "Medication not found. It may have been deleted.",
        });
        return;
      }

      let doseLog = await doseLogsRepo.findPendingForDose(
        userId,
        parsedParams.medicationId,
        parsedParams.scheduledTime
      );
      doseLog ??= await doseLogsRepo.create({
        userId,
        medicationId: parsedParams.medicationId,
        scheduledTime: parsedParams.scheduledTime,
      });

      setState({
        kind: "loaded",
        medication,
        scheduledTime: parsedParams.scheduledTime,
        doseLog,
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
      await doseLogsRepo.markTaken(state.doseLog.id, now);
      setCompletedAt(now);
      setActionStatus("done");
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to mark taken";
      setError(message);
      setActionStatus("idle");
    }
  }, [state]);

  const handleSkip = useCallback(async (): Promise<void> => {
    if (state.kind !== "loaded") {
      return;
    }
    setActionStatus("skipping");
    setError(null);
    try {
      await doseLogsRepo.markSkipped(state.doseLog.id);
      setCompletedAt(Date.now());
      setActionStatus("done");
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to skip dose";
      setError(message);
      setActionStatus("idle");
    }
  }, [state]);

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
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Failed to snooze";
      setError(message);
      setActionStatus("idle");
    }
  }, [state]);

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
