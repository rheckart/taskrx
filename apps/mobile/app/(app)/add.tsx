import { useUser } from "@clerk/clerk-expo";
import type {
  Priority,
  ReminderDraft,
  ReminderKind,
  RxNormSearchResult,
} from "@taskrx/shared";
import {
  PRIORITIES,
  isSavableDraft,
  readPhrase,
  withKind,
  withName,
} from "@taskrx/shared";
import { useRouter } from "expo-router";
import { useCallback, useState } from "react";
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

import { DrugSearch } from "../../src/components/DrugSearch";
import { NumberField } from "../../src/components/NumberField";
import {
  SchedulePicker,
  ScheduleTypeChooser,
  type EditableSchedule,
} from "../../src/components/SchedulePicker";
import { useToast } from "../../src/components/useToast";
import * as remindersRepo from "../../src/db/repos/reminders";
import { describeSchedule } from "../../src/format/schedule";

/**
 * Type a phrase, get the right Reminder — the primary creation surface for both
 * Kinds (issue #15).
 *
 * The engine is `readPhrase` in `@taskrx/shared`, over the parser that has been
 * in the repository, fully tested and entirely uncalled, since the port. This
 * screen adds no grammar of its own: it collects a phrase, hands it over, shows
 * what came back, and writes what the person agreed to. Every rule it appears to
 * apply — which Kind a phrase is, what a failed read falls back to, what a Kind
 * correction may not carry across, whether a draft can be saved — is a function
 * in the shared package with a case pinning it.
 *
 * **Nothing is saved until it has been seen.** The parser is a small
 * deterministic grammar, so it is confidently wrong on the phrases it does not
 * cover, and a medication saved with a misread dose is not a cosmetic failure.
 * The draft is therefore shown as a form, not as a confirmation dialog: every
 * field the parser filled in is the field you correct, in place, without
 * retyping the phrase.
 *
 * **It never dead-ends.** `readPhrase` returns a draft even for the two inputs
 * the parser refuses, so "I could not read that" is a message above a form that
 * is already open, not a wall. That is why there is no separate manual-entry
 * route out of here.
 *
 * **It works with no signal.** The parse is a pure function over the phrase and
 * a clock — no model call, no date library, no network. The one thing here that
 * does need connectivity is the RxNorm search, which is optional, opt-in, and
 * only offered once a draft is already a medication.
 *
 * One thing to know about, recorded here rather than discovered later: this is
 * the first caller to pass `priority` to `remindersRepo.create`, and that
 * module's `enqueueExternalWrite` comment explains why that had been avoided —
 * a `reminders` row cannot say whether its `priority` was chosen or defaulted,
 * so a recovery through `reconcilePending` sends `null` and leaves the server's
 * copy alone. The cost is bounded and worth paying: it applies only when an
 * enqueue fails after its row write, only to `priority`, which no scheduling
 * decision reads (ADR 0003 derives Tier from Kind), and the device keeps the
 * chosen value either way. Dropping a typed "urgent" on the floor to avoid it
 * would be the larger lie.
 */

const KINDS: readonly { readonly key: ReminderKind; readonly label: string }[] =
  [
    { key: "medication", label: "Medication" },
    { key: "task", label: "Task" },
  ];

/**
 * The words the parser itself recognises, so the button says back what you
 * could have typed.
 *
 * `tryParsePriority` reads "urgent" as `critical` and "important" as `high`;
 * labelling the buttons with the stored enum instead would show someone who
 * typed "urgent" a selected chip reading "Critical", i.e. a word they did not
 * use for a level they did not name. The domain vocabulary is unaffected — this
 * is the label, not the value.
 */
const PRIORITY_LABELS: Readonly<Record<Priority, string>> = {
  critical: "Urgent",
  high: "Important",
  normal: "Normal",
  low: "Low",
};

/**
 * Everything that differs between the two Kinds, said once.
 *
 * The alternative is what this file had: eight `isMedication ? … : …` ternaries
 * scattered through the form, so adding a Kind-specific word meant finding them
 * all, and reading the medication wording meant reading it interleaved with the
 * task wording. A Kind is not a branch here — it is a column in a table.
 */
const COPY: Readonly<
  Record<
    ReminderKind,
    {
      readonly namePlaceholder: string;
      readonly notesLabel: string;
      readonly notesPlaceholder: string;
      readonly saveButton: string;
      readonly savedToast: string;
    }
  >
> = {
  medication: {
    namePlaceholder: "e.g. Metformin",
    notesLabel: "Instructions",
    notesPlaceholder: "e.g. with food",
    saveButton: "Save medication",
    savedToast: "Medication added",
  },
  task: {
    namePlaceholder: "e.g. Call the pharmacy",
    notesLabel: "Notes",
    notesPlaceholder: "e.g. ask about the repeat",
    saveButton: "Save task",
    savedToast: "Task added",
  },
};

/** Whether the phrase named any part of the supply picture. */
function namesSupply(draft: ReminderDraft): boolean {
  return (
    draft.pillsRemaining !== undefined ||
    draft.pillsPerDose !== undefined ||
    draft.refillReminderDays !== undefined
  );
}

const EXAMPLES: readonly string[] = [
  "atorvastatin 20mg every morning with food",
  "call pharmacy friday 2pm",
  "metformin 500mg twice daily 30 pills",
];

/**
 * A row of mutually exclusive buttons over a small closed set.
 *
 * Kind and Priority are both "pick one of a handful", both need the selected
 * state spoken to a screen reader rather than shown only as a fill colour, and
 * `ScheduleTypeChooser` — which is the same widget for the same reason — lives
 * in `SchedulePicker` because it is bound to that component's value. This is
 * the general one; it is generic over the key so a chooser cannot be wired to a
 * value from a different set.
 */
function ChoiceRow<T extends string>({
  options,
  value,
  onChange,
}: {
  readonly options: readonly { readonly key: T; readonly label: string }[];
  readonly value: T;
  readonly onChange: (next: T) => void;
}): ReactElement {
  return (
    <View style={styles.choiceRow}>
      {options.map(({ key, label }) => (
        <Pressable
          key={key}
          onPress={() => {
            onChange(key);
          }}
          style={[
            styles.choiceButton,
            value === key ? styles.choiceButtonActive : null,
          ]}
          accessibilityRole="radio"
          accessibilityState={{ selected: value === key }}
        >
          <Text
            style={[
              styles.choiceButtonText,
              value === key ? styles.choiceButtonTextActive : null,
            ]}
          >
            {label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

export default function AddScreen(): ReactElement {
  const router = useRouter();
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;

  const [phrase, setPhrase] = useState<string>("");
  const [draft, setDraft] = useState<ReminderDraft | null>(null);
  /** Whether `draft` is what the parser read, or the blank one it fell back to. */
  const [understood, setUnderstood] = useState<boolean>(false);
  const [searchOpen, setSearchOpen] = useState<boolean>(false);
  /**
   * Whether to offer the supply boxes for a medication the phrase said nothing
   * about. A count the parser missed is as misread as one it got wrong, so
   * there has to be a way to it; a form that opens with three empty count boxes
   * on it is a different screen than this one.
   */
  const [supplyOpen, setSupplyOpen] = useState<boolean>(false);
  const [saving, setSaving] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const { showToast, ToastContainer } = useToast();

  const patch = useCallback((fields: Partial<ReminderDraft>): void => {
    setDraft((current) =>
      current === null ? current : { ...current, ...fields }
    );
  }, []);

  const onRead = useCallback((): void => {
    // `Date.now()` per press, and that is the right clock rather than one
    // captured with the phrase. It anchors an `every N hours` schedule, which
    // means "starting now" — so a phrase reviewed, backed out of and reviewed
    // again ten minutes later starts ten minutes later, which is what the
    // person just asked for. The parse is still a pure function of the two.
    const next = readPhrase(phrase, { now: Date.now() });
    setDraft(next.draft);
    setUnderstood(next.understood);
    setSearchOpen(false);
    setSupplyOpen(namesSupply(next.draft));
    setError(null);
  }, [phrase]);

  /** Back to the phrase, with the phrase still in the box. */
  const onEditPhrase = useCallback((): void => {
    setDraft(null);
    setSearchOpen(false);
  }, []);

  // What a task may not carry — the dosage, the supply counts, the RxNorm
  // identity — is `withKind`'s to drop, not this screen's. All that is left here
  // is closing the two medication-only panels, which is presentation.
  const onKindChange = useCallback((kind: ReminderKind): void => {
    setDraft((current) =>
      current === null ? current : withKind(current, kind)
    );
    if (kind === "task") {
      setSearchOpen(false);
      setSupplyOpen(false);
    }
  }, []);

  // Name and identity set together, in one patch. They are two halves of one
  // claim, and `withName` exists precisely to break them apart again the moment
  // the name is typed over by hand.
  const onPickDrug = useCallback(
    (result: RxNormSearchResult): void => {
      patch({ name: result.name, rxcui: result.rxcui });
      setSearchOpen(false);
    },
    [patch]
  );

  const onSave = useCallback(async (): Promise<void> => {
    if (userId === undefined || draft === null) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const { pillsRemaining, pillsPerDose, refillReminderDays, rxcui } = draft;
      // Trimmed at the boundary rather than on every keystroke, so that a space
      // typed between two words is not eaten as it is typed.
      const dosage = draft.dosage?.trim();
      const instructions = draft.instructions?.trim();

      await remindersRepo.create({
        userId,
        kind: draft.kind,
        name: draft.name.trim(),
        schedule: draft.schedule,
        priority: draft.priority,
        ...(rxcui === undefined ? {} : { rxcui }),
        ...(dosage === undefined ? {} : { dosage }),
        ...(instructions === undefined ? {} : { instructions }),
        ...(pillsRemaining === undefined ? {} : { pillsRemaining }),
        ...(pillsPerDose === undefined ? {} : { pillsPerDose }),
        ...(refillReminderDays === undefined ? {} : { refillReminderDays }),
      });

      showToast.success(COPY[draft.kind].savedToast);
      // To the day, for both Kinds. One with no time lands in the Backlog, which
      // the timeline links to.
      router.replace("/");
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Save failed";
      setError(message);
      setSaving(false);
      showToast.error("Couldn't save — will retry on next sync");
    }
  }, [userId, draft, router, showToast]);

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
        <Text>Sign in to add a reminder.</Text>
      </View>
    );
  }

  if (draft === null) {
    return (
      <ScrollView
        style={styles.container}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.label}>What do you need to remember?</Text>
        <TextInput
          autoFocus
          multiline
          style={[styles.input, styles.phraseInput]}
          value={phrase}
          onChangeText={setPhrase}
          placeholder="e.g. atorvastatin 20mg every morning with food"
          placeholderTextColor="#999"
          autoCapitalize="none"
          autoCorrect={false}
        />
        <Text style={styles.hint}>
          Say it however you like — a medication or something to do.
          You&rsquo;ll see what was understood before anything is saved.
        </Text>

        <View style={styles.examples}>
          {EXAMPLES.map((example) => (
            <Pressable
              key={example}
              onPress={() => {
                setPhrase(example);
              }}
              style={styles.exampleRow}
              accessibilityRole="button"
              accessibilityLabel={`Use the example: ${example}`}
            >
              <Text style={styles.exampleText}>{example}</Text>
            </Pressable>
          ))}
        </View>

        <Pressable
          onPress={onRead}
          disabled={phrase.trim().length === 0}
          style={[
            styles.primaryButton,
            phrase.trim().length === 0 ? styles.primaryButtonDisabled : null,
          ]}
        >
          <Text style={styles.primaryButtonText}>Review</Text>
        </Pressable>
      </ScrollView>
    );
  }

  const isMedication = draft.kind === "medication";
  const canSave = isSavableDraft(draft);
  const copy = COPY[draft.kind];

  return (
    <>
      <ToastContainer />
      <ScrollView
        style={styles.container}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.phraseCard}>
          <Text style={styles.phraseCardLabel}>You typed</Text>
          <Text style={styles.phraseCardText}>{phrase.trim()}</Text>
          <Pressable onPress={onEditPhrase} hitSlop={8}>
            <Text style={styles.linkText}>Edit phrase</Text>
          </Pressable>
        </View>

        {understood ? (
          <Text style={styles.understoodNote}>
            Here&rsquo;s what that was understood as. Change anything that
            isn&rsquo;t right.
          </Text>
        ) : (
          <View style={styles.notUnderstoodCard}>
            <Text style={styles.notUnderstoodTitle}>
              That phrase couldn&rsquo;t be read
            </Text>
            {/*
              Our words, not the parser's. `PhraseReading.reasons` carries
              strings ported from the C# for callers to reason about ("Could not
              determine a name for the reminder."); putting one in front of a
              person tells them how the grammar failed, when the only thing they
              can act on is the form already open below.
            */}
            <Text style={styles.notUnderstoodBody}>
              Nothing has been saved. Fill it in below instead — or go back and
              try a phrase with a name in it.
            </Text>
          </View>
        )}

        <Text style={styles.label}>Kind</Text>
        <ChoiceRow options={KINDS} value={draft.kind} onChange={onKindChange} />

        <Text style={styles.label}>Name</Text>
        <TextInput
          style={styles.input}
          value={draft.name}
          onChangeText={(name) => {
            // Through `withName`, so that typing over a name picked from RxNorm
            // takes the concept id with it rather than leaving the row claiming
            // an identity for a name nobody checked.
            setDraft((current) =>
              current === null ? current : withName(current, name)
            );
          }}
          placeholder={copy.namePlaceholder}
          placeholderTextColor="#999"
          autoCorrect={false}
        />

        {isMedication ? (
          <>
            <Text style={styles.label}>Dosage</Text>
            <TextInput
              style={styles.input}
              value={draft.dosage ?? ""}
              onChangeText={(next) => {
                patch({ dosage: next.trim() === "" ? undefined : next });
              }}
              placeholder="e.g. 500mg"
              placeholderTextColor="#999"
              autoCorrect={false}
            />

            {draft.rxcui === undefined ? null : (
              <Text style={styles.rxcui}>RxCUI: {draft.rxcui}</Text>
            )}

            {/*
              Drug search stays available for a medication, and only for one.
              It is opt-in rather than always shown because the phrase has
              usually already named the drug — this is for pinning down which
              one, or for a name the parser read but nobody spells the same way
              twice. Picking a result replaces the name in place; the rest of
              the draft, including the schedule that took a sentence to say, is
              untouched.
            */}
            <Pressable
              onPress={() => {
                setSearchOpen((open) => !open);
              }}
              hitSlop={8}
              style={styles.searchToggle}
            >
              <Text style={styles.linkText}>
                {searchOpen ? "Hide drug search" : "Look this up in RxNorm"}
              </Text>
            </Pressable>

            {searchOpen ? (
              <View style={styles.searchPanel}>
                <DrugSearch onPick={onPickDrug} initialQuery={draft.name} />
              </View>
            ) : null}
          </>
        ) : null}

        <Text style={styles.label}>{copy.notesLabel}</Text>
        <TextInput
          style={styles.input}
          value={draft.instructions ?? ""}
          onChangeText={(next) => {
            patch({ instructions: next.trim() === "" ? undefined : next });
          }}
          placeholder={copy.notesPlaceholder}
          placeholderTextColor="#999"
        />

        <Text style={styles.label}>Priority</Text>
        <ChoiceRow
          options={PRIORITIES.map((priority) => ({
            key: priority,
            label: PRIORITY_LABELS[priority],
          }))}
          value={draft.priority}
          onChange={(priority) => {
            patch({ priority });
          }}
        />

        <Text style={styles.label}>Schedule</Text>
        <Text style={styles.scheduleSummary}>
          {describeSchedule(draft.schedule)}
        </Text>
        <ScheduleTypeChooser
          value={draft.schedule.type}
          onChange={(schedule: EditableSchedule) => {
            patch({ schedule });
          }}
        />
        <SchedulePicker
          value={draft.schedule}
          onChange={(schedule: EditableSchedule) => {
            patch({ schedule });
          }}
        />

        {/*
          Supply, for a medication.

          Shown outright when the phrase named any of the three, because those
          are readings and a reading is exactly what this screen is for putting
          right. Behind one tap when it named none — a misread is not only a
          number read wrongly, it is also "30 pills" that landed in the unused
          words, and a form with no way to that number would make the parser's
          silence unappealable. What the tap does not do is open three empty
          boxes on arrival, which would read as six fields to fill in and is the
          thing this whole surface exists to replace.
        */}
        {isMedication ? (
          supplyOpen ? (
            <>
              <NumberField
                label="Pills remaining"
                initialValue={draft.pillsRemaining}
                onChange={(pillsRemaining) => {
                  patch({ pillsRemaining });
                }}
              />
              <NumberField
                label="Pills per dose"
                initialValue={draft.pillsPerDose}
                onChange={(pillsPerDose) => {
                  patch({ pillsPerDose });
                }}
              />
              <NumberField
                label="Remind me to refill this many days ahead"
                initialValue={draft.refillReminderDays}
                onChange={(refillReminderDays) => {
                  patch({ refillReminderDays });
                }}
              />
            </>
          ) : (
            <Pressable
              onPress={() => {
                setSupplyOpen(true);
              }}
              hitSlop={8}
              style={styles.searchToggle}
            >
              <Text style={styles.linkText}>Add supply and refill details</Text>
            </Pressable>
          )
        ) : null}

        {/*
          Said out loud rather than dropped. The parser never rejects a phrase —
          it parses around what it cannot place — so without this, a word that
          had no effect looks exactly like a word that did.
        */}
        {draft.unparsedTokens.length === 0 ? null : (
          <View style={styles.unparsedCard}>
            <Text style={styles.unparsedTitle}>
              Not used: {draft.unparsedTokens.join(", ")}
            </Text>
            <Text style={styles.unparsedBody}>
              Those words weren&rsquo;t understood, so nothing above came from
              them. Add them to the name or the notes if they matter.
            </Text>
          </View>
        )}

        {draft.kind === "task" ? (
          <Text style={styles.deliveryNote}>
            Tasks show up on your timeline. They don&rsquo;t sound an alarm the
            way a medication does.
          </Text>
        ) : null}

        {error !== null ? (
          <Text style={styles.errorText}>Save failed: {error}</Text>
        ) : null}

        <Pressable
          onPress={() => void onSave()}
          disabled={!canSave || saving}
          style={[
            styles.primaryButton,
            !canSave || saving ? styles.primaryButtonDisabled : null,
          ]}
        >
          {saving ? (
            <ActivityIndicator color="#fff" />
          ) : (
            <Text style={styles.primaryButtonText}>{copy.saveButton}</Text>
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
  choiceButton: {
    borderColor: "#ccc",
    borderRadius: 6,
    borderWidth: 1,
    marginBottom: 8,
    marginRight: 8,
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  choiceButtonActive: {
    backgroundColor: "#007aff",
    borderColor: "#007aff",
  },
  choiceButtonText: {
    color: "#333",
    fontSize: 14,
    fontWeight: "500",
  },
  choiceButtonTextActive: {
    color: "#fff",
  },
  choiceRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    marginTop: 4,
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
  exampleRow: {
    paddingVertical: 6,
  },
  exampleText: {
    color: "#007aff",
    fontSize: 14,
  },
  examples: {
    marginTop: 16,
  },
  hint: {
    color: "#666",
    fontSize: 13,
    marginTop: 8,
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
  linkText: {
    color: "#007aff",
    fontSize: 14,
    fontWeight: "500",
  },
  notUnderstoodBody: {
    color: "#5c4400",
    fontSize: 13,
    marginTop: 4,
  },
  notUnderstoodCard: {
    backgroundColor: "#fdf3d8",
    borderRadius: 8,
    marginTop: 16,
    padding: 12,
  },
  notUnderstoodTitle: {
    color: "#8a6d00",
    fontSize: 14,
    fontWeight: "700",
  },
  phraseCard: {
    backgroundColor: "#f5f7fa",
    borderRadius: 8,
    padding: 12,
  },
  phraseCardLabel: {
    color: "#666",
    fontSize: 12,
    fontWeight: "600",
    letterSpacing: 0.3,
    textTransform: "uppercase",
  },
  phraseCardText: {
    fontSize: 16,
    marginBottom: 8,
    marginTop: 4,
  },
  phraseInput: {
    minHeight: 88,
    textAlignVertical: "top",
  },
  primaryButton: {
    alignItems: "center",
    backgroundColor: "#007aff",
    borderRadius: 8,
    marginTop: 24,
    paddingVertical: 14,
  },
  primaryButtonDisabled: {
    backgroundColor: "#aac8ee",
  },
  primaryButtonText: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "600",
  },
  rxcui: {
    color: "#666",
    fontSize: 13,
    marginTop: 8,
  },
  scheduleSummary: {
    color: "#333",
    fontSize: 15,
    marginBottom: 8,
    marginTop: 4,
  },
  searchPanel: {
    borderColor: "#eee",
    borderRadius: 8,
    borderWidth: 1,
    height: 280,
    marginTop: 8,
    overflow: "hidden",
  },
  searchToggle: {
    marginTop: 12,
  },
  understoodNote: {
    color: "#666",
    fontSize: 13,
    marginTop: 12,
  },
  unparsedBody: {
    color: "#5c4400",
    fontSize: 13,
    marginTop: 4,
  },
  unparsedCard: {
    backgroundColor: "#fdf3d8",
    borderRadius: 8,
    marginTop: 20,
    padding: 12,
  },
  unparsedTitle: {
    color: "#8a6d00",
    fontSize: 14,
    fontWeight: "700",
  },
});
