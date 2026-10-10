package com.taskrx.mobile.notifications

import com.taskrx.core.notifications.ArmedNotification
import com.taskrx.core.notifications.NotificationSpec

/**
 * One operating-system subsystem, as the scheduler sees it. The two Tiers have one each
 * and share nothing: a dose occupies an exact-alarm slot, a task a work request, so no
 * quantity of the second can exhaust the first (ADR 0007).
 */
interface TierGateway {
    /** Arms [spec], replacing any armed notification with the same id. */
    suspend fun arm(spec: NotificationSpec)

    suspend fun cancel(id: String)

    /** What is armed here now, as best the platform lets us know. */
    suspend fun armed(): List<ArmedNotification>
}
