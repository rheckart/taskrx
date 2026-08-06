import type {
  DailyTimes,
  IntervalTimes,
  NoneTimes,
  OnceTimes,
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
 * Four of the five variants are editable here:
 *   - `daily`:    a list of HH:MM time slots
 *   - `interval`: an hours field and a start instant
 *   - `once`:     a single instant (ADR 0006 — an instant, never a bare date)
 *   - `none`:     nothing to edit; the reminder goes to the Backlog
 *
 * `weekly` is deliberately outside `EditableSchedule`. It has no UI yet, and
 * narrowing the prop says so in the type rather than leaving a branch that
 * renders an apology.
 */

/** The schedule variants this component can edit. `weekly` has no UI yet. */
export type EditableSchedule =
  | DailyTimes
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

export function SchedulePicker({
  value,
  onChange,
}: SchedulePickerProps): ReactElement {
  switch (value.type) {
    case "daily":
      return <DailyPicker value={value} onChange={onChange} />;
    case "interval":
      return <IntervalPicker value={value} onChange={onChange} />;
    case "once":
      return <OncePicker value={value} onChange={onChange} />;
    case "none":
      return <NoneNotice />;
  }
}

function DailyPicker({
  value,
  onChange,
}: {
  readonly value: DailyTimes;
  readonly onChange: (next: EditableSchedule) => void;
}): ReactElement {
  const times = value.times;

  const updateTime = useCallback(
    (index: number, next: string): void => {
      onChange({
        type: "daily",
        times: times.map((t, i) => (i === index ? next : t)),
      });
    },
    [times, onChange]
  );

  const removeTime = useCallback(
    (index: number): void => {
      onChange({ type: "daily", times: times.filter((_, i) => i !== index) });
    },
    [times, onChange]
  );

  const addTime = useCallback((): void => {
    onChange({ type: "daily", times: [...times, "09:00"] });
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
});
