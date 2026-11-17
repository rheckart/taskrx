package com.taskrx.mobile.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.taskrx.mobile.TaskRxApplication
import java.util.concurrent.TimeUnit

/**
 * The periodic background pull (ADR 0016). The phone pulls live only while its process runs,
 * so without this a dose answered on the desktop still alarms here, and a medication added or
 * paused there is not armed or disarmed until the app is next opened. Every fifteen minutes
 * (Android's minimum), with a network, this pushes what is waiting, pulls once, and the pull
 * re-arms the Horizon.
 *
 * It is not what makes a dose ring: alarms are armed from the local store and fire offline.
 * A pull that cannot run is tried again with WorkManager's backoff and changes nothing armed.
 */
class BackgroundPullWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as TaskRxApplication).container.sync
        val outcome = sync?.pullOnce()
        Log.i(NAME, "background pull: ${outcome ?: "local-only build"}")
        return resultOf(outcome)
    }

    companion object {
        const val NAME = "background-pull"

        /** Done and signed out are finished; anything that could not run is tried again. Null is a local-only build. */
        fun resultOf(outcome: BackgroundPull?): Result =
            when (outcome) {
                null, BackgroundPull.Done, BackgroundPull.SignedOut -> Result.success()
                BackgroundPull.NotReady, BackgroundPull.Failed -> Result.retry()
            }

        /** One periodic pull, kept across launches (KEEP, so a launch never resets its clock). */
        fun schedule(workManager: WorkManager) {
            val request =
                PeriodicWorkRequestBuilder<BackgroundPullWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
