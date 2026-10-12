package com.taskrx.core.parser

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * The rules the natural-language entry surface renders, stated as rules.
 *
 * The grammar itself is `ReminderParserTest`'s subject, not this file's. Where
 * a case here does name a parsed value — `"20mg"`, `"with food"`, `08:00` — the
 * value is not what is being pinned: the claim is that the field *survives the
 * crossing* into the draft, and naming a real one is the only way to state that.
 * Deleting `dosage = reminder.dosage` has to fail a case here, and it does.
 * Those cases stay narrow for that reason, and a phrase is never added here to
 * cover a lexicon entry.
 *
 * What is asserted for its own sake is what the *review* step needs: that a
 * phrase always yields something editable, that a phrase the parser could not
 * read still yields something editable, that a correction cannot leave a task
 * carrying a dose or a drug identity, and what makes a draft savable.
 */

private val NOW: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

private fun read(phrase: String): ReminderDraft = readPhrase(phrase, ReadPhraseOptions(now = NOW)).draft

class ReadPhraseTest {
    @Test
    fun `reads a medication phrase without being told the Kind`() {
        val reading = readPhrase("atorvastatin 20mg every morning with food", ReadPhraseOptions(now = NOW))

        assertTrue(reading.understood)
        assertEquals(emptyList(), reading.reasons)
        assertEquals(ReminderKind.Medication, reading.draft.kind)
        assertEquals("atorvastatin", reading.draft.name)
        assertEquals("20mg", reading.draft.dosage)
        assertEquals("with food", reading.draft.instructions)
        assertEquals(dailyTimes(listOf("08:00")), reading.draft.schedule)
    }

    @Test
    fun `reads a task phrase without being told the Kind`() {
        val draft = read("call pharmacy friday 2pm")

        assertEquals(ReminderKind.Task, draft.kind)
        assertEquals("call pharmacy", draft.name)
        assertNull(draft.dosage)
    }

    @Test
    fun `carries the extracted supply details onto the draft`() {
        val draft = read("metformin 500mg daily 30 pills 1 per dose refill in 7")

        assertEquals(30.0, draft.pillsRemaining)
        assertEquals(1.0, draft.pillsPerDose)
        assertEquals(7, draft.refillReminderDays)
    }

    @Test
    fun `carries the extracted priority onto the draft`() {
        assertEquals(Priority.Critical, read("urgent call the surgery").priority)
    }

    @Test
    fun `claims no RxNorm identity, because no phrase can name one`() {
        assertNull(read("atorvastatin 20mg every morning").rxcui)
    }

    @Test
    fun `surfaces the tokens it could not place rather than dropping them`() {
        val draft = read("aspirin 81mg daily wibble")

        assertEquals(listOf("wibble"), draft.unparsedTokens)
        assertEquals("aspirin", draft.name)
    }

    @Test
    fun `reads the same phrase the same way every time`() {
        val first = readPhrase("aspirin 81mg every 8 hours", ReadPhraseOptions(now = NOW))
        val second = readPhrase("aspirin 81mg every 8 hours", ReadPhraseOptions(now = NOW))

        assertEquals(first, second)
        assertEquals(intervalTimes(8, NOW), first.draft.schedule)
    }

    @Test
    fun `still offers a draft when it cannot read the phrase`() {
        val reading = readPhrase("9am", ReadPhraseOptions(now = NOW))

        assertFalse(reading.understood)
        assertTrue(reading.reasons.isNotEmpty())
        assertEquals("9am", reading.draft.name)
    }

    @Test
    fun `names no moment at all for a phrase it could not read`() {
        assertEquals(noneTimes(), readPhrase("9am", ReadPhraseOptions(now = NOW)).draft.schedule)
    }

    @Test
    fun `offers an empty draft for an empty phrase`() {
        val reading = readPhrase("   ", ReadPhraseOptions(now = NOW))

        assertFalse(reading.understood)
        assertEquals("", reading.draft.name)
        assertFalse(isSavableDraft(reading.draft))
    }
}

class WithKindTest {
    @Test
    fun `drops the medication-only fields when a draft becomes a task`() {
        val medication = read("metformin 500mg daily 30 pills 1 per dose refill in 7")
        val task = withKind(medication, ReminderKind.Task)

        assertEquals(ReminderKind.Task, task.kind)
        assertNull(task.dosage)
        assertNull(task.pillsRemaining)
        assertNull(task.pillsPerDose)
        assertNull(task.refillReminderDays)
    }

    @Test
    fun `keeps the fields both Kinds share when a draft becomes a task`() {
        val medication = read("atorvastatin 20mg every morning with food")
        val task = withKind(medication, ReminderKind.Task)

        assertEquals("atorvastatin", task.name)
        assertEquals("with food", task.instructions)
        assertEquals(dailyTimes(listOf("08:00")), task.schedule)
    }

    @Test
    fun `drops the drug identity when a draft becomes a task`() {
        val picked = read("metformin 500mg daily").copy(rxcui = "860975")

        assertNull(withKind(picked, ReminderKind.Task).rxcui)
    }

    @Test
    fun `keeps the drug identity while a draft is still a medication`() {
        val picked = read("metformin 500mg daily").copy(rxcui = "860975")

        assertEquals("860975", withKind(picked, ReminderKind.Medication).rxcui)
    }

    @Test
    fun `leaves the rest of the draft alone when it becomes a medication`() {
        val task = read("call pharmacy friday 2pm")
        val medication = withKind(task, ReminderKind.Medication)

        assertEquals(ReminderKind.Medication, medication.kind)
        assertEquals("call pharmacy", medication.name)
        assertEquals(task.schedule, medication.schedule)
    }
}

class WithNameTest {
    @Test
    fun `drops a drug identity the new name was never checked against`() {
        val picked = read("metformin 500mg daily").copy(rxcui = "860975")

        assertNull(withName(picked, "Metformin").rxcui)
    }

    @Test
    fun `renames without disturbing the rest of the draft`() {
        val draft = read("metformin 500mg daily")
        val renamed = withName(draft, "Metformin XR")

        assertEquals("Metformin XR", renamed.name)
        assertEquals("500mg", renamed.dosage)
        assertEquals(draft.schedule, renamed.schedule)
        assertEquals(ReminderKind.Medication, renamed.kind)
    }
}

class IsSavableDraftTest {
    @Test
    fun `refuses a draft with no name`() {
        val draft = read("aspirin 81mg daily")

        assertFalse(isSavableDraft(draft.copy(name = "   ")))
    }

    @Test
    fun `refuses a draft whose schedule cannot produce a moment`() {
        val draft = read("aspirin 81mg daily")

        assertFalse(isSavableDraft(draft.copy(schedule = dailyTimes(emptyList()))))
    }

    @Test
    fun `accepts a named draft with a usable schedule`() {
        assertTrue(isSavableDraft(read("aspirin 81mg daily")))
    }

    @Test
    fun `accepts a named draft with no schedule at all, which is the Backlog`() {
        val draft = read("aspirin 81mg daily")

        assertTrue(isSavableDraft(draft.copy(schedule = noneTimes())))
    }
}

// Not in the vitest suite, but pinned against what V8 does: JS `trim()` strips
// U+FEFF and keeps U+001F, the reverse of Kotlin's `trim()`.
class DraftJsWhitespaceTest {
    @Test
    fun `a phrase that is only a byte order mark is not savable as a name`() {
        val draft = read("aspirin 81mg daily")

        assertFalse(isSavableDraft(draft.copy(name = "\uFEFF")))
        assertTrue(isSavableDraft(draft.copy(name = "\u001F")))
    }

    @Test
    fun `an unreadable phrase becomes the name with JS trimming`() {
        assertEquals("!!!", read("\uFEFF!!!\uFEFF").name)
        assertEquals("\u001F", read("\u001F").name)
    }
}
