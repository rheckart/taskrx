package com.taskrx.mobile.notifications

import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class WireSpec(
    val id: String,
    val tier: String,
    val fireAt: Long,
    val title: String,
    val body: String,
    val reminderId: String,
    val scheduledTime: Long,
    val snoozed: Boolean = false,
)

/**
 * The wire form of a [NotificationSpec]: what rides in the PendingIntent to the alarm
 * receiver, in the WorkManager input to the worker, and in the record kept of a snooze.
 *
 * The Tier travels as its frozen wire word, not an enum name. Decoding is total: a
 * payload nobody can read is `null`, never an exception thrown into a broadcast.
 */
object SpecCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(spec: NotificationSpec): String =
        json.encodeToString(
            WireSpec(spec.id, spec.tier.wire, spec.fireAt, spec.title, spec.body, spec.reminderId, spec.scheduledTime, spec.snoozed),
        )

    fun decode(text: String?): NotificationSpec? {
        if (text == null) return null
        val wire = runCatching { json.decodeFromString<WireSpec>(text) }.getOrNull() ?: return null
        val tier = Tier.entries.firstOrNull { it.wire == wire.tier } ?: return null
        return NotificationSpec(wire.id, tier, wire.fireAt, wire.title, wire.body, wire.reminderId, wire.scheduledTime, wire.snoozed)
    }
}
