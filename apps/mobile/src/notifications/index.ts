export {
  MEDICATION_REMINDER_CHANNEL_ID,
  MEDICATION_REMINDER_CHANNEL_NAME,
  createNotificationChannel,
  scheduleForMedication,
  cancelForMedication,
  cancelAll,
  rescheduleAll,
  getPendingCount,
} from "./scheduler.js";

export {
  requestPermissions,
  checkNotificationPermission,
  checkExactAlarmPermission,
  openExactAlarmSettings,
} from "./permissions.js";

export type { PermissionStatus, PermissionResult } from "./permissions.js";

export {
  registerNotificationResponseHandler,
  registerBackgroundNotificationHandler,
} from "./tap-handler.js";
