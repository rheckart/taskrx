package com.taskrx.core.golden

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.parser.ReadPhraseOptions
import com.taskrx.core.parser.ReminderDraft
import com.taskrx.core.parser.isSavableDraft
import com.taskrx.core.parser.readPhrase
import com.taskrx.core.parser.withKind
import com.taskrx.core.parser.withName
import com.taskrx.core.sync.scheduleToServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** The moment the parser tests read at; `every 8 hours` starts from it. */
private val NOW: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

private fun draftJson(d: ReminderDraft): JsonObject =
    buildJsonObject {
        put("kind", d.kind.wire)
        put("name", d.name)
        put("schedule", scheduleToServer(d.schedule))
        put("priority", d.priority.wire)
        put("dosage", d.dosage?.let(::JsonPrimitive) ?: JsonNull)
        put("instructions", d.instructions?.let(::JsonPrimitive) ?: JsonNull)
        put("pillsRemaining", d.pillsRemaining?.let(::JsonPrimitive) ?: JsonNull)
        put("pillsPerDose", d.pillsPerDose?.let(::JsonPrimitive) ?: JsonNull)
        put("refillReminderDays", d.refillReminderDays?.let(::JsonPrimitive) ?: JsonNull)
        put("rxcui", d.rxcui?.let(::JsonPrimitive) ?: JsonNull)
        put("unparsedTokens", JsonArray(d.unparsedTokens.map(::JsonPrimitive)))
        put("isSavable", isSavableDraft(d))
    }

/**
 * Add and edit: `readPhrase`, the one sentence a person types, read into a draft. The phrases
 * are every one the parser's own tests read, plus a few for the Schedules those tests do not
 * name. `reasons` are shown to the person, so their wording is part of the contract. The draft's
 * schedule is in the Convex shape, as a save would send it.
 */
object ParserGolden : GoldenSet {
    override val name = "parser"
    override val about =
        "readPhrase(phrase, now) -> {understood, reasons, draft (+ isSavableDraft)}; withKind and withName on a read draft"

    private val phrases =
        listOf(
            "atorvastatin 20mg every morning with food",
            "metformin 500mg twice daily",
            "aspirin 81mg every 8 hours",
            "do laundry every monday wednesday friday at 9am",
            "",
            "atorvastatin 20mg frobnicate",
            "urgent lisinopril 10mg daily",
            "atorvastatin 20mg daily 30 pills 1 per dose refill in 7 days",
            "atorvastatin daily",
            "take out trash weekly",
            "atorvastatin 20mg daily",
            "atorvastatin 20mg every morning at 9pm",
            "!!! ??? ***",
            "   \t\n ",
            "daily",
            "gym session",
            "check the boiler pressure tomorrow",
            "take atorvastatin 20mg daily",
            "gym session today",
            "gym session today now",
            "call the dentist",
            "take out the trash every two weeks",
            "water the plants downstairs",
            "pick up the recycling bins",
            "put the bins out",
            "Water the plants downstairs",
            "take out the trash",
            "pick the up bins",
            "take the",
            "the trash",
            "take laundry at 9am",
            "take ibuprofen 200mg every 8 hours",
            "important call mom",
            "call plumber low priority",
            "call plumber low bridge",
            "call plumber low",
            "vitamin d 1000units every 3 days",
            "water the plants every 2 weeks",
            "Take out the trash every two weeks",
            "aspirin 81mg every eight hours",
            "vitamin d 1000units every three days",
            "aspirin 81mg every a minute",
            "aspirin 81mg every one week",
            "aspirin 81mg every thirty minutes",
            "aspirin 81mg every 15 mins",
            "aspirin 81mg every ninety minutes",
            "aspirin 81mg every 2 hrs",
            "water the plants every fortnight",
            "water the plants every one fortnight",
            "water the plants every two fortnights",
            "water the plants every 2 wks",
            "aspirin 81mg every 4 days",
            "aspirin 81mg every 0 hours",
            "aspirin 81mg every twelve weeks",
            "vitamin d 1000units every 2147483647 days",
            "aspirin 81mg every 8 bananas",
            "aspirin 81mg every 8",
            "aspirin 81mg every 99999999999 hours",
            "aspirin 81mg every purple",
            "atorvastatin 20mg every",
            "pay rent monthly on the 15th at 9am",
            "call landlord monthly",
            "pay rent every month",
            "pay this month's rent",
            "pay rent monthly on the 9th at 9",
            "pay rent monthly 9 at 09:00",
            "pay rent monthly at 9",
            "pay rent monthly on the 99th",
            "pay rent every month on the 15th and the 1st at 9am and 8pm",
            "pay rent at 15 monthly on the 1st",
            "pay rent monthly twice daily",
            "pay rent monthly on the 15th Monday",
            "pay rent monthly on the 15th at 9am Monday",
            "pay rent monthly at noon",
            "pay rent monthly at 8pm",
            "pay rent monthly the 1st at 24",
            "pay rent monthly at 12:30",
            "take out the trash the 1st at 9am monthly",
            "pay rent every month on the 15th and the 1st at 09:00",
            "pay rent monthly 3 at 9am",
            "pay rent monthly the 21st at 9am",
            "pay rent monthly on the 1st at 8am and 8pm",
            "pay rent monthly",
            "atorvastatin 20mg 8:30pm",
            "atorvastatin 20mg at 18:30",
            "atorvastatin 20mg at 8:00:00",
            "atorvastatin 20mg at 12am",
            "atorvastatin 20mg at 12pm",
            "atorvastatin 20mg at 25:00",
            "atorvastatin 20mg at 23:59",
            "atorvastatin 20mg at 24:00",
            "atorvastatin 20mg at 8:60",
            "atorvastatin 20mg at 8:70",
            "atorvastatin 20mg at 13pm",
            "atorvastatin 20mg at 0am",
            "atorvastatin 20mg at 8",
            "atorvastatin 20mg at",
            "atorvastatin 20mg bedtime",
            "atorvastatin 20mg daily at 9pm",
            "atorvastatin 20mg every morning at 8am",
            "aspirin 81mg every 8 hours at 9am",
            "call mom monday at 9pm at 9am",
            "call mom monday",
            "call mom friday monday monday",
            "call mom tues thurs",
            "call mom monday weekly",
            "atorvastatin 20mg 30 pills",
            "atorvastatin 20mg per dose",
            "atorvastatin 20mg 1.5 per dose",
            "atorvastatin 20mg 30 pills per dose",
            "atorvastatin 20mg 30 bananas",
            "atorvastatin 20mg refill in 7",
            "atorvastatin 20mg refill in soon",
            "atorvastatin 20mg refill",
            "atorvastatin 20mg 30",
            "atorvastatin 20mg with juice",
            "atorvastatin 20mg with",
            "atorvastatin 20mg 30 pills per day",
            "atorvastatin 20mg 30 per day",
            "atorvastatin 20mg per day",
            "atorvastatin 20mg 1.5 per day",
            "call pharmacy friday 2pm",
            "metformin 500mg daily 30 pills 1 per dose refill in 7",
            "urgent call the surgery",
            "atorvastatin 20mg every morning",
            "aspirin 81mg daily wibble",
            "9am",
            "   ",
            "tylenol 500mg daily",
            "metformin 500mg daily",
            "aspirin 81mg daily",
            "\uFEFF!!!\uFEFF",
            "\u001F",
            "take vitamin d on the 1st and 15th at 9am",
            "pay rent monthly on the 1st",
            "water plants every 2 days",
            "call mom sunday 6pm",
            "ibuprofen 200mg as needed",
            "metformin 500mg twice daily 60 pills 2 per dose",
            "stretch every 8 hours starting now",
            "dentist tomorrow 3pm",
            "high priority renew passport",
            "take 2 tablets lisinopril 10mg every evening",
        )

    override fun cases(): List<GoldenCase> {
        val readings =
            phrases.map { phrase ->
                val reading = readPhrase(phrase, ReadPhraseOptions(now = NOW))
                GoldenCase(
                    "readPhrase: ${phrase.ifBlank { "(blank: ${phrase.length} chars)" }}",
                    buildJsonObject {
                        put("call", "readPhrase")
                        put("phrase", phrase)
                        put("now", NOW)
                    },
                    buildJsonObject {
                        put("understood", reading.understood)
                        put("reasons", JsonArray(reading.reasons.map(::JsonPrimitive)))
                        put("draft", draftJson(reading.draft))
                    },
                )
            }
        val corrections =
            listOf(
                "metformin 500mg daily 30 pills 1 per dose refill in 7",
                "ibuprofen 200mg as needed",
                "call pharmacy friday 2pm",
            ).flatMap { phrase ->
                val draft = readPhrase(phrase, ReadPhraseOptions(now = NOW)).draft.copy(rxcui = "860975")
                val input =
                    buildJsonObject {
                        put("phrase", phrase)
                        put("now", NOW)
                        put("draft", draftJson(draft))
                    }
                ReminderKind.entries.map { kind ->
                    GoldenCase(
                        "withKind ${kind.wire}: $phrase",
                        JsonObject(input + ("call" to JsonPrimitive("withKind")) + ("kind" to JsonPrimitive(kind.wire))),
                        draftJson(withKind(draft, kind)),
                    )
                } +
                    listOf("Metformin", "   ").map { name ->
                        GoldenCase(
                            "withName \"$name\": $phrase",
                            JsonObject(input + ("call" to JsonPrimitive("withName")) + ("name" to JsonPrimitive(name))),
                            draftJson(withName(draft, name)),
                        )
                    }
            }
        return readings + corrections
    }
}
