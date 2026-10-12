package com.taskrx.core.notifications

import java.net.URLEncoder

/*
 * Notification tap -> answer screen. Ported from apps/mobile/src/notifications/tap-handler.ts.
 *
 * The route is Kind-neutral: a task tap lands on the same answer screen a dose does.
 */

/** The answer screen's route, kept at the Expo app's path so the deep link stays the same. */
const val ANSWER_ROUTE: String = "dose/log"

data class TapTarget(val reminderId: String, val scheduledTime: Long)

/**
 * Every tap ends in exactly one of these and every one is logged: until the Expo
 * handler logged at every exit, a tap that went nowhere was silent, and "the medication
 * reminder does nothing" has several different causes with different fixes.
 */
sealed interface TapOutcome {
    val logLine: String

    /** The intent was not a notification press at all (a launcher start, say). */
    data class Ignored(val action: String?) : TapOutcome {
        override val logLine: String get() = "[tap] ignored: action=$action"
    }

    /** A press whose payload names no Reminder or no usable moment. */
    data object NoUsableData : TapOutcome {
        override val logLine: String get() = "[tap] press with no usable dose data"
    }

    data class Navigate(val target: TapTarget, val route: String) : TapOutcome {
        override val logLine: String get() = "[tap] navigating to $route"
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** `dose/log?reminderId=..&scheduledTime=..` */
fun answerRoute(target: TapTarget): String =
    "$ANSWER_ROUTE?reminderId=${encode(target.reminderId)}&scheduledTime=${target.scheduledTime}"

/**
 * Resolves a tap from the strings its notification carried. `reminderId` is preferred
 * and `medicationId` accepted, as in the Expo handler: an alarm armed by the previous
 * build is still handled instead of silently dropped.
 */
fun resolveTap(isPress: Boolean, action: String?, reminderId: String?, medicationId: String?, scheduledTime: String?): TapOutcome {
    if (!isPress) return TapOutcome.Ignored(action)
    val id = reminderId ?: medicationId
    val time = scheduledTime?.toLongOrNull()
    if (id == null || time == null) return TapOutcome.NoUsableData
    val target = TapTarget(id, time)
    return TapOutcome.Navigate(target, answerRoute(target))
}
