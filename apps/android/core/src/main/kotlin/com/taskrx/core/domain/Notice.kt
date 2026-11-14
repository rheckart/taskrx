package com.taskrx.core.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Notices: outside messages a watched source admitted, kept as evidence rather than as
 * commitments. A Notice never changes anything by itself; it is what a Proposal is made
 * from, and the person answers the Proposal (see `docs/plans/external-notices.md`).
 *
 * Retention is deliberately narrow: the source, the subject, the sentence the date came
 * from, and when it arrived. The body is not kept, and nothing here parses a date - the
 * reader lives in `core/notices/Extract.kt` and is the only implementation of that rule.
 */

/** Where a Notice came from. A watched source can only be an email or an app; a share is the person's own act. */
enum class SourceKind(val wire: String) {
    Email("email"),
    App("app"),
    Share("share"),
    ;

    companion object {
        fun fromWire(wire: String): SourceKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What became of a Notice: still waiting for a decision, applied as a move to an existing
 * task, turned into a new task, or put aside.
 *
 * The two task outcomes are separate values because they say different things on screen: a
 * move leaves the task that was already there, and a new task did not exist before.
 */
enum class NoticeResolution(val wire: String) {
    None("none"),
    Applied("applied"),
    NewTask("newTask"),
    Dismissed("dismissed"),
    ;

    companion object {
        fun fromWire(wire: String): NoticeResolution? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One outside message, as it was admitted.
 *
 * [sentence] is the part the person is shown - the date's own sentence once extraction
 * exists, the shared text until then. [externalId] is what de-duplication keys on (a JMAP
 * email id, a notification key, a share hash); null when the road supplies none.
 */
data class Notice(
    val id: String,
    val userId: String,
    val sourceKind: SourceKind,
    val sourceValue: String,
    val title: String,
    val sentence: String?,
    val receivedAt: Long,
    val externalId: String?,
    val resolution: NoticeResolution,
    val resolvedAt: Long?,
    val createdAt: Long,
    override val updatedAt: Long,
) : Timestamped

/**
 * A sender address or an app whose notices may be kept at all: the gate on what is stored,
 * not on what is proposed. Matching words live on the Reminder's [NoticeRule], so adding a
 * watched source alone never moves a task.
 */
data class WatchedSource(
    val id: String,
    val userId: String,
    val kind: SourceKind,
    val value: String,
    val label: String,
    val createdAt: Long,
    override val updatedAt: Long,
) : Timestamped

/**
 * A rule on a Reminder: notices from [sourceValue] that mention one of [terms] may be
 * proposed against it. Authored next to Pause ("Notices about this"), and only the
 * pre-filled version of what the person can still do by hand.
 *
 * An empty [terms] means "any notice from this source", which is what a rule with no
 * matching words asks for.
 */
data class NoticeRule(
    val sourceKind: SourceKind,
    val sourceValue: String,
    val terms: List<String> = emptyList(),
)

/**
 * The canonical spelling of a source, or null when it cannot be one.
 *
 * An email is case-insensitive, so it is lowercased and must look like an address; an app
 * package is exact, so only surrounding space is removed. One normaliser for both roads, so
 * a rule and the notice it is meant to match cannot disagree about spelling.
 */
fun normalizeSourceValue(kind: SourceKind, raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when (kind) {
        SourceKind.Email -> trimmed.lowercase().takeIf { '@' in it }
        SourceKind.App, SourceKind.Share -> trimmed
    }
}

/** "water bill, collection": the words a person typed, split and cleaned. Empty entries are dropped. */
fun parseNoticeTerms(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/* The wire shape, also the stored one: a JSON array of `{sourceKind, sourceValue, terms}`. */

fun noticeRulesToJson(rules: List<NoticeRule>): JsonArray =
    buildJsonArray {
        for (rule in rules) {
            add(
                buildJsonObject {
                    put("sourceKind", rule.sourceKind.wire)
                    put("sourceValue", rule.sourceValue)
                    put("terms", JsonArray(rule.terms.map(::JsonPrimitive)))
                },
            )
        }
    }

/**
 * Reads [value] back, or returns an empty list for anything that is not a well-formed array
 * of rules. An empty list proposes nothing, which is the safe misreading: no task moves
 * until the person has actually authored a rule.
 */
fun noticeRulesFromJson(value: JsonElement?): List<NoticeRule> {
    if (value !is JsonArray) return emptyList()
    return value.map { element ->
        val entry = element as? JsonObject ?: return emptyList()
        val kind = (entry["sourceKind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(SourceKind::fromWire) ?: return emptyList()
        val rawValue = (entry["sourceValue"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
        val sourceValue = normalizeSourceValue(kind, rawValue) ?: return emptyList()
        val terms =
            when (val termsElement = entry["terms"]) {
                null -> emptyList()
                is JsonArray ->
                    termsElement.map { term ->
                        (term as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
                    }
                else -> return emptyList()
            }
        NoticeRule(kind, sourceValue, terms)
    }
}
