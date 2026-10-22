package com.taskrx.mobile.notifications

import android.content.Context
import android.content.SharedPreferences
import com.taskrx.core.notifications.ArmedNotification
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier

/**
 * The small facts the notification layer must remember across process death, in one
 * preferences file. None of it is a verdict about a permission: the platform is asked
 * every time. These are records of things the app did, which no API will give back.
 */
class NotificationPrefs(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("taskrx.notifications", Context.MODE_PRIVATE)
}

/**
 * Which alarms this app has armed with AlarmManager, which cannot be queried. An entry is
 * only a claim: [AlarmClockGateway] checks each against the system's own PendingIntent
 * before believing it.
 */
class ArmedLedger(private val store: NotificationPrefs) {
    private val key = "armed_alarms"

    @Synchronized
    fun all(): List<ArmedNotification> =
        store.prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { entry ->
            val mark = entry.substringBefore(':')
            val id = entry.substringAfter(':', "")
            if (id.isEmpty() || mark !in setOf("e", "d")) null else ArmedNotification(id, Tier.Medication, degraded = mark == "d")
        }

    @Synchronized
    fun put(id: String, degraded: Boolean) {
        val next = all().filter { it.id != id }.map { encode(it.id, it.degraded) } + encode(id, degraded)
        store.prefs.edit().putStringSet(key, next.toSet()).commit()
    }

    @Synchronized
    fun remove(id: String) {
        val next = all().filter { it.id != id }.map { encode(it.id, it.degraded) }
        store.prefs.edit().putStringSet(key, next.toSet()).commit()
    }

    private fun encode(id: String, degraded: Boolean) = "${if (degraded) "d" else "e"}:$id"
}

/**
 * The snoozes the person has asked for. A snooze is armed by the user, not derived from a
 * schedule, so the reschedule diff can neither recreate nor see it; this record is what
 * lets a reboot bring it back.
 */
class SnoozeStore(private val store: NotificationPrefs) {
    private val key = "snoozes"

    @Synchronized
    fun all(): List<NotificationSpec> =
        store.prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { SpecCodec.decode(it) }

    @Synchronized
    fun put(spec: NotificationSpec) = write(all().filter { it.id != spec.id } + spec)

    @Synchronized
    fun removeForReminder(reminderId: String) = write(all().filter { it.reminderId != reminderId })

    /** Forgets the snoozes of one Occurrence and returns them, so their alarms can be cancelled. */
    @Synchronized
    fun removeForOccurrence(reminderId: String, scheduledTime: Long): List<NotificationSpec> {
        val (gone, kept) = all().partition { it.reminderId == reminderId && it.scheduledTime == scheduledTime }
        if (gone.isNotEmpty()) write(kept)
        return gone
    }

    /** Drops the snoozes whose moment has passed and returns them. */
    @Synchronized
    fun pruneExpired(now: Long): List<NotificationSpec> {
        val (expired, live) = all().partition { it.fireAt <= now }
        if (expired.isNotEmpty()) write(live)
        return expired
    }

    private fun write(specs: List<NotificationSpec>) {
        store.prefs.edit().putStringSet(key, specs.map(SpecCodec::encode).toSet()).commit()
    }
}

/**
 * Whose Reminders the boot receiver should re-arm. Set on the signed-in path (and by the
 * harness); null means there is nothing to arm for.
 */
class CurrentUser(private val store: NotificationPrefs) {
    fun get(): String? = store.prefs.getString("user_id", null)

    fun set(userId: String?) {
        store.prefs.edit().apply { if (userId == null) remove("user_id") else putString("user_id", userId) }.commit()
    }
}

/** Whether the OS notification prompt has ever been raised: Android will not say. */
class PromptRecord(private val store: NotificationPrefs) {
    fun shown(): Boolean = store.prefs.getBoolean("prompt_shown", false)

    fun markShown() {
        store.prefs.edit().putBoolean("prompt_shown", true).commit()
    }
}
