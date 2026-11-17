package com.taskrx.mobile

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import com.taskrx.core.notifications.MAX_TASK_TRIGGERS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WorkManager hands the system only so many jobs at once (20 by default) and keeps the rest in
 * its own database, picked by when they were enqueued, not when they are due. With the app
 * closed, a job left out waits for one handed over to run. Every task the Horizon arms and the
 * background pull must fit, or the pull, and a task due soon, wait behind tasks due days later.
 */
@RunWith(RobolectricTestRunner::class)
class WorkSchedulerLimitTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `every armed task and the background pull fit in what the system is handed`() {
        val limit = (app as Configuration.Provider).workManagerConfiguration.maxSchedulerLimit
        assertEquals(TaskRxApplication.WORK_SCHEDULER_LIMIT, limit)
        // The tasks, the pull, and room for task snoozes and requests that are due but not yet run.
        assertTrue(MAX_TASK_TRIGGERS + 1 + 10 <= limit, "limit $limit")
    }

    @Test
    fun `the default initializer is removed, or the configuration is never read`() {
        val provider =
            app.packageManager.getProviderInfo(
                ComponentName(app, "androidx.startup.InitializationProvider"),
                PackageManager.GET_META_DATA,
            )
        assertFalse(provider.metaData?.containsKey("androidx.work.WorkManagerInitializer") ?: false, "${provider.metaData?.keySet()}")
    }
}
