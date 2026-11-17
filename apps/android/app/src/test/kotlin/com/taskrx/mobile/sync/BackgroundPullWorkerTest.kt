package com.taskrx.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundPullWorkerTest {
    @Test
    fun `a pull that ran, or had nothing to do signed out, is done, and one that could not run is tried again`() {
        assertEquals(ListenableWorker.Result.success(), BackgroundPullWorker.resultOf(BackgroundPull.Done))
        assertEquals(ListenableWorker.Result.success(), BackgroundPullWorker.resultOf(BackgroundPull.SignedOut))
        assertEquals(ListenableWorker.Result.retry(), BackgroundPullWorker.resultOf(BackgroundPull.NotReady))
        assertEquals(ListenableWorker.Result.retry(), BackgroundPullWorker.resultOf(BackgroundPull.Failed))
        assertEquals(ListenableWorker.Result.success(), BackgroundPullWorker.resultOf(null), "a local-only build has no sync to run")
    }

    @Test
    fun `scheduling keeps one periodic pull every fifteen minutes that needs a network`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val workManager = WorkManager.getInstance(context)

        BackgroundPullWorker.schedule(workManager)
        val first = workManager.getWorkInfosForUniqueWork(BackgroundPullWorker.NAME).get().single()
        BackgroundPullWorker.schedule(workManager)

        val infos = workManager.getWorkInfosForUniqueWork(BackgroundPullWorker.NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        // Each launch updates the job in place: that hands it to the system again if it was left
        // out, and it keeps the job's clock, so launching often never postpones a pull.
        assertEquals(first.id, info.id)
        assertEquals(first.generation + 1, info.generation, "re-registered, not kept untouched")
        assertEquals(first.nextScheduleTimeMillis, info.nextScheduleTimeMillis)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.MINUTES.toMillis(15), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }
}
