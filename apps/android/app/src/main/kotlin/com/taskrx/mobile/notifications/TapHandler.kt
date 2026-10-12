package com.taskrx.mobile.notifications

import android.content.Intent
import android.util.Log
import com.taskrx.core.notifications.TapOutcome
import com.taskrx.core.notifications.resolveTap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turns a notification tap into a route, and logs the outcome of EVERY tap. The route is
 * the answer screen with reminderId + scheduledTime, Kind-neutral; the screen itself
 * arrives with the UI step, which collects [route].
 */
class TapHandler(private val log: (String) -> Unit = { Log.i("tap", it) }) {
    private val pending = MutableStateFlow<String?>(null)

    /** The route a tap asked for and the UI has not yet consumed. */
    val route: StateFlow<String?> = pending

    fun handle(intent: Intent?): TapOutcome {
        val outcome =
            resolveTap(
                isPress = intent?.action == ACTION_TAP,
                action = intent?.action,
                reminderId = intent?.getStringExtra(EXTRA_REMINDER_ID),
                medicationId = intent?.getStringExtra(EXTRA_LEGACY_MEDICATION_ID),
                scheduledTime = intent?.getStringExtra(EXTRA_SCHEDULED_TIME),
            )
        log(outcome.logLine)
        if (outcome is TapOutcome.Navigate) pending.value = outcome.route
        return outcome
    }

    fun consume() {
        pending.value = null
    }

    companion object {
        const val ACTION_TAP = "com.taskrx.mobile.action.NOTIFICATION_TAP"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_LEGACY_MEDICATION_ID = "medicationId"
        const val EXTRA_SCHEDULED_TIME = "scheduledTime"
    }
}
