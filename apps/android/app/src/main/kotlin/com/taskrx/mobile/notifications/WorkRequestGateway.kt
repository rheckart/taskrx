package com.taskrx.mobile.notifications

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.taskrx.core.notifications.ArmedNotification
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import com.taskrx.mobile.data.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * The task Tier: a WorkManager `OneTimeWorkRequest` with an initial delay (ADR 0007).
 * Never AlarmManager - not an inexact alarm either - so a task cannot touch the
 * exact-alarm budget, needs no SCHEDULE_EXACT_ALARM, and never shows the alarm glyph.
 *
 * The cost is that a task is approximate: WorkManager defers in Doze until a maintenance
 * window. That is the right trade for a task and the wrong one for a dose.
 *
 * The unique work name is the notification id (`task-${reminderId}-${scheduledTime}`)
 * with REPLACE, so re-arming is idempotent and one task's occurrences do not replace each
 * other. Unlike AlarmManager, WorkManager can be asked what it holds, so [armed] is the
 * truth and needs no ledger.
 */
class WorkRequestGateway(
    private val workManager: WorkManager,
    private val clock: Clock,
) : TierGateway {
    override suspend fun arm(spec: NotificationSpec) {
        val request =
            OneTimeWorkRequest.Builder(TaskNotificationWorker::class.java)
                .setInitialDelay(maxOf(0L, spec.fireAt - clock()), TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putString(TaskNotificationWorker.KEY_SPEC, SpecCodec.encode(spec)).build())
                .addTag(TAG)
                .addTag(ID_TAG_PREFIX + spec.id)
                .build()
        workManager.enqueueUniqueWork(spec.id, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun cancel(id: String) {
        workManager.cancelUniqueWork(id)
    }

    override suspend fun armed(): List<ArmedNotification> =
        workManager.getWorkInfosByTagFlow(TAG).first()
            .filter { !it.state.isFinished }
            .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(ID_TAG_PREFIX) }?.removePrefix(ID_TAG_PREFIX) }
            .distinct()
            .map { ArmedNotification(it, Tier.Task) }

    companion object {
        const val TAG = "task-reminder"
        const val ID_TAG_PREFIX = "nid:"
    }
}

class TaskNotificationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val spec = SpecCodec.decode(inputData.getString(KEY_SPEC))
        if (spec == null) {
            Log.w("task-work", "ran with no readable payload; nothing to post")
            return Result.failure()
        }
        NotificationPoster.post(applicationContext, spec)
        return Result.success()
    }

    companion object {
        const val KEY_SPEC = "spec"
    }
}
