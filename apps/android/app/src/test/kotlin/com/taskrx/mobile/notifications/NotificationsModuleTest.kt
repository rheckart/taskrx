package com.taskrx.mobile.notifications

import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.notifications.RearmCause
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationsModuleTest {
    @Test
    fun `a pass that throws is logged and dropped, not thrown into the process`() =
        withFixture(1_000_000) {
            val escaped = mutableListOf<Throwable>()
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e })
            val module =
                NotificationsModule(
                    ApplicationProvider.getApplicationContext(),
                    container.reminders,
                    { error("clock broke") },
                    scope = scope,
                )
            module.currentUser.set(USER)

            module.onForeground()?.join()
            module.rearm(RearmCause.Boot)?.join()

            assertEquals(emptyList(), escaped)
        }
}
