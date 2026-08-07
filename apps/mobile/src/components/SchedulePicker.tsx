import type {
  DailyTimes,
  IntervalTimes,
  NoneTimes,
  OnceTimes,
  Weekday,
  WeeklyTimes,
} from "@taskrx/shared";
import { isWellFormedSchedule } from "@taskrx/shared";
import { useCallback } from "react";
import type { ReactElement } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";

/**
 * Schedule picker for the reminder form.
 *
 * It speaks the domain union directly — one value, discriminated by its own
 * `type` field. It used to speak a POC pair (`DailySchedule` / `IntervalSchedule`
 * with no discriminator, told apart by a separate `scheduleType` prop) that the
 * form converted at save; `@taskrx/shared` carried those two shapes for this
 * component alone and recorded that they were "due to be replaced wholesale when
 * the picker grows the `once` and `none` variants". This is that.
 *
 * All five variants are editable here:
 *   - `daily`:    a list of HH:MM time slots
 *   - `weekly`:   a set of weekdays, and the same time slots on each
 *   - `interval`: an hours field and a start instant
 *   - `once`:     a single instant (ADR 0006 — an instant, never a bare date)
 *   - `none`:     nothing to edit; the reminder goes to the Backlog
 *
 * `weekly` was the last one without a UI. It arrives with the Task work rather
 * than earlier because it is what "a weekday-only commitment does not nag me on
 * Sunday" needs, and until a Task could exist there was nothing that shape of
 * commitment belonged to. Everything under it — the domain variant, the SQLite
 * column, the Convex validator, `nextOccurrences` — has supported it all along;
 * only the form could not say it.
 */

/** The schedule variants this component can edit — now the whole union. */
export type EditableSchedule =
  | DailyTimes
  | WeeklyTimes
  | IntervalTimes
  | OnceTimes
  | NoneTimes;

export type EditableScheduleType = EditableSchedule["type"];

/**
 * Whether a schedule can be saved. The rule itself is `isWellFormedSchedule` in
 * `@taskrx/shared` — what makes a schedule usable is a property of the schedule,
 * and both surfaces that set one ask the same question.
 */
export { isWellFormedSchedule };

export interface SchedulePickerProps {
  readonly value: EditableSchedule;
  readonly onChange: (next: EditableSchedule) => void;
}

const TIME_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/;

function isValidTime(value: string): boolean {
  return TIME_PATTERN.test(value);
}

function pad2(n: number): string {
  return n.toString().padStart(2, "0");
}

function localDateTimeInputToMs(value: string): number | null {
  const parsed = new Date(value).getTime();
  return Number.isFinite(parsed) ? parsed : null;
}

function msToLocalDateTimeInputValue(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear().toString().padStart(4, "0")}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/**
 * A fresh value of `type`, used when the person switches variant. Switching
 * replaces the value rather than carrying fields across, because the variants
 * share no field: an hours count means nothing to a single moment.
 *
 * These are presentation defaults — where the form starts someone off — not
 * domain rules, which is why they live here and `isWellFormedSchedule` does not.
 */
export function defaultSchedule(type: EditableScheduleType): EditableSchedule {
  switch (type) {
    case "daily":
      return { type: "daily", times: ["09:00"] };
    case "weekly":
      // Weekdays, which is what "weekly rather than daily" almost always means
      // — the person is excluding the weekend. Sunday is 0, matching both
      // `Date.getDay()` and the domain's `Weekday`.
      return { type: "weekly", daysOfWeek: [1, 2, 3, 4, 5], times: ["09:00"] };
    case "interval":
      return { type: "interval", hours: 8, startAt: Date.now() };
    case "once":
      // An hour out, so the moment is ahead of the person filling the form in
      // rather than already behind them.
      return { type: "once", at: Date.now() + 3_600_000 };
    case "none":
      return { type: "none" };
  }
}

/**
 * The variant chooser that sits above the picker.
 *
 * Both creation forms offer the same five variants under the same labels, and
 * both replace the value wholesale on a switch, so this was byte-identical in
 * each of them — the list, the row of `Pressable`s, and five styles. What
 * genuinely differs between a medication and a task is the fields around the
 * schedule, not the schedule itself.
 *
 * The order is the order a person is likely to want, not the order of the
 * union: recurring first, then the two that end.
 */
const SCHEDULE_TYPES: readonly {
  readonly type: EditableScheduleType;
  readonly label: string;
}[] = [
  { type: "daily", label: "Daily" },
  { type: "weekly", label: "Weekly" },
  { type: "interval", label: "Interval" },
  { type: "once", label: "Once" },
  { type: "none", label: "No time" },
];

export function ScheduleTypeChooser({
  value,
  onChange,
}: {
  readonly value: EditableScheduleType;
  /** Receives a fresh default value for the chosen variant, not the bare type. */
  readonly onChange: (next: EditableSchedule) => void;
}): ReactElement {
  return (
    <View style={styles.typeRow}>
      {SCHEDULE_TYPES.map(({ type, label }) => (
        <Pressable
          key={type}
          onPress={() => {
            onChange(defaultSchedule(type));
          }}
          style={[
            styles.typeButton,
            value === type ? styles.typeButtonActive : null,
          ]}
          accessibilityRole="radio"
          accessibilityState={{ selected: value === type }}
        >
          <Text
            style={[
              styles.typeButtonText,
              value === type ? styles.typeButtonTextActive : null,
            ]}
          >
            {label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

export function SchedulePicker({
  value,
  onChange,
}: SchedulePickerProps): ReactElement {
  switch (value.type) {
    case "daily":
      return <DailyPicker value={value} onChange={onChange} />;
    case "weekly":
      return <WeeklyPicker value={value} onChange={onChange} />;
    case "interval":
      return <IntervalPicker value={value} onChange={onChange} />;
    case "once":
      return <OncePicker value={value} onChange={onChange} />;
    case "none":
      return <NoneNotice />;
  }
}

/**
 * The list of `"HH:MM"` slots, shared by the two variants that carry one.
 *
 * `daily` and `weekly` differ only in which days the slots apply to, so the
 * editing, the validation hint and the add/remove affordances are written once
 * and each variant supplies what to do with the new list.
 */
function TimesEditor({
  times,
  onChange,
}: {
  readonly times: readonly string[];
  readonly onChange: (next: readonly string[]) => void;
}): ReactElement {
  const updateTime = useCallback(
    (index: number, next: string): void => {
      onChange(times.map((t, i) => (i === index ? next : t)));
    },
    [times, onChange]
  );

  const removeTime = useCallback(
    (index: number): void => {
      onChange(times.filter((_, i) => i !== index));
    },
    [times, onChange]
  );

  const addTime = useCallback((): void => {
    onChange([...times, "09:00"]);
  }, [times, onChange]);

  return (
    <View>
      <Text style={styles.label}>Reminder times (HH:MM, 24h)</Text>
      {times.length === 0 ? (
        <Text style={styles.hint}>
          No times yet — tap &ldquo;Add time&rdquo; below.
        </Text>
      ) : (
        times.map((t, i) => (
          <View key={`time-${i.toString()}`} style={styles.timeRow}>
            <TextInput
              style={styles.timeInput}
              value={t}
              onChangeText={(next) => {
                updateTime(i, next);
              }}
              placeholder="HH:MM"
              placeholderTextColor="#999"
              autoCorrect={false}
              autoCapitalize="none"
              keyboardType="numbers-and-punctuation"
              maxLength={5}
            />
            {!isValidTime(t) ? (
              <Text style={styles.invalidHint}>Use HH:MM (00:00–23:59)</Text>
            ) : null}
            <Pressable
              onPress={() => {
                removeTime(i);
              }}
              style={styles.removeButton}
              hitSlop={8}
            >
              <Text style={styles.removeButtonText}>Remove</Text>
            </Pressable>
          </View>
        ))
      )}
      <Pressable onPress={addTime} style={styles.addButton} hitSlop={8}>
        <Text style={styles.addButtonText}>+ Add time</Text>
      </Pressable>
    </View>
  );
}

function DailyPicker({
  value,
  onChange,
}: {
  readonly value: DailyTimes;
  readonly onChange: (next: EditableSchedule) => void;
}): ReactElement {
  const onTimesChange = useCallback(
    (times: readonly string[]): void => {
      onChange({ type: "daily", times });
    },
    [onChange]
  );

  return <TimesEditor times={value.times} onChange={onTimesChange} />;
}

/**
 * `Weekday` is `0 | 1 | ... | 6` and `Date.getDay()` uses the same ordinals, so
 * the index into this tuple is the domain value and needs no mapping.
 */
const WEEKDAYS: readonly { readonly day: Weekday; readonly label: string }[] = [
  { day: 0, label: "Sun" },
  { day: 1, label: "Mon" },
  { day: 2, label: "Tue" },
  { day: 3, label: "Wed" },
  { day: 4, label: "Thu" },
  { day: 5, label: "Fri" },
  { day: 6, label: "Sat" },
];

function WeeklyPicker({
  value,
  onChange,
}: {
  readonly value: WeeklyTimes;
  readonly onChange: (next: EditableSchedule) => void;
}): ReactElement {
  const { daysOfWeek, times } = value;

  const toggleDay = useCallback(
    (day: Weekday): void => {
      // Rebuilt from the canonical list rather than appended to, so the stored
      // order is always Sunday-first however the person tapped them in. Two
      // schedules covering the same days should not differ as JSON.
      const selected = new Set(daysOfWeek);
      if (selected.has(day)) {
        selected.delete(day);
      } else {
        selected.add(day);
      }
      onChange({
        type: "weekly",
        daysOfWeek: WEEKDAYS.map((w) => w.day).filter((d) => selected.has(d)),
        times,
      });
    },
    [daysOfWeek, times, onChange]
  );

  const onTimesChange = useCallback(
    (nextTimes: readonly string[]): void => {
      onChange({ type: "weekly", daysOfWeek, times: nextTimes });
    },
    [daysOfWeek, onChange]
  );

  return (
    <View>
      <Text style={styles.label}>Days</Text>
      <View style={styles.dayRow}>
        {WEEKDAYS.map(({ day, label }) => {
          const on = daysOfWeek.includes(day);
          return (
            <Pressable
              key={label}
              onPress={() => {
                toggleDay(day);
              }}
              style={[styles.dayButton, on ? styles.dayButtonOn : null]}
              accessibilityRole="checkbox"
              accessibilityState={{ checked: on }}
              accessibilityLabel={label}
            >
              <Text
                style={[
                  styles.dayButtonText,
                  on ? styles.dayButtonTextOn : null,
                ]}
              >
                {label}
              </Text>
            </Pressable>
          );
        })}
      </View>
      {daysOfWeek.length === 0 ? (
        <Text style={styles.invalidHint}>Pick at least one day.</Text>
      ) : null}

      <TimesEditor times={times} onChange={onTimesChange} />
    </View>
  );
}

/**
 * A local wall-clock instant, entered as text. Shared by the two variants that
 * carry one — an interval's start and a single moment — so the format, the
 * parse and the "that is in the past" warning are written once.
 */
function InstantField({
  label,
  hint,
  value,
  onChange,
}: {
  readonly label: string;
  readonly hint: string;
  readonly value: number;
  readonly onChange: (next: number) => void;
}): ReactElement {
  const onText = useCallback(
    (next: string): void => {
      const ms = localDateTimeInputToMs(next);
      if (ms === null) {
        return;
      }
      onChange(ms);
    },
    [onChange]
  );

  return (
    <View>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        style={styles.input}
        value={msToLocalDateTimeInputValue(value)}
        onChangeText={onText}
        placeholder="YYYY-MM-DDTHH:MM"
        placeholderTextColor="#999"
        autoCorrect={false}
        autoCapitalize="none"
      />
      {value <= Date.now() ? (
        <Text style={styles.invalidHint}>
          That is in the past — nothing will fire for it.
        </Text>
      ) : null}
      <Text style={styles.hint}>{hint}</Text>
    </View>
  );
}

function IntervalPicker({
  value,
  onChange,
}: {
  readonly value: IntervalTimes;
  readonly onChange: (next: EditableSchedule) => void;
}): ReactElement {
  const { hours, startAt } = value;

  const onHoursChange = useCallback(
    (next: string): void => {
      const parsed = Number.parseInt(next, 10);
      const safe = Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
      onChange({ type: "interval", hours: safe, startAt });
    },
    [startAt, onChange]
  );

  const onStartChange = useCallback(
    (startAt: number): void => {
      onChange({ type: "interval", hours, startAt });
    },
    [hours, onChange]
  );

  return (
    <View>
      <Text style={styles.label}>Interval (hours)</Text>
      <TextInput
        style={styles.input}
        value={hours.toString()}
        onChangeText={onHoursChange}
        keyboardType="number-pad"
        placeholder="8"
        placeholderTextColor="#999"
      />

      <InstantField
        label="First reminder at"
        hint={`Format: YYYY-MM-DDTHH:MM (local time). Reminders fire every ${hours.toString()} hour${hours === 1 ? "" : "s"} from this start.`}
        value={startAt}
        onChange={onStartChange}
      />
    </View>
  );
}

function OncePicker({
  value,
  onChange,
}: {
  readonly value: OnceTimes;
  readonly onChange: (next: EditableSchedule) => void;
}): ReactElement {
  const onAtChange = useCallback(
    (at: number): void => {
      onChange({ type: "once", at });
    },
    [onChange]
  );

  return (
    <InstantField
      label="Happens at"
      hint="Format: YYYY-MM-DDTHH:MM (local time). This reminds you once, then it is done."
      value={value.at}
      onChange={onAtChange}
    />
  );
}

function NoneNotice(): ReactElement {
  return (
    <View>
      <Text style={styles.hint}>
        No time at all. This goes to your Backlog, where you can complete it
        whenever you get to it — or give it a time later.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  addButton: {
    alignSelf: "flex-start",
    marginTop: 8,
    paddingVertical: 6,
  },
  addButtonText: {
    color: "#007aff",
    fontSize: 15,
    fontWeight: "500",
  },
  dayButton: {
    borderColor: "#ccc",
    borderRadius: 6,
    borderWidth: 1,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  dayButtonOn: {
    backgroundColor: "#007aff",
    borderColor: "#007aff",
  },
  dayButtonText: {
    color: "#333",
    fontSize: 14,
    fontWeight: "500",
  },
  dayButtonTextOn: {
    color: "#fff",
  },
  dayRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 6,
    marginTop: 4,
  },
  hint: {
    color: "#666",
    fontSize: 13,
    marginTop: 4,
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
  invalidHint: {
    color: "#c00",
    fontSize: 12,
    marginLeft: 8,
  },
  label: {
    color: "#333",
    fontSize: 14,
    fontWeight: "600",
    marginTop: 16,
  },
  removeButton: {
    marginLeft: 8,
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  removeButtonText: {
    color: "#c00",
    fontSize: 14,
  },
  timeInput: {
    borderColor: "#ccc",
    borderRadius: 6,
    borderWidth: 1,
    fontSize: 16,
    minWidth: 90,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  timeRow: {
    alignItems: "center",
    flexDirection: "row",
    marginTop: 8,
  },
  typeButton: {
    borderColor: "#ccc",
    borderRadius: 6,
    borderWidth: 1,
    marginBottom: 8,
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
