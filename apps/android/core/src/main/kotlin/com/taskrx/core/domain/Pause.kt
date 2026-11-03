package com.taskrx.core.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Pausing a Reminder: a stretch of time in which it comes due at no moment at all, after
 * which it carries on as before. Not Stop. Stop is `active = false` and a server tombstone,
 * and no other device ever sees the Reminder again; a pause is a fact about time, so it
 * rides on the Reminder and syncs like any other field.
 *
 * A pause is a window rather than a flag because the Missed sweep looks back seven days
 * (ADR 0005). A flag cleared on resume would make every paused moment in that lookback an
 * unanswered one, and a person back from surgery would be told they missed a week of doses.
 * A resumed pause therefore stays, ended at the moment of resuming, until it is old enough
 * for nothing to look at it.
 */

/**
 * The moments in `[from, until)` do not exist for the Reminder that carries this. [until] is
 * null while the person has not said when they will resume.
 */
data class PauseWindow(val from: Long, val until: Long? = null) {
    init {
        require(until == null || until > from) { "A pause must end after it starts." }
    }
}

/** Whether [instant] falls inside any of [windows]: the start counts, the end does not. */
fun isPausedAt(windows: List<PauseWindow>, instant: Long): Boolean =
    windows.any { instant >= it.from && (it.until == null || instant < it.until) }

/**
 * The window that has not finished as of [now]: one running, or one that starts later. At
 * most one exists, because [addPause] refuses a second.
 */
fun livePause(windows: List<PauseWindow>, now: Long): PauseWindow? =
    windows.firstOrNull { it.until == null || it.until > now }

/**
 * [windows] with a new pause added.
 *
 * @throws IllegalArgumentException when one is already running or waiting to start: the person
 * resumes (or cancels) that one first, so what the list says is never a guess at which wins.
 */
fun addPause(windows: List<PauseWindow>, from: Long, until: Long?, now: Long): List<PauseWindow> {
    require(livePause(windows, now) == null) { "It is already paused." }
    return windows + PauseWindow(from, until)
}

/**
 * [windows] after the person resumes at [now]. A running pause ends there, and keeps its moments
 * suppressed up to it; one that has not started is removed, since nothing was ever paused.
 * Nothing live means nothing changes.
 */
fun resumePause(windows: List<PauseWindow>, now: Long): List<PauseWindow> {
    val live = livePause(windows, now) ?: return windows
    return if (live.from >= now) windows - live else windows.map { if (it == live) it.copy(until = now) else it }
}

/*
 * The wire shape, which is also the stored one: `[{"from": 1.7e12, "until": 1.7e12}]`, `until`
 * left out while open. The same JSON is the Room column, the sync payload and the Convex field
 * (`pauseWindowValidator`), so there is one codec and nothing to keep in step.
 */

fun pausesToJson(windows: List<PauseWindow>): JsonArray =
    buildJsonArray {
        for (window in windows) {
            add(
                buildJsonObject {
                    put("from", window.from)
                    window.until?.let { put("until", it) }
                },
            )
        }
    }

private fun JsonElement?.whole(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.content.toDoubleOrNull() ?: return null
    return if (number % 1.0 == 0.0) number.toLong() else null
}

/**
 * Reads [value] back, or returns an empty list for anything that is not a well-formed array of
 * windows. Numbers arrive from Convex as float64, so `1700000000000.0` is as good as the integer.
 * An empty list is the safe misreading: the Reminder is simply not paused.
 */
fun pausesFromJson(value: JsonElement?): List<PauseWindow> {
    if (value !is JsonArray) return emptyList()
    return value.map { element ->
        val window = element as? JsonObject ?: return emptyList()
        val from = window["from"].whole() ?: return emptyList()
        val untilElement = window["until"]
        val until = if (untilElement == null) null else untilElement.whole() ?: return emptyList()
        if (until != null && until <= from) return emptyList()
        PauseWindow(from, until)
    }
}
