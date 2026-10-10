package com.taskrx.mobile.notifications

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.TaskRxApplication
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An application whose graph cannot be built, as when the database or sign-in fails to initialise. */
class BrokenGraphApplication : TaskRxApplication() {
    override fun createContainer(): AppContainer = error("graph unavailable")
}

@RunWith(RobolectricTestRunner::class)
@Config(application = BrokenGraphApplication::class)
class ColdStartTest {
    @Test
    fun `a process woken by an alarm starts and has its channels without building the graph`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel("medication_reminders"))
    }
}
