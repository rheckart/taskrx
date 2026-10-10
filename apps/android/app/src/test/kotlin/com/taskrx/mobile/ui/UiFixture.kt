package com.taskrx.mobile.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.robolectric.shadows.ShadowAlarmManager
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * A [Fixture] whose session is the test user, with `Dispatchers.Main` running eagerly so a
 * ViewModel's `viewModelScope` work happens where the test can `join()` it. Storage is real
 * (in-memory Room), so assertions read what was stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun withUi(now: Long, block: suspend Fixture.() -> Unit) {
    Dispatchers.setMain(Dispatchers.Unconfined)
    try {
        withFixture(now) {
            // The real gateways run underneath, so a reschedule after a write is observable
            // in Robolectric's AlarmManager and in a test-initialised WorkManager.
            val context = ApplicationProvider.getApplicationContext<Application>()
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
            )
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            container.notifications.currentUser.set(USER)
            block()
        }
    } finally {
        Dispatchers.resetMain()
    }
}

/** Every toast a ViewModel raised, in order. */
class ToastLog {
    val shown = mutableListOf<com.taskrx.mobile.ui.components.ToastRequest>()

    fun add(request: com.taskrx.mobile.ui.components.ToastRequest) {
        shown += request
    }
}
