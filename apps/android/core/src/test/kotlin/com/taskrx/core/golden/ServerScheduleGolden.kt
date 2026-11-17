package com.taskrx.core.golden

import com.taskrx.core.sync.scheduleFromServer
import com.taskrx.core.sync.scheduleToServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * `scheduleFromServer` and `scheduleToServer`: how a Convex `schedule` field is read, and the
 * canonical form a create or edit writes back. Convex numbers
 * are float64, so a whole number may arrive with a fractional spelling; anything malformed
 * reads as null, which the pull turns into the Backlog.
 */
object ServerScheduleGolden : GoldenSet {
    override val name = "server-schedule"
    override val about =
        "scheduleFromServer(schedule) then scheduleToServer(result): the canonical Convex shape a write sends; null when unreadable"

    private val raw =
        listOf(
            "daily" to """{"type":"daily","times":["09:00","21:30"]}""",
            "daily: a single-digit hour is padded" to """{"type":"daily","times":["7:05"]}""",
            "daily: a one-digit minute is unreadable" to """{"type":"daily","times":["07:5"]}""",
            "daily: 24:00 is unreadable" to """{"type":"daily","times":["24:00"]}""",
            "daily: 23:60 is unreadable" to """{"type":"daily","times":["23:60"]}""",
            "daily: a number for a time is unreadable" to """{"type":"daily","times":[900]}""",
            "daily: no times is a readable empty schedule" to """{"type":"daily","times":[]}""",
            "daily: times missing" to """{"type":"daily"}""",
            "daily: times not an array" to """{"type":"daily","times":"09:00"}""",
            "interval" to """{"type":"interval","hours":8,"startAt":1783072800000}""",
            "interval: float64 spellings of whole numbers" to """{"type":"interval","hours":8.0,"startAt":1.7830728E12}""",
            "interval: a fractional hour is unreadable" to """{"type":"interval","hours":2.5,"startAt":1783072800000}""",
            "interval: zero hours is unreadable" to """{"type":"interval","hours":0,"startAt":1783072800000}""",
            "interval: negative hours is unreadable" to """{"type":"interval","hours":-8,"startAt":1783072800000}""",
            "interval: hours beyond Int is unreadable" to """{"type":"interval","hours":3000000000,"startAt":1783072800000}""",
            "interval: a string number is unreadable" to """{"type":"interval","hours":"8","startAt":1783072800000}""",
            "interval: startAt missing" to """{"type":"interval","hours":8}""",
            "weekly" to """{"type":"weekly","daysOfWeek":[1,3,5],"times":["07:00"]}""",
            "weekly: Sunday is 0 and Saturday 6" to """{"type":"weekly","daysOfWeek":[0,6],"times":["07:00"]}""",
            "weekly: 7 is unreadable" to """{"type":"weekly","daysOfWeek":[7],"times":["07:00"]}""",
            "weekly: -1 is unreadable" to """{"type":"weekly","daysOfWeek":[-1],"times":["07:00"]}""",
            "weekly: a fractional day is unreadable" to """{"type":"weekly","daysOfWeek":[1.5],"times":["07:00"]}""",
            "weekly: days missing" to """{"type":"weekly","times":["07:00"]}""",
            "weekly: times missing" to """{"type":"weekly","daysOfWeek":[1]}""",
            "monthly" to """{"type":"monthly","daysOfMonth":[1,15,31],"times":["09:00"]}""",
            "monthly: 0 is unreadable" to """{"type":"monthly","daysOfMonth":[0],"times":["09:00"]}""",
            "monthly: 32 is unreadable" to """{"type":"monthly","daysOfMonth":[32],"times":["09:00"]}""",
            "monthly: days not an array" to """{"type":"monthly","daysOfMonth":1,"times":["09:00"]}""",
            "monthly: times missing" to """{"type":"monthly","daysOfMonth":[1]}""",
            "once" to """{"type":"once","at":1783699200000}""",
            "once: float64 spelling" to """{"type":"once","at":1.7836992E12}""",
            "once: a fractional moment is unreadable" to """{"type":"once","at":1783699200000.5}""",
            "once: at missing" to """{"type":"once"}""",
            "none" to """{"type":"none"}""",
            "asNeeded with a daily limit" to """{"type":"asNeeded","minGapMinutes":240,"maxPer24h":4}""",
            "asNeeded with no limit" to """{"type":"asNeeded","minGapMinutes":240}""",
            "asNeeded: a zero gap is unreadable" to """{"type":"asNeeded","minGapMinutes":0}""",
            "asNeeded: a zero limit is unreadable" to """{"type":"asNeeded","minGapMinutes":240,"maxPer24h":0}""",
            "asNeeded: a null limit is unreadable" to """{"type":"asNeeded","minGapMinutes":240,"maxPer24h":null}""",
            "asNeeded: gap missing" to """{"type":"asNeeded"}""",
            "an unknown type" to """{"type":"hourly","times":["09:00"]}""",
            "no type" to """{"times":["09:00"]}""",
            "a type that is not a string" to """{"type":1}""",
            "not an object" to """["daily"]""",
            "null" to """null""",
        )

    override fun cases(): List<GoldenCase> =
        raw.map { (name, text) ->
            val schedule: JsonElement = Json.parseToJsonElement(text)
            GoldenCase(
                name,
                buildJsonObject { put("schedule", schedule) },
                scheduleFromServer(schedule)?.let(::scheduleToServer) ?: JsonNull,
            )
        }
}
