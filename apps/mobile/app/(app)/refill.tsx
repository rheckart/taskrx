import { useUser } from "@clerk/clerk-expo";
import type { Reminder } from "@taskrx/shared";
import { DEFAULT_REFILL_REMINDER_DAYS } from "@taskrx/shared";
import { Link, useFocusEffect } from "expo-router";
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

import { NumberField } from "../../src/components/NumberField";
import { SkeletonList } from "../../src/components/Skeleton";
import * as remindersRepo from "../../src/db/repos/reminders";
import {
  refillDetail,
  refillHeadline,
  refillUrgency,
} from "../../src/format/refill";
import type { RefillRow } from "../../src/refill/read";
import { readRefills } from "../../src/refill/read";
import { useUnifiedSync } from "../../src/sync/pull";

/**
 * The Refill surface — "am I about to run out?"
 *
 * Every rule lives somewhere else. `projectRefill` in `@taskrx/shared` decides
 * how long a supply lasts and whether that is close enough to act on,
 * `planRefill` decides what gets raised, and `src/refill/read.ts` decides that
 * the Backlog is reconciled with the projection before anything is rendered.
 * This file picks none of that: it asks for a reading and writes it down. If a
 * judgement about what counts ever appears below, it is in the wrong file.
 *
 * ## Why this screen exists at all
 *
 * The refill planner was ported from `RefillPlanner.cs` and had never been
 * called by anything. It could not be: its threshold compared pills against
 * doses and so was blind to `pillsPerDose`, and it had no notion of a date at
 * all, so "how many days are left" — the thing a person actually wants — was not
 * a question it could answer. #17 replaced the threshold with a projection over
 * the Reminder's real Schedule, which is what made a surface worth building.
 *
 * ## What it will not say
 *
 * A medication whose supply nobody has recorded shows no number. Not a zero, not
 * "0 days" — those are the shape of an empty bottle, and the person has simply
 * never told the app how many they have. It gets a sentence saying what is
 * missing and the same box to put it right. That distinction is the whole reason
 * the projection returns a reason rather than a count.
 *
 * Tasks are absent, and not by omission here: `projectRefill` has nothing to say
 * about a Reminder that is not a medication, because an errand has no supply.
 */

/** How each row reads, from `src/format/refill.ts`, which is where the words live. */
function headline(row: RefillRow): string {
  return refillHeadline(row.medication, row.projection);
}

function detailLine(row: RefillRow): string {
  return refillDetail(row.medication, row.projection);
}

/**
 * Said only when there is something to say. A standing "you're fine" badge on
 * every row makes the two that are not fine harder to find, which is the one
 * job this screen has.
 */
function RefillBadge({
  row,
}: {
  readonly row: RefillRow;
}): ReactElement | null {
  const urgency = refillUrgency(row.projection);
  if (urgency === "none") return null;

  const urgent = urgency === "out";
  return (
    <View style={[styles.badge, urgent ? styles.badgeUrgent : null]}>
      <Text style={[styles.badgeText, urgent ? styles.badgeTextUrgent : null]}>
        {urgent ? "Out of stock" : "Reorder now"}
      </Text>
    </View>
  );
}

/**
 * What the read just did to the Backlog, said out loud.
 *
 * Both directions, not just the raise. Opening this screen can add a Reminder to
 * the Backlog and can take one away, and a Reminder that appears or vanishes
 * with no account of why is the app doing things behind somebody's back — the
 * withdrawal more so than the raise, because a task disappearing on its own is
 * the one a person would otherwise assume they had done.
 */
function BacklogChanges({
  raised,
  retired,
}: {
  readonly raised: readonly string[];
  readonly retired: readonly string[];
}): ReactElement | null {
  if (raised.length === 0 && retired.length === 0) return null;

  return (
    <View style={styles.raisedNote}>
      {raised.length === 0 ? null : (
        <Text style={styles.raisedTitle}>
          {raised.length === 1
            ? `Added "${raised[0] ?? ""}" to your backlog.`
            : `Added ${String(raised.length)} reminders to your backlog.`}
        </Text>
      )}
      {retired.length === 0 ? null : (
        <Text style={styles.raisedTitle}>
          {retired.length === 1
            ? `Cleared "${retired[0] ?? ""}" — you have enough now.`
            : `Cleared ${String(retired.length)} reminders you no longer need.`}
        </Text>
      )}
      <Link href="/backlog" style={styles.linkText}>
        Open the backlog
      </Link>
    </View>
  );
}

/** The three numbers a supply is projected from, as they are being edited. */
interface SupplyDraft {
  readonly pillsRemaining: number | undefined;
  readonly pillsPerDose: number | undefined;
  readonly refillReminderDays: number | undefined;
}

function SupplyEditor({
  medication,
  onSave,
  onCancel,
}: {
  readonly medication: Reminder;
  readonly onSave: (draft: SupplyDraft) => void;
  readonly onCancel: () => void;
}): ReactElement {
  const [draft, setDraft] = useState<SupplyDraft>({
    pillsRemaining: medication.pillsRemaining,
    pillsPerDose: medication.pillsPerDose,
    refillReminderDays: medication.refillReminderDays,
  });

  return (
    <View style={styles.editor}>
      <NumberField
        label="Pills remaining"
        initialValue={medication.pillsRemaining}
        onChange={(pillsRemaining) => {
          setDraft((current) => ({ ...current, pillsRemaining }));
        }}
      />
      <NumberField
        label="Pills per dose"
        initialValue={medication.pillsPerDose}
        onChange={(pillsPerDose) => {
          setDraft((current) => ({ ...current, pillsPerDose }));
        }}
      />
      <NumberField
        label="Remind me to reorder this many days ahead"
        initialValue={medication.refillReminderDays}
        onChange={(refillReminderDays) => {
          setDraft((current) => ({ ...current, refillReminderDays }));
        }}
      />
      {/*
        The default said out loud. An empty box normally means "no value", and
        for the other two fields it does — but a warning window has to be some
        number for a reminder to ever be raised, so a blank one is silently
        seven days. Leaving that unstated makes the box a lie about what is in
        force.
      */}
      <Text style={styles.editorHint}>
        Blank means {String(DEFAULT_REFILL_REMINDER_DAYS)} days.
      </Text>
      <View style={styles.editorButtons}>
        <Pressable onPress={onCancel} hitSlop={8} style={styles.editorButton}>
          <Text style={styles.linkText}>Cancel</Text>
        </Pressable>
        <Pressable
          onPress={() => {
            onSave(draft);
          }}
          hitSlop={8}
          style={styles.editorButton}
        >
          <Text style={styles.saveText}>Save</Text>
        </Pressable>
      </View>
    </View>
  );
}

function MedicationRow({
  row,
  editing,
  onEdit,
  onCancel,
  onSave,
}: {
  readonly row: RefillRow;
  readonly editing: boolean;
  readonly onEdit: () => void;
  readonly onCancel: () => void;
  readonly onSave: (draft: SupplyDraft) => void;
}): ReactElement {
  const { medication } = row;

  return (
    <View style={styles.row}>
      <View
        // `accessible` as well as the label: a plain View is not an
        // accessibility element, so a screen reader would walk the children and
        // read the name, the headline and the working as three unrelated
        // fragments. Grouped, the row is the sentence it looks like by eye.
        accessible
        accessibilityLabel={`${medication.name}: ${headline(row)}, ${detailLine(
          row
        )}`}
      >
        <View style={styles.rowHeader}>
          <Text style={styles.rowName}>{medication.name}</Text>
          <RefillBadge row={row} />
        </View>
        {medication.dosage !== undefined ? (
          <Text style={styles.rowDosage}>{medication.dosage}</Text>
        ) : null}
        <Text
          style={[
            styles.rowHeadline,
            row.projection.projected ? null : styles.rowHeadlineMuted,
          ]}
        >
          {headline(row)}
        </Text>
        <Text style={styles.rowDetail}>{detailLine(row)}</Text>
      </View>

      {editing ? (
        <SupplyEditor
          medication={medication}
          onSave={onSave}
          onCancel={onCancel}
        />
      ) : (
        <Pressable onPress={onEdit} hitSlop={8} style={styles.editLink}>
          {/*
            Named for the act rather than for the fields. Someone opens this
            because they have just come back from the pharmacy, and "update
            supply" describes the form while "I collected a refill" describes
            them.
          */}
          <Text style={styles.linkText}>I collected a refill</Text>
        </Pressable>
      )}
    </View>
  );
}

export default function RefillScreen(): ReactElement {
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;

  const [rows, setRows] = useState<readonly RefillRow[]>([]);
  const [raised, setRaised] = useState<readonly string[]>([]);
  const [retired, setRetired] = useState<readonly string[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Both halves: the medications are what is projected, and the Refill
  // Reminders raised from them are Reminders too.
  useUnifiedSync(userId);

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      setRows([]);
      setLoading(false);
      return;
    }

    try {
      const reading = await readRefills(userId);
      setRows(reading.rows);
      setRaised(reading.raised);
      setRetired(reading.retired);
      setError(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [userId]);

  /**
   * On focus, not on mount. Collecting a prescription is the thing this screen
   * exists around, and the count it came back to would otherwise still be the
   * one from before it was recorded.
   */
  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load])
  );

  const onRefresh = useCallback((): void => {
    setRefreshing(true);
    void load();
  }, [load]);

  const saveSupply = useCallback(
    (medicationId: string, draft: SupplyDraft): void => {
      setEditingId(null);
      void (async () => {
        try {
          // Every key present, so clearing a box clears the column: the repo
          // reads `"key" in patch` precisely so that "I no longer track this"
          // can be said at all.
          await remindersRepo.update(medicationId, {
            pillsRemaining: draft.pillsRemaining,
            pillsPerDose: draft.pillsPerDose,
            refillReminderDays: draft.refillReminderDays,
          });
          await load();
        } catch (err: unknown) {
          setError(err instanceof Error ? err.message : "Unknown error");
        }
      })();
    },
    [load]
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
        <Text>Sign in to see your supplies.</Text>
      </View>
    );
  }

  if (loading) {
    return <SkeletonList rows={5} linesPerRow={3} />;
  }

  if (error !== null) {
    return (
      <View style={styles.center}>
        <Text style={styles.errorText}>Failed to load: {error}</Text>
        <Text style={styles.emptyBody}>Pull down to try again.</Text>
      </View>
    );
  }

  return (
    <FlatList
      data={rows}
      keyExtractor={(row) => row.medication.id}
      renderItem={({ item }) => (
        <MedicationRow
          row={item}
          editing={editingId === item.medication.id}
          onEdit={() => {
            setEditingId(item.medication.id);
          }}
          onCancel={() => {
            setEditingId(null);
          }}
          onSave={(draft) => {
            saveSupply(item.medication.id, draft);
          }}
        />
      )}
      ListHeaderComponent={<BacklogChanges raised={raised} retired={retired} />}
      ListEmptyComponent={
        <View style={styles.center}>
          <Text style={styles.emptyTitle}>No medications yet</Text>
          {/*
            Said here rather than left to be inferred: a person looking for a
            task they added will otherwise read this screen as broken.
          */}
          <Text style={styles.emptyBody}>
            Supplies are a medication thing — a task has nothing to run out of.
          </Text>
        </View>
      }
      contentContainerStyle={
        rows.length === 0 ? styles.emptyContent : styles.listContent
      }
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
      }
    />
  );
}

const styles = StyleSheet.create({
  badge: {
    backgroundColor: "#fff4e5",
    borderRadius: 10,
    paddingHorizontal: 8,
    paddingVertical: 2,
  },
  badgeText: {
    color: "#8a6d00",
    fontSize: 12,
    fontWeight: "700",
  },
  badgeTextUrgent: {
    color: "#c00",
  },
  badgeUrgent: {
    backgroundColor: "#fdeaea",
  },
  center: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    padding: 24,
  },
  editLink: {
    marginTop: 10,
  },
  editor: {
    borderTopColor: "#eee",
    borderTopWidth: 1,
    marginTop: 12,
    paddingTop: 4,
  },
  editorButton: {
    paddingVertical: 8,
  },
  editorButtons: {
    flexDirection: "row",
    gap: 24,
    marginTop: 16,
  },
  editorHint: {
    color: "#666",
    fontSize: 12,
    marginTop: 4,
  },
  emptyBody: {
    color: "#666",
    marginTop: 8,
    textAlign: "center",
  },
  emptyContent: {
    flexGrow: 1,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: "600",
  },
  errorText: {
    color: "#c00",
    textAlign: "center",
  },
  linkText: {
    color: "#007aff",
    fontSize: 14,
    fontWeight: "500",
  },
  listContent: {
    paddingBottom: 24,
  },
  raisedNote: {
    backgroundColor: "#f5f5f5",
    gap: 4,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  raisedTitle: {
    color: "#444",
    fontSize: 14,
    fontWeight: "600",
  },
  row: {
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  rowDetail: {
    color: "#666",
    fontSize: 13,
    marginTop: 2,
  },
  rowDosage: {
    color: "#666",
    fontSize: 14,
    marginTop: 2,
  },
  rowHeader: {
    alignItems: "center",
    flexDirection: "row",
    gap: 8,
    justifyContent: "space-between",
  },
  rowHeadline: {
    fontSize: 15,
    fontWeight: "600",
    marginTop: 6,
  },
  // Muted, because it is the absence of a reading rather than a bad one. A
  // supply nobody recorded must not draw the eye the way three days left does.
  rowHeadlineMuted: {
    color: "#999",
    fontWeight: "500",
  },
  rowName: {
    flexShrink: 1,
    fontSize: 16,
    fontWeight: "600",
  },
  saveText: {
    color: "#007aff",
    fontSize: 15,
    fontWeight: "700",
  },
});
