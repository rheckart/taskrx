package com.taskrx.core.notices

import java.time.ZoneId

/*
 * Choosing the part of an outside message a Notice keeps.
 *
 * Retention is narrow on purpose (D14): the source, the subject, and at most one or two
 * sentences around the date. The body itself is discarded. Splitting and choosing is pure
 * text work with no Android in it, so it lives here beside the reader it exists to serve and
 * is covered by the 100% `:core` gate.
 *
 * The important property is that the kept sentence is the one the reader will read. The
 * calling code runs `readNoticeDate` over each candidate and keeps the sentence that yields a
 * date, not the one this function guesses is important; this function only bounds the work and
 * supplies the fallback when no sentence yields a date at all.
 */

/** At most this many sentences are ever offered to the reader: more is a body, not a notice. */
const val MAX_NOTICE_SENTENCES: Int = 2

/**
 * The sentences of [body], trimmed, with empties dropped.
 *
 * Sentences are split on `.`, `!` and `?`, and on a blank line, which is how a message with no
 * punctuation at all (a notification is one long line) still yields something. The split keeps
 * the terminator with its sentence, so a re-joined sentence reads as the original did.
 */
fun noticeSentences(body: String): List<String> {
    val sentences = ArrayList<String>()
    val current = StringBuilder()
    for (line in body.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            if (current.isNotBlank()) sentences += current.toString().trim()
            current.setLength(0)
            continue
        }
        if (current.isNotEmpty()) current.append(' ')
        var index = 0
        while (index < trimmed.length) {
            val ch = trimmed[index]
            current.append(ch)
            index++
            if (ch == '.' || ch == '!' || ch == '?') {
                // A run of terminators ("...") closes one sentence, not three.
                while (index < trimmed.length && (trimmed[index] == '.' || trimmed[index] == '!' || trimmed[index] == '?')) {
                    current.append(trimmed[index])
                    index++
                }
                sentences += current.toString().trim()
                current.setLength(0)
            }
        }
    }
    if (current.isNotBlank()) sentences += current.toString().trim()
    return sentences.filter { it.isNotEmpty() }
}

/**
 * The text a Notice keeps from one message: the date's own sentence, plus the one before it
 * when that sentence has no subject of its own.
 *
 * "Collection moves to" reads as a fragment; with the sentence before it ("There is a change
 * to your bin day. Collection moves to Wednesday 18 November.") it reads as the message did.
 * A sentence that already names a month or a weekday or starts with a capital reads on its
 * own, so the earlier sentence is not pulled in.
 *
 * When no sentence yields a date, the first sentence is kept: a notice with no date is a
 * supported state (the listener road often has no body at all), and the person still needs the
 * subject to decide what to do with it. [title] is used when [body] has no sentences.
 */
fun keptNoticeSentence(body: String, title: String, now: Long, zone: ZoneId): String? {
    val sentences = noticeSentences(body)
    if (sentences.isEmpty()) return title.trim().ifEmpty { null }

    val readings = sentences.map { readNoticeDate(it, now, zone) }
    val datedIndex = readings.indexOfFirst { it is ReadDate.Found }
    if (datedIndex < 0) return sentences.first()

    // A fragment that leans on the sentence before it: no capital at the start, and no word of
    // its own to say what it is about.
    val dated = sentences[datedIndex]
    val needsLead = datedIndex > 0 && isFragment(dated)
    val start = if (needsLead) datedIndex - 1 else datedIndex
    return sentences.subList(start, datedIndex + 1).joinToString(" ")
}

/**
 * Whether [sentence] reads as a fragment that needs the sentence before it: it does not start
 * with a capital letter or a digit, so it has no subject of its own ("collection moves to…").
 */
private fun isFragment(sentence: String): Boolean {
    val first = sentence.firstOrNull { !it.isWhitespace() } ?: return false
    return !first.isUpperCase() && !first.isDigit()
}
