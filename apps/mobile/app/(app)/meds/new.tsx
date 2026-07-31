import { useUser } from "@clerk/clerk-expo";
import type { ScheduleTimes, ScheduleType } from "@taskrx/shared";
import { dailyTimes, intervalTimes } from "@taskrx/shared";
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
  type ScheduleValue,
} from "../../../src/components/SchedulePicker";
import { useToast } from "../../../src/components/useToast";
import * as remindersRepo from "../../../src/db/repos/reminders";

/**
 * Writes a `medication`-kind Reminder, not a `medications` row.
 *
 * This is the medication half of the write-path cutover. Before it, the form
 * wrote `medications` and `medication-mirror.ts` projected a `reminders` row
 * behind it, which is the direction that cannot carry a Task — a Task has no
 * legacy row to project from. The mirror stays in place for the rows that
 * still arrive through the legacy pull path; nothing on this screen produces
 * one any more.
 *
 * The form's own state is unchanged: `SchedulePicker` still speaks the POC
 * `(ScheduleType, ScheduleValue)` pair, and `toScheduleTimes` converts once at
 * save. Rewriting the picker is not this ticket's job and would be a change a
 * person can see.
 */

const TIME_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/;

function defaultSchedule(type: ScheduleType): ScheduleValue {
  if (type === "daily") {
    return { times: ["09:00"] };
  }
  return { hours: 8, startAt: Date.now() };
}

function isValidSchedule(type: ScheduleType, value: ScheduleValue): boolean {
  if (type === "daily") {
    if (!("times" in value)) {
      return false;
    }
    const times = value.times;
    if (times.length === 0) {
      return false;
    }
    return times.every((t) => TIME_PATTERN.test(t));
  }
  if (!("hours" in value) || !("startAt" in value)) {
    return false;
  }
  return value.hours > 0 && Number.isFinite(value.startAt);
}

/**
 * The form's `(scheduleType, value)` pair as the unified tagged union.
 *
 * Deliberately the same mapping migration 005 and `medication-mirror.ts` apply
 * in SQL: same discriminator, same member names, and the same degradation to
 * `[]` / `0` when the value does not match its declared type. A medication
 * saved here must land in `reminders.schedule` identical to one the mirror
 * would have projected, because that column is what
 * `notifications/scheduler.ts` arms alarms from — a different blob is a
 * different set of alarms from the ones the device baseline was taken against.
 *
 * Say the cost plainly: this is a third copy of one mapping, in a third
 * language, and the parity above is asserted by reading the two SQL copies, not
 * by a test — `scripts/verify-reminder-migrations.mjs` pins the migration
 * against the mirror but cannot reach TypeScript. Adding a `weekly` option to
 * `SchedulePicker` therefore means editing here, in `005_backfill_reminders.
 * sql`, and in `medication-mirror.ts`. The two SQL copies die with
 * `medications`; this one becomes the only mapping and the duplication goes
 * with them, which is why it is not worth extracting first.
 *
 * The degraded arms are unreachable from this screen (`onChangeType` replaces
 * the value whenever the type changes, and `canSave` rejects a mismatch before
 * `onSave` runs). They exist because `ScheduleValue` carries no discriminator
 * of its own, so the narrowing has to be written out.
 */
function toScheduleTimes(
  type: ScheduleType,
  value: ScheduleValue
): ScheduleTimes {
  if (type === "daily") {
    return dailyTimes("times" in value ? value.times : []);
  }
  return intervalTimes(
    "hours" in value ? value.hours : 0,
    "startAt" in value ? value.startAt : 0
  );
}

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
  const [scheduleType, setScheduleType] = useState<ScheduleType>("daily");
  const [schedule, setSchedule] = useState<ScheduleValue>(() =>
    defaultSchedule("daily")
  );
  const [saving, setSaving] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const { showToast, ToastContainer } = useToast();

  const onChangeType = useCallback((next: ScheduleType): void => {
    setScheduleType(next);
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
    return isValidSchedule(scheduleType, schedule);
  }, [isSignedIn, userId, trimmedName, scheduleType, schedule]);

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
        schedule: toScheduleTimes(scheduleType, schedule),
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
  }, [
    userId,
    trimmedName,
    initialRxcui,
    dosage,
    scheduleType,
    schedule,
    router,
    showToast,
  ]);

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
          <Pressable
            onPress={() => {
              onChangeType("daily");
            }}
            style={[
              styles.typeButton,
              scheduleType === "daily" ? styles.typeButtonActive : null,
            ]}
          >
            <Text
              style={[
                styles.typeButtonText,
                scheduleType === "daily" ? styles.typeButtonTextActive : null,
              ]}
            >
              Daily
            </Text>
          </Pressable>
          <Pressable
            onPress={() => {
              onChangeType("interval");
            }}
            style={[
              styles.typeButton,
              scheduleType === "interval" ? styles.typeButtonActive : null,
            ]}
          >
            <Text
              style={[
                styles.typeButtonText,
                scheduleType === "interval"
                  ? styles.typeButtonTextActive
                  : null,
              ]}
            >
              Interval
            </Text>
          </Pressable>
        </View>

        <SchedulePicker
          scheduleType={scheduleType}
          value={schedule}
          onChange={setSchedule}
        />

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
    marginTop: 4,
  },
});
