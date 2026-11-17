package com.taskrx.core.golden

import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.noticeRulesToJson
import com.taskrx.core.notices.ProposalRefusal
import com.taskrx.core.notices.ReadDate
import com.taskrx.core.notices.UnreadReason
import com.taskrx.core.notices.keptNoticeSentence
import com.taskrx.core.notices.matches
import com.taskrx.core.notices.noticeSentences
import com.taskrx.core.notices.noticeText
import com.taskrx.core.notices.occurrenceOptionsAround
import com.taskrx.core.notices.proposalsFor
import com.taskrx.core.notices.readNoticeDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val UTC: ZoneId = ZoneOffset.UTC

private fun nowAt(year: Int, month: Int, day: Int, hour: Int = 12): Long =
    ZonedDateTime.of(year, month, day, hour, 0, 0, 0, UTC).toInstant().toEpochMilli()

/** The moment the notice tests read at: Saturday 14 November 2026, noon UTC. */
private val NOW = nowAt(2026, 11, 14)

private fun readJson(read: ReadDate): JsonElement =
    when (read) {
        is ReadDate.Found ->
            buildJsonObject {
                put("date", read.date.toString())
                put("time", read.time?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("matchedText", read.matchedText)
            }
        is ReadDate.Unread ->
            buildJsonObject {
                put(
                    "unread",
                    when (read.reason) {
                        UnreadReason.NoDate -> "noDate"
                        UnreadReason.Ambiguous -> "ambiguous"
                        UnreadReason.Competing -> "competing"
                        UnreadReason.Impossible -> "impossible"
                    },
                )
            }
    }

/** A Convex `notices` document. */
private fun noticeDoc(
    title: String,
    sentence: String?,
    sourceKind: String = "email",
    sourceValue: String = "Council@Example.gov",
    receivedAt: Long = NOW - 3_600_000,
    localId: String = "notice-1",
): JsonObject =
    buildJsonObject {
        put("_id", "j77$localId")
        put("localId", localId)
        put("userId", "user_golden")
        put("sourceKind", sourceKind)
        put("sourceValue", sourceValue)
        put("title", title)
        sentence?.let { put("sentence", it) }
        put("receivedAt", receivedAt)
        put("resolution", "none")
        put("createdAt", receivedAt)
        put("updatedAt", receivedAt)
    }

private fun noticeFromDoc(doc: JsonObject): Notice {
    fun text(k: String) = (doc[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun long(k: String) = (doc[k] as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull }?.content?.toDouble()?.toLong()
    return Notice(
        id = text("localId")!!,
        userId = text("userId")!!,
        sourceKind = SourceKind.fromWire(text("sourceKind")!!)!!,
        sourceValue = text("sourceValue")!!,
        title = text("title")!!,
        sentence = text("sentence"),
        receivedAt = long("receivedAt")!!,
        externalId = text("externalId"),
        resolution = NoticeResolution.fromWire(text("resolution")!!)!!,
        resolvedAt = long("resolvedAt"),
        createdAt = long("createdAt")!!,
        updatedAt = long("updatedAt")!!,
    )
}

private fun refusalJson(refusal: ProposalRefusal?): JsonElement =
    when (refusal) {
        null -> JsonNull
        ProposalRefusal.NoOccurrence -> JsonPrimitive("noOccurrence")
        is ProposalRefusal.Move ->
            JsonPrimitive(
                when (refusal.reason) {
                    MoveRefusal.NotATask -> "notATask"
                    MoveRefusal.NotAfter -> "notAfter"
                    MoveRefusal.InThePast -> "inThePast"
                    MoveRefusal.TooLate -> "tooLate"
                    MoveRefusal.AlreadyAnswered -> "alreadyAnswered"
                    MoveRefusal.InProgress -> "inProgress"
                    MoveRefusal.InAPause -> "inAPause"
                },
            )
    }

/**
 * Proposals (ADR 0014): reading a date out of a notice, choosing the sentence kept from a
 * message, which rules match, and the move each matching task is offered. The date reader is a
 * second implementation on the desktop, held to the first only by these cases.
 */
object NoticesGolden : GoldenSet {
    override val name = "notices"
    override val about =
        "readNoticeDate(text, now, zone), noticeSentences(body), keptNoticeSentence(body, title, now, zone), " +
            "NoticeRule.matches(notice), noticeText(notice), proposalsFor(notice, reminders, instances, now, zone), " +
            "occurrenceOptionsAround(reminder, around, zone)"

    private val dates =
        listOf(
            "Sept 3" to nowAt(2026, 1, 1),
            "November 18" to NOW,
            "November 18" to nowAt(2026, 11, 20),
            "November 18" to nowAt(2026, 11, 18),
            "November 18, 2024" to NOW,
            "18 November 2024" to NOW,
            "Feb 29" to NOW,
            "Feb 28" to nowAt(2026, 1, 1),
            "on 2026-11-18 at noon" to NOW,
            "2026-01-02" to NOW,
            "18/11" to NOW,
            "11/18" to NOW,
            "18.11" to NOW,
            "05/06" to NOW,
            "11/12" to NOW,
            "05-06" to NOW,
            "18/11/2026" to NOW,
            "11/18/26" to NOW,
            "Feb 30" to NOW,
            "31/02" to NOW,
            "2026-13-01" to NOW,
            "32/11" to NOW,
            "18/00" to NOW,
            "18/19" to NOW,
            "Nov 18 or Nov 20" to NOW,
            "2026-11-18 and December 1" to NOW,
            "Nov 18 or 2026-11-18" to NOW,
            "The bins were collected today." to NOW,
            "" to NOW,
            "2026-11-18" to NOW,
            "Nov 18 at 5pm" to NOW,
            "Nov 18 at 5:30 pm" to NOW,
            "Nov 18 at 17:00" to NOW,
            "Nov 18 at noon" to NOW,
            "Nov 18 at midnight" to NOW,
            "Nov 18 at 12am" to NOW,
            "Nov 18 at 12pm" to NOW,
            "Nov 18 at 5 pm" to NOW,
            "Nov 18" to NOW,
            "Nov 18 at noon or 5pm" to NOW,
            "Nov 18 at 13pm" to NOW,
            "Nov 18 at 5:60 pm" to NOW,
            "Nov 18 at 25:00" to NOW,
            "Nov 18 at 10:60" to NOW,
        )

    private val sentences =
        listOf(
            "The bins move. Please put them out. Thanks.",
            "Is it Tuesday? Yes!",
            "Wow...",
            "Bin day changed\n\nCollection moves to Wednesday.",
            "one long line with no punctuation at all",
            "",
            "   \n\t\n  ",
            "Hi.",
        )

    private val bodies =
        listOf(
            "There is a change to your bin day. Collection moves to Wednesday 18 November. Thank you.",
            "There is a change to your bin day. collection moves to Wednesday 18 November. Thank you.",
            "Your bin collection moves to 18 November. Please put them out the night before.",
            "Bin day is changing. 18 November is the new collection day.",
            "Thank you for your patience. There have been delays.",
            "Collection moves to 18 November. A reminder will follow on 20 November.",
            "collection moves to 18 November. Thanks.",
            "Please note. collection moves to 18 November. See you then.",
        )

    private fun dateCases(): List<GoldenCase> =
        dates.map { (text, now) ->
            GoldenCase(
                "readNoticeDate: \"$text\" at ${java.time.Instant.ofEpochMilli(now)}",
                buildJsonObject {
                    put("call", "readNoticeDate")
                    put("text", text)
                    put("now", now)
                    put("zone", "UTC")
                },
                readJson(readNoticeDate(text, now, UTC)),
            )
        } +
            listOf(
                "Nov 18 at 12:30 am", "Nov 18 at 11 am", "Smarch 5", "December 1st", "on 18 nov.", "Sept 31", "12/25/2026",
                "2026-11-18 at 7:05", "Nov 18 2026-11-18", "Nov 18 at 0:00", "18 November 2026 or 18/11/2026",
            ).map { text ->
                GoldenCase(
                    "readNoticeDate: \"$text\" (extra)",
                    buildJsonObject {
                        put("call", "readNoticeDate")
                        put("text", text)
                        put("now", NOW)
                        put("zone", "UTC")
                    },
                    readJson(readNoticeDate(text, NOW, UTC)),
                )
            } +
            listOf(
                Triple("today is the zone's day: late evening in New York", "Nov 15", nowAt(2026, 11, 15, 3) to "America/New_York"),
                Triple("today is the zone's day: the same moment in UTC", "Nov 15", nowAt(2026, 11, 15, 3) to "UTC"),
                Triple("next year when the date has passed, in Sydney", "Nov 14", nowAt(2026, 11, 14, 14) to "Australia/Sydney"),
            ).map { (name, text, at) ->
                val (now, zone) = at
                GoldenCase(
                    "readNoticeDate: $name",
                    buildJsonObject {
                        put("call", "readNoticeDate")
                        put("text", text)
                        put("now", now)
                        put("zone", zone)
                    },
                    readJson(readNoticeDate(text, now, ZoneId.of(zone))),
                )
            }

    private fun sentenceCases(): List<GoldenCase> =
        (sentences + listOf("Mr. Smith says hi. Dr. Who agrees!", "Line one\nline two\n\nline three.", "Wait?! Really... yes.")).distinct().map { body ->
            GoldenCase(
                "noticeSentences: \"${body.take(40)}\"",
                buildJsonObject {
                    put("call", "noticeSentences")
                    put("body", body)
                },
                JsonArray(noticeSentences(body).map(::JsonPrimitive)),
            )
        } +
            (bodies + listOf("", "   \n  ", "   leading space. Then 18 November.", "18 November")).map { body ->
                GoldenCase(
                    "keptNoticeSentence: \"${body.take(50)}\"",
                    buildJsonObject {
                        put("call", "keptNoticeSentence")
                        put("body", body)
                        put("title", "Bins")
                        put("now", NOW)
                        put("zone", "UTC")
                    },
                    keptNoticeSentence(body, "Bins", NOW, UTC)?.let(::JsonPrimitive) ?: JsonNull,
                )
            }

    private fun ruleCases(): List<GoldenCase> {
        val notice = noticeDoc("Bin collection change", "Collection moves to Wednesday 18 November.")
        val rules =
            listOf(
                "an email rule matches case-insensitively, with no words" to NoticeRule(SourceKind.Email, "  council@EXAMPLE.gov "),
                "a word in the sentence" to NoticeRule(SourceKind.Email, "council@example.gov", listOf("collection")),
                "a word in the title, any case" to NoticeRule(SourceKind.Email, "council@example.gov", listOf("BIN")),
                "none of the words" to NoticeRule(SourceKind.Email, "council@example.gov", listOf("water", "rent")),
                "another sender" to NoticeRule(SourceKind.Email, "school@example.org"),
                "another source kind" to NoticeRule(SourceKind.App, "council@example.gov"),
                "an address with no @ never matches" to NoticeRule(SourceKind.Email, "council"),
                "a blank value never matches" to NoticeRule(SourceKind.Email, "   "),
            )
        val app = noticeDoc("Delivery update", "Your parcel now arrives Friday.", sourceKind = "app", sourceValue = "com.Courier.App")
        val appRules =
            listOf(
                "an app rule matches its package exactly" to NoticeRule(SourceKind.App, "com.Courier.App"),
                "an app rule is case-sensitive" to NoticeRule(SourceKind.App, "com.courier.app"),
            )
        return (rules.map { it to notice } + appRules.map { it to app }).map { (rule, doc) ->
            val (name, r) = rule
            GoldenCase(
                "matches: $name",
                buildJsonObject {
                    put("call", "matches")
                    put("rule", noticeRulesToJson(listOf(r)).first())
                    put("notice", doc)
                },
                buildJsonObject {
                    put("matches", r.matches(noticeFromDoc(doc)))
                    put("noticeText", noticeText(noticeFromDoc(doc)))
                },
            )
        }
    }

    private fun proposalCases(): List<GoldenCase> {
        val zone = ZoneId.of("America/New_York")
        val now = at("America/New_York", 2026, 11, 14, 12)
        val council = NoticeRule(SourceKind.Email, "council@example.gov", listOf("bin", "collection"))
        fun task(id: String, name: String, schedule: JsonObject, rules: List<NoticeRule>, active: Boolean = true, pauses: JsonArray? = null) =
            JsonObject(
                reminderDoc(localId = id, kind = "task", dosage = null, name = name, schedule = schedule, active = active, pauses = pauses, createdAt = now - 90L * 86_400_000) +
                    ("noticeRules" to noticeRulesToJson(rules)),
            )
        val weekly = { day: Int ->
            buildJsonObject {
                put("type", "weekly")
                put("daysOfWeek", JsonArray(listOf(JsonPrimitive(day))))
                put("times", JsonArray(listOf(JsonPrimitive("07:00"))))
            }
        }
        val tasks =
            listOf(
                task("task-bins", "Put the bins out", weekly(2), listOf(council)),
                task("task-recycling", "Recycling", weekly(3), listOf(council)),
                task("task-stopped", "Old bins", weekly(2), listOf(council), active = false),
                task("task-unruled", "Water the plants", weekly(2), emptyList()),
                task("task-backlog", "Garden waste", buildJsonObject { put("type", "none") }, listOf(council)),
                task("task-paused", "Bulky waste", weekly(4), listOf(council), pauses = JsonArray(listOf(buildJsonObject { put("from", at("America/New_York", 2026, 11, 17)) }))),
                JsonObject(reminderDoc(localId = "med-1", schedule = weekly(2)) + ("noticeRules" to noticeRulesToJson(listOf(council)))),
            )
        val answered = instanceDoc("task-recycling", at("America/New_York", 2026, 11, 18, 7), "completed", completedAt = at("America/New_York", 2026, 11, 18, 7))
        data class P(val name: String, val notice: JsonObject, val instances: List<JsonObject> = emptyList())
        return listOf(
            P("a date with no time keeps the task's own time", noticeDoc("Bin collection change", "Collection moves to Wednesday 18 November.")),
            P("a date with a time", noticeDoc("Bin collection change", "Collection moves to 18 November at 6pm.")),
            P("a date already past reads as next year", noticeDoc("Bin collection change", "Collection moved to 10 November.")),
            P("today at a time already gone is in the past", noticeDoc("Bin collection change", "Collection moves to 14 November at 9am.")),
            P("an answered occurrence is not moved", noticeDoc("Bin collection change", "Collection moves to 19 November."), listOf(answered)),
            P("no date, no proposals", noticeDoc("Bin collection change", "There have been delays.")),
            P("a sender with no rule", noticeDoc("Bin collection change", "Collection moves to 18 November.", sourceValue = "someone@example.com")),
            P("no word the rules ask for", noticeDoc("Water outage", "Supply returns 18 November.")),
        ).map { p ->
            val proposals =
                proposalsFor(noticeFromDoc(p.notice), tasks.map(::reminderFromDoc), p.instances.map(::instanceFromDoc), now, zone)
            GoldenCase(
                "proposalsFor: ${p.name}",
                buildJsonObject {
                    put("call", "proposalsFor")
                    put("notice", p.notice)
                    put("reminders", JsonArray(tasks))
                    put("instances", JsonArray(p.instances))
                    put("now", now)
                    put("zone", "America/New_York")
                },
                JsonArray(
                    proposals.map { pr ->
                        buildJsonObject {
                            put("reminderId", pr.reminder.id)
                            put("from", pr.from?.let(::JsonPrimitive) ?: JsonNull)
                            put("to", pr.to)
                            put("date", pr.date.toString())
                            put("time", pr.time?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                            put("matchedText", pr.matchedText)
                            put("refusal", refusalJson(pr.refusal))
                            put("applies", pr.applies)
                        }
                    },
                ),
            )
        } +
            listOf(
                Triple("three either side of a weekly task's moment", tasks[0], at("America/New_York", 2026, 11, 17, 7)),
                Triple("between two moments", tasks[0], at("America/New_York", 2026, 11, 20)),
                Triple("a Backlog task has none", tasks[4], now),
                Triple("a medication has none", tasks[6], now),
            ).map { (name, doc, around) ->
                GoldenCase(
                    "occurrenceOptionsAround: $name",
                    buildJsonObject {
                        put("call", "occurrenceOptionsAround")
                        put("reminder", doc)
                        put("around", around)
                        put("zone", "America/New_York")
                    },
                    JsonArray(occurrenceOptionsAround(reminderFromDoc(doc), around, zone).map(::JsonPrimitive)),
                )
            }
    }

    override fun cases(): List<GoldenCase> = dateCases() + sentenceCases() + ruleCases() + proposalCases()
}
