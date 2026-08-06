import { useUser } from "@clerk/clerk-expo";
import { useLocalSearchParams, useRouter } from "expo-router";
import { useCallback, useMemo, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import {
  SchedulePicker,
  defaultSchedule,
  isWellFormedSchedule,
  type EditableSchedule,
  type EditableScheduleType,
} from "../../../src/components/SchedulePicker";
import { useToast } from "../../../src/components/useToast";
import * as remindersRepo from "../../../src/db/repos/reminders";

/**
 * Writes a `medication`-kind Reminder.
 *
 * This was the medication half of the write-path cutover. The form used to
 * write a legacy `medications` row and let a mirror project a `reminders` row
 * behind it, which is the direction that cannot carry a Task — a Task has no
 * legacy row to project from. Both the legacy table and its mirror are now
 * deleted.
 *
 * The form now holds a `ScheduleTimes` value and saves it unchanged. It used to
 * hold the POC `(ScheduleType, ScheduleValue)` pair and convert at save; both
 * halves of that pair went with the picker's rewrite for the two new variants,
 * and with them the last mapping between two spellings of a schedule.
 *
 * Four variants are offered. `once` gives a medication with a single dose —
 * a short course, a one-off injection — and `none` puts it in the Backlog, which
 * for a medication means "I have this, I take it when I need it".
 */

const SCHEDULE_TYPES: readonly {
  readonly type: EditableScheduleType;
  readonly label: string;
}[] = [
  { type: "daily", label: "Daily" },
  { type: "interval", label: "Interval" },
  { type: "once", label: "Once" },
  { type: "none", label: "No time" },
];

export default function NewMedicationScreen(): ReactElement {
  const router = useRouter();
  const params = useLocalSearchParams<{ rxcui?: string; name?: string }>();
  const { isLoaded, isSignedIn, user } = useUser();

  const initialName =
    typeof params.name === "string" && params.name.length > 0
      ? params.name
      : "";
  const initialRxcui =
    typeof params.rxcui === "string" && params.rxcui.length > 0
      ? params.rxcui
      : undefined;

  const [name, setName] = useState<string>(initialName);
  const [dosage, setDosage] = useState<string>("");
  const [schedule, setSchedule] = useState<EditableSchedule>(() =>
    defaultSchedule("daily")
  );
  const [saving, setSaving] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const { showToast, ToastContainer } = useToast();

  const onChangeType = useCallback((next: EditableScheduleType): void => {
    setSchedule(defaultSchedule(next));
  }, []);

  const trimmedName = name.trim();
  const userId = user?.id;
  const canSave = useMemo<boolean>(() => {
    if (!isSignedIn || userId === undefined) {
      return false;
    }
    if (trimmedName.length === 0) {
      return false;
    }
    return isWellFormedSchedule(schedule);
  }, [isSignedIn, userId, trimmedName, schedule]);

  const onSave = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const trimmedDosage = dosage.trim();
      const dosageArg = trimmedDosage.length > 0 ? trimmedDosage : undefined;

      await remindersRepo.create({
        userId,
        kind: "medication",
        name: trimmedName,
        schedule,
        ...(initialRxcui !== undefined ? { rxcui: initialRxcui } : {}),
        ...(dosageArg !== undefined ? { dosage: dosageArg } : {}),
      });
      showToast.success("Medication added");
      router.replace("/meds");
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Save failed";
      setError(message);
      setSaving(false);
      showToast.error("Couldn't save — will retry on next sync");
    }
  }, [userId, trimmedName, initialRxcui, dosage, schedule, router, showToast]);

  if (!isLoaded) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  if (!isSignedIn) {
    return (
      <View style={styles.center}>
        <Text>Sign in to add a medication.</Text>
      </View>
    );
  }

  return (
    <>
      <ToastContainer />
      <ScrollView
        style={styles.container}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.label}>Name</Text>
        <TextInput
          style={styles.input}
          value={name}
          onChangeText={setName}
          placeholder="e.g. Metformin"
          placeholderTextColor="#999"
          autoCorrect={false}
        />

        <Text style={styles.label}>Dosage (optional)</Text>
        <TextInput
          style={styles.input}
          value={dosage}
          onChangeText={setDosage}
          placeholder="e.g. 500mg"
          placeholderTextColor="#999"
          autoCorrect={false}
        />

        {initialRxcui !== undefined ? (
          <Text style={styles.rxcui}>RxCUI: {initialRxcui}</Text>
        ) : null}

        <Text style={styles.label}>Schedule type</Text>
        <View style={styles.typeRow}>
          {SCHEDULE_TYPES.map(({ type, label }) => (
            <Pressable
              key={type}
              onPress={() => {
                onChangeType(type);
              }}
              style={[
                styles.typeButton,
                schedule.type === type ? styles.typeButtonActive : null,
              ]}
            >
              <Text
                style={[
                  styles.typeButtonText,
                  schedule.type === type ? styles.typeButtonTextActive : null,
                ]}
              >
                {label}
              </Text>
            </Pressable>
          ))}
        </View>

        <SchedulePicker value={schedule} onChange={setSchedule} />

        {error !== null ? (
          <Text style={styles.errorText}>Save failed: {error}</Text>
        ) : null}

        <Pressable
          onPress={() => void onSave()}
          disabled={!canSave || saving}
          style={[
            styles.saveButton,
            !canSave || saving ? styles.saveButtonDisabled : null,
          ]}
        >
          {saving ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <Text style={styles.saveButtonText}>Save medication</Text>
          )}
        </Pressable>
      </ScrollView>
    </>
  );
}

const styles = StyleSheet.create({
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
  errorText: {
    color: "#c00",
    marginTop: 16,
  },
  input: {
    borderColor: "#ccc",
    borderRadius: 6,
    borderWidth: 1,
    fontSize: 16,
    marginTop: 4,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  label: {
    color: "#333",
    fontSize: 14,
    fontWeight: "600",
    marginTop: 16,
  },
  rxcui: {
    color: "#666",
    fontSize: 13,
    marginTop: 8,
  },
  saveButton: {
    alignItems: "center",
    backgroundColor: "#007aff",
    borderRadius: 8,
    marginTop: 24,
    paddingVertical: 14,
  },
  saveButtonDisabled: {
    backgroundColor: "#aac8ee",
  },
  saveButtonText: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "600",
  },
  typeButton: {
    borderColor: "#ccc",
    borderRadius: 6,
    borderWidth: 1,
    marginRight: 8,
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  typeButtonActive: {
    backgroundColor: "#007aff",
    borderColor: "#007aff",
  },
  typeButtonText: {
    color: "#333",
    fontSize: 14,
    fontWeight: "500",
  },
  typeButtonTextActive: {
    color: "#fff",
  },
  typeRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    marginTop: 4,
  },
});
