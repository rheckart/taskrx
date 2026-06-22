import type {
  DailySchedule,
  IntervalSchedule,
  ScheduleType,
} from "@taskrx/shared";
import { useCallback, useMemo } from "react";
import type { ReactElement } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";

/**
 * Schedule picker for the medication form.
 *
 * Renders different inputs depending on `scheduleType`:
 *   - `daily`:    a list of HH:MM time slots the user can add/remove
 *   - `interval`: an hours field + a start datetime field
 *
 * The component is fully controlled: the parent owns the value and
 * receives change callbacks. The serialized shape matches the SQLite
 * `schedule_times` JSON column (see plan §6.1):
 *   - daily:    `{ times: ["09:00", "21:00"] }`
 *   - interval: `{ hours: 8, startAt: <unix ms> }`
 *
 * The `value` shape is discriminated by the `scheduleType` prop, not by
 * an internal `type` field — this matches `@taskrx/shared`'s
 * `DailySchedule` / `IntervalSchedule` types which have no discriminator.
 */

export type ScheduleValue = DailySchedule | IntervalSchedule;

export interface SchedulePickerProps {
  readonly scheduleType: ScheduleType;
  readonly value: ScheduleValue;
  readonly onChange: (next: ScheduleValue) => void;
}

const TIME_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/;

function isValidTime(value: string): boolean {
  return TIME_PATTERN.test(value);
}

function pad2(n: number): string {
  return n.toString().padStart(2, "0");
}

function nowLocalDateTimeInputValue(): string {
  const d = new Date();
  return `${d.getFullYear().toString().padStart(4, "0")}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

function localDateTimeInputToMs(value: string): number | null {
  const parsed = new Date(value).getTime();
  return Number.isFinite(parsed) ? parsed : null;
}

function msToLocalDateTimeInputValue(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear().toString().padStart(4, "0")}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

export function SchedulePicker({
  scheduleType,
  value,
  onChange,
}: SchedulePickerProps): ReactElement {
  if (scheduleType === "daily") {
    return <DailyPicker value={value} onChange={onChange} />;
  }
  return <IntervalPicker value={value} onChange={onChange} />;
}

function DailyPicker({
  value,
  onChange,
}: {
  readonly value: ScheduleValue;
  readonly onChange: (next: ScheduleValue) => void;
}): ReactElement {
  const times: readonly string[] = useMemo<readonly string[]>(() => {
    if ("times" in value && Array.isArray(value.times)) {
      return value.times.filter((t): t is string => typeof t === "string");
    }
    return [];
  }, [value]);

  const updateTime = useCallback(
    (index: number, next: string): void => {
      const updated = times.map((t, i) => (i === index ? next : t));
      const daily: DailySchedule = { times: updated };
      onChange(daily);
    },
    [times, onChange]
  );

  const removeTime = useCallback(
    (index: number): void => {
      const filtered = times.filter((_, i) => i !== index);
      const daily: DailySchedule = { times: filtered };
      onChange(daily);
    },
    [times, onChange]
  );

  const addTime = useCallback((): void => {
    const next = [...times, "09:00"];
    const daily: DailySchedule = { times: next };
    onChange(daily);
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

function IntervalPicker({
  value,
  onChange,
}: {
  readonly value: ScheduleValue;
  readonly onChange: (next: ScheduleValue) => void;
}): ReactElement {
  const hours =
    "hours" in value && typeof value.hours === "number" ? value.hours : 8;
  const startAt =
    "startAt" in value && typeof value.startAt === "number"
      ? value.startAt
      : Date.now();

  const onHoursChange = useCallback(
    (next: string): void => {
      const parsed = Number.parseInt(next, 10);
      const safe = Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
      const interval: IntervalSchedule = { hours: safe, startAt };
      onChange(interval);
    },
    [startAt, onChange]
  );

  const onStartChange = useCallback(
    (next: string): void => {
      const ms = localDateTimeInputToMs(next);
      if (ms === null) {
        return;
      }
      const interval: IntervalSchedule = { hours, startAt: ms };
      onChange(interval);
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

      <Text style={styles.label}>First dose at</Text>
      <TextInput
        style={styles.input}
        value={msToLocalDateTimeInputValue(startAt)}
        onChangeText={onStartChange}
        placeholder={nowLocalDateTimeInputValue()}
        placeholderTextColor="#999"
        autoCorrect={false}
        autoCapitalize="none"
      />
      <Text style={styles.hint}>
        Format: YYYY-MM-DDTHH:MM (local time). Reminders fire every {hours} hour
        {hours === 1 ? "" : "s"} from this start.
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
