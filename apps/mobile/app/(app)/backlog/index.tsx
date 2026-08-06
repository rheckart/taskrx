import { useUser } from "@clerk/clerk-expo";
import type { Reminder, ReminderInstance } from "@taskrx/shared";
import { isBacklog, planBacklogCompletion } from "@taskrx/shared";
import { useFocusEffect } from "expo-router";
import { useCallback, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from "react-native";

import {
  SchedulePicker,
  defaultSchedule,
  isWellFormedSchedule,
  type EditableSchedule,
} from "../../../src/components/SchedulePicker";
import { SkeletonList } from "../../../src/components/Skeleton";
import { useToast } from "../../../src/components/useToast";
import * as instancesRepo from "../../../src/db/repos/reminderInstances";
import * as remindersRepo from "../../../src/db/repos/reminders";
import { formatDayTime } from "../../../src/format/datetime";

/**
 * The Backlog — commitments with no moment attached.
 *
 * A Reminder whose Schedule is `none` produces no Occurrences, so it never
 * appears on the timeline and cannot be reached from it. This surface is
 * rendered from the Reminder rows themselves, which is exactly what ADR 0002
 * says it must be: a Backlog Reminder is invisible to any query over Instances
 * until it is acted on.
 *
 * The two things that can be done here are the two the Backlog is for:
 *
 *   * **Complete it.** `planBacklogCompletion` in `@taskrx/shared` decides what
 *     Instance that should produce — minted at the moment of the act, with its
 *     scheduled time set to the completion time (ADR 0002). This screen persists
 *     that decision and decides none of it.
 *   * **Give it a time.** Setting any other Schedule moves it onto the timeline,
 *     which is the same write `meds/new.tsx` makes and needs no special path.
 *
 * Kind-neutral, so it lists a medication taken as needed beside a task with no
 * deadline. Completing one leaves the Reminder in the Backlog rather than
 * retiring it: the Instance records the act, and nothing in the domain yet says
 * whether an unscheduled commitment is one-shot or standing. A person who is
 * done with it for good stops the Reminder itself.
 */

interface BacklogEntry {
  readonly reminder: Reminder;
  readonly lastCompleted: ReminderInstance | null;
}

function formatCompletedAt(instance: ReminderInstance | null): string | null {
  return instance?.completedAt === undefined
    ? null
    : formatDayTime(instance.completedAt);
}

function BacklogRow({
  entry,
  onComplete,
  onSchedule,
}: {
  readonly entry: BacklogEntry;
  readonly onComplete: (reminder: Reminder) => void;
  readonly onSchedule: (reminder: Reminder, schedule: EditableSchedule) => void;
}): ReactElement {
  const [schedule, setSchedule] = useState<EditableSchedule | null>(null);
  const lastDone = formatCompletedAt(entry.lastCompleted);
  const { reminder } = entry;
  // `none` is well formed, but "move it to the timeline" with no time is a
  // no-op dressed as an action.
  const canMove =
    schedule !== null &&
    isWellFormedSchedule(schedule) &&
    schedule.type !== "none";

  return (
    <View style={styles.row}>
      <Text style={styles.rowName}>{reminder.name}</Text>
      {reminder.dosage !== undefined ? (
        <Text style={styles.rowDosage}>{reminder.dosage}</Text>
      ) : null}
      <Text style={styles.rowMeta}>
        {reminder.kind === "medication" ? "Medication" : "Task"}
        {lastDone === null ? "" : ` · Last done ${lastDone}`}
      </Text>

      <View style={styles.rowActions}>
        <Pressable
          onPress={() => {
            onComplete(reminder);
          }}
          style={[styles.actionButton, styles.completeButton]}
          accessibilityRole="button"
          accessibilityLabel={`Complete ${reminder.name}`}
        >
          <Text style={styles.actionButtonText}>
            {reminder.kind === "medication" ? "Taken" : "Done"}
          </Text>
        </Pressable>

        <Pressable
          onPress={() => {
            setSchedule((current) =>
              current === null ? defaultSchedule("once") : null
            );
          }}
          style={[styles.actionButton, styles.scheduleButton]}
          accessibilityRole="button"
          accessibilityLabel={`Give ${reminder.name} a time`}
        >
          <Text style={styles.actionButtonText}>
            {schedule === null ? "Give it a time" : "Cancel"}
          </Text>
        </Pressable>
      </View>

      {schedule === null ? null : (
        <View style={styles.editor}>
          <SchedulePicker value={schedule} onChange={setSchedule} />
          <Pressable
            disabled={!canMove}
            onPress={() => {
              onSchedule(reminder, schedule);
              setSchedule(null);
            }}
            style={[
              styles.actionButton,
              styles.saveButton,
              canMove ? null : styles.actionButtonDisabled,
            ]}
          >
            <Text style={styles.actionButtonText}>Move to timeline</Text>
          </Pressable>
        </View>
      )}
    </View>
  );
}

export default function BacklogScreen(): ReactElement {
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;

  const [entries, setEntries] = useState<readonly BacklogEntry[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const { showToast, ToastContainer } = useToast();

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      setEntries([]);
      setLoading(false);
      return;
    }

    try {
      // Both Kinds, active only: a stopped reminder is not an outstanding
      // commitment. `isBacklog` is the shared rule, so an empty daily schedule
      // — the misconfiguration `none` replaced — does not drift in here.
      const reminders = await remindersRepo.list(userId);
      const backlog = reminders.filter(isBacklog);

      // Concurrently: the Backlog is small, and one round trip per row in
      // series is the shape that makes a short list feel slow.
      setEntries(
        await Promise.all(
          backlog.map(async (reminder) => ({
            reminder,
            lastCompleted: await instancesRepo.lastCompletedFor(
              userId,
              reminder.id
            ),
          }))
        )
      );
      setError(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [userId]);

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load])
  );

  const onRefresh = useCallback((): void => {
    setRefreshing(true);
    void load();
  }, [load]);

  const onComplete = useCallback(
    (reminder: Reminder): void => {
      if (userId === undefined) {
        return;
      }

      void (async (): Promise<void> => {
        try {
          const plan = planBacklogCompletion(reminder, Date.now());
          await instancesRepo.create({
            userId,
            reminderId: plan.reminderId,
            reminderKind: plan.reminderKind,
            scheduledTime: plan.scheduledTime,
            status: plan.status,
            completedAt: plan.completedAt,
          });
          showToast.success("Recorded");
          await load();
        } catch (err: unknown) {
          showToast.error(
            err instanceof Error ? err.message : "Couldn't record it"
          );
        }
      })();
    },
    [userId, showToast, load]
  );

  const onSchedule = useCallback(
    (reminder: Reminder, schedule: EditableSchedule): void => {
      void (async (): Promise<void> => {
        try {
          await remindersRepo.update(reminder.id, { schedule });
          showToast.success("Moved to your timeline");
          await load();
        } catch (err: unknown) {
          showToast.error(
            err instanceof Error ? err.message : "Couldn't schedule it"
          );
        }
      })();
    },
    [showToast, load]
  );

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
        <Text>Sign in to see your backlog.</Text>
      </View>
    );
  }

  return (
    <>
      <ToastContainer />
      {loading ? (
        <SkeletonList rows={4} linesPerRow={2} />
      ) : error !== null ? (
        <View style={styles.center}>
          <Text style={styles.errorText}>Failed to load: {error}</Text>
        </View>
      ) : entries.length === 0 ? (
        <View style={styles.center}>
          <Text style={styles.emptyTitle}>Nothing in your backlog</Text>
          <Text style={styles.emptyBody}>
            Anything you add without a time waits here until you get to it.
          </Text>
        </View>
      ) : (
        <FlatList
          data={entries}
          keyExtractor={(entry) => entry.reminder.id}
          renderItem={({ item }) => (
            <BacklogRow
              entry={item}
              onComplete={onComplete}
              onSchedule={onSchedule}
            />
          )}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
          }
        />
      )}
    </>
  );
}

const styles = StyleSheet.create({
  actionButton: {
    alignItems: "center",
    borderRadius: 8,
    flex: 1,
    paddingVertical: 10,
  },
  actionButtonDisabled: {
    opacity: 0.5,
  },
  actionButtonText: {
    color: "#fff",
    fontSize: 15,
    fontWeight: "600",
  },
  center: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    padding: 24,
  },
  completeButton: {
    backgroundColor: "#34c759",
  },
  editor: {
    borderTopColor: "#eee",
    borderTopWidth: 1,
    marginTop: 12,
    paddingTop: 4,
  },
  emptyBody: {
    color: "#666",
    marginTop: 8,
    textAlign: "center",
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: "600",
  },
  errorText: {
    color: "#c00",
    textAlign: "center",
  },
  row: {
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  rowActions: {
    flexDirection: "row",
    gap: 12,
    marginTop: 12,
  },
  rowDosage: {
    color: "#666",
    fontSize: 14,
    marginTop: 2,
  },
  rowMeta: {
    color: "#888",
    fontSize: 13,
    marginTop: 4,
  },
  rowName: {
    fontSize: 16,
    fontWeight: "600",
  },
  saveButton: {
    backgroundColor: "#007aff",
    marginTop: 12,
  },
  scheduleButton: {
    backgroundColor: "#5856d6",
  },
});
