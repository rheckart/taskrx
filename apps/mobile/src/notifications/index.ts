export {
  MEDICATION_REMINDER_CHANNEL_ID,
  MEDICATION_REMINDER_CHANNEL_NAME,
  createNotificationChannel,
  scheduleForMedication,
  cancelForMedication,
  cancelAll,
  rescheduleAll,
  getPendingCount,
} from "./scheduler";

export {
  requestPermissions,
  checkNotificationPermission,
  checkExactAlarmPermission,
  openExactAlarmSettings,
} from "./permissions";

export type { PermissionStatus, PermissionResult } from "./permissions";

export {
  registerNotificationResponseHandler,
  registerBackgroundNotificationHandler,
} from "./tap-handler";
