package com.taskrx.mobile.ui

import android.net.Uri
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
    const val ADD_DOSE = "dose/add"
    const val BACKLOG = "backlog"
    const val HISTORY = "dose/history"
    const val REMINDERS = "meds"
    const val ADHERENCE = "adherence"
    const val SUPPLIES = "refill"
    const val DRUG_SEARCH = "meds/search"
    const val SYNC_PROBLEMS = "sync/problems"
    const val SETTINGS = "settings"

    const val ARG_NAME = "name"
    const val ARG_RXCUI = "rxcui"
    const val ARG_EDIT_ID = "id"

    /** The medication form, optionally started from a drug-search pick. */
    const val NEW_MEDICATION_PATTERN = "$NEW_MEDICATION?$ARG_NAME={$ARG_NAME}&$ARG_RXCUI={$ARG_RXCUI}"

    const val EDIT_PATTERN = "reminders/{$ARG_EDIT_ID}/edit"

    fun edit(reminderId: String): String = "reminders/${Uri.encode(reminderId)}/edit"

    /** The medication form with a picked drug filled in. */
    fun newMedication(name: String, rxcui: String): String =
        "$NEW_MEDICATION?$ARG_NAME=${Uri.encode(name)}&$ARG_RXCUI=${Uri.encode(rxcui)}"

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
            NEW_MEDICATION, NEW_MEDICATION_PATTERN -> "New Medication"
            ADD_DOSE -> "Add a dose"
            BACKLOG -> "Backlog"
            HISTORY -> "History"
            REMINDERS -> "My Reminders"
            ADHERENCE -> "How it's going"
            SUPPLIES -> "Supplies"
            DRUG_SEARCH -> "Search RxNorm"
            SYNC_PROBLEMS -> "Sync problems"
            SETTINGS -> "Settings"
            EDIT_PATTERN -> "Edit reminder"
            ANSWER_PATTERN -> "Answer a reminder"
            else -> "TaskRx"
        }
}
