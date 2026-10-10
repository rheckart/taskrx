package com.taskrx.mobile.ui

import com.taskrx.core.notifications.ANSWER_ROUTE
import com.taskrx.core.notifications.TapTarget
import com.taskrx.core.notifications.answerRoute

/**
 * Every destination in the app. Route NAMES follow the Expo app's paths, because the device
 * harness and a notification tap name them: the answer route's path and its parameters are
 * :core's (`answerRoute`), the same string a notification tap resolves to.
 */
object Routes {
    const val TODAY = "today"
    const val ADD = "add"
    const val NEW_TASK = "tasks/new"
    const val NEW_MEDICATION = "meds/new"

    const val ARG_REMINDER_ID = "reminderId"
    const val ARG_SCHEDULED_TIME = "scheduledTime"

    /**
     * The pattern the answer destination registers. Both arguments are optional strings
     * parsed by the screen, so a link missing either lands on "Invalid reminder link"
     * rather than failing to resolve.
     */
    const val ANSWER_PATTERN = "$ANSWER_ROUTE?$ARG_REMINDER_ID={$ARG_REMINDER_ID}&$ARG_SCHEDULED_TIME={$ARG_SCHEDULED_TIME}"

    /** The route that opens the answer screen for one Occurrence: what a timeline tap and a notification tap both use. */
    fun answer(reminderId: String, scheduledTime: Long): String = answerRoute(TapTarget(reminderId, scheduledTime))

    /** The title the top bar shows for the destination whose route pattern is [route]. */
    fun titleOf(route: String?): String =
        when (route) {
            ADD -> "Add a reminder"
            NEW_TASK -> "New Task"
            NEW_MEDICATION -> "New Medication"
            ANSWER_PATTERN -> "Answer a reminder"
            else -> "TaskRx"
        }
}
