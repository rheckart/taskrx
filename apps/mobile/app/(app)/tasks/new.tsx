import { useUser } from "@clerk/clerk-expo";
import { useRouter } from "expo-router";
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
  ScheduleTypeChooser,
  defaultSchedule,
  isWellFormedSchedule,
  type EditableSchedule,
} from "../../../src/components/SchedulePicker";
import { useToast } from "../../../src/components/useToast";
import * as remindersRepo from "../../../src/db/repos/reminders";

/**
 * Writes a `task`-kind Reminder — the second Kind becoming reachable, which is
 * what issue #13 is for.
 *
 * It is a separate screen from `meds/new.tsx` rather than that form with a Kind
 * toggle, and the difference between the two is the argument for the split: a
 * task has no dosage, no RxCUI, no pill count and no refill threshold, and the
 * ticket asks that those be *absent* rather than disabled. A toggle would have
 * to hide four fields and skip the drug-search entry point, which is a form
 * pretending to be one thing while being two. What they genuinely share — the
 * schedule — is `SchedulePicker`, shared as a component.
 *
 * All five Schedule variants are offered. `weekly` is what a task most often
 * needs and a medication rarely does ("bins out on Tuesday"), and `none` puts
 * the task in the Backlog, which is how a commitment with no deadline gets
 * recorded without inventing a fake due date for it.
 *
 * **Nothing is armed.** A task appears on the timeline and delivers no
 * notification of its own, because Tier is derived from Kind and the task Tier
 * has no trigger yet (ADR 0003, issue #14). `rescheduleAll` is not called here
 * for that reason — `meds/new.tsx` does not call it either, relying on the
 * foreground reschedule in `_layout.tsx`, and for a task there would be nothing
 * for it to do.
 */

export default function NewTaskScreen(): ReactElement {
  const router = useRouter();
  const { isLoaded, isSignedIn, user } = useUser();

  const [name, setName] = useState<string>("");
  const [instructions, setInstructions] = useState<string>("");
  // `once` rather than `daily`: a task is a one-off far more often than a
  // medication is, and the default should be the common case rather than the
  // one inherited from the medication form.
  const [schedule, setSchedule] = useState<EditableSchedule>(() =>
    defaultSchedule("once")
  );
  const [saving, setSaving] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const { showToast, ToastContainer } = useToast();

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
      const trimmedInstructions = instructions.trim();

      await remindersRepo.create({
        userId,
        kind: "task",
        name: trimmedName,
        schedule,
        ...(trimmedInstructions.length > 0
          ? { instructions: trimmedInstructions }
          : {}),
      });
      showToast.success("Task added");
      // To the day, not to a task list: a scheduled task is now a row on the
      // timeline, and one with no time is in the Backlog, which the timeline
      // links to. Neither needs a list of its own.
      router.replace("/");
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Save failed";
      setError(message);
      setSaving(false);
      showToast.error("Couldn't save — will retry on next sync");
    }
  }, [userId, trimmedName, instructions, schedule, router, showToast]);

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
        <Text>Sign in to add a task.</Text>
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
        <Text style={styles.label}>What do you need to do?</Text>
        <TextInput
          style={styles.input}
          value={name}
          onChangeText={setName}
          placeholder="e.g. Call the pharmacy"
          placeholderTextColor="#999"
          autoCorrect={false}
        />

        <Text style={styles.label}>Notes (optional)</Text>
        <TextInput
          style={styles.input}
          value={instructions}
          onChangeText={setInstructions}
          placeholder="e.g. ask about the repeat prescription"
          placeholderTextColor="#999"
        />

        <Text style={styles.label}>Schedule type</Text>
        <ScheduleTypeChooser value={schedule.type} onChange={setSchedule} />

        <SchedulePicker value={schedule} onChange={setSchedule} />

        <Text style={styles.deliveryNote}>
          Tasks show up on your timeline. They don&rsquo;t sound an alarm the
          way a medication does.
        </Text>

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
            <Text style={styles.saveButtonText}>Save task</Text>
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
  deliveryNote: {
    color: "#666",
    fontSize: 13,
    marginTop: 20,
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
});
