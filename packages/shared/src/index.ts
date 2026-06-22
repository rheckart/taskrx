export type ScheduleType = "daily" | "interval";

export interface DailySchedule {
  readonly times: readonly string[];
}

export interface IntervalSchedule {
  readonly hours: number;
  readonly startAt: number;
}

export interface Medication {
  readonly id: string;
  readonly userId: string;
  readonly name: string;
  readonly rxcui: string | undefined;
  readonly dosage: string | undefined;
  readonly scheduleType: ScheduleType;
  readonly scheduleTimes: DailySchedule | IntervalSchedule;
  readonly active: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export type DoseStatus = "pending" | "taken" | "skipped" | "missed";

export interface DoseLog {
  readonly id: string;
  readonly userId: string;
  readonly medicationId: string;
  readonly scheduledTime: number;
  readonly takenAt: number | undefined;
  readonly status: DoseStatus;
  readonly notes: string | undefined;
  readonly createdAt: number;
  readonly updatedAt: number;
}
