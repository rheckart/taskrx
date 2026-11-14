package com.taskrx.core.notices

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UTC: ZoneId = ZoneOffset.UTC

private fun nowAt(year: Int, month: Int, day: Int, hour: Int = 12): Long =
    ZonedDateTime.of(year, month, day, hour, 0, 0, 0, UTC).toInstant().toEpochMilli()

private val NOW = nowAt(2026, 11, 14)

class IntakeTest {
    @Test
    fun `sentences split on terminators and keep the terminator`() {
        assertEquals(
            listOf("The bins move.", "Please put them out.", "Thanks."),
            noticeSentences("The bins move. Please put them out. Thanks."),
        )
        assertEquals(listOf("Is it Tuesday?", "Yes!"), noticeSentences("Is it Tuesday? Yes!"))
        assertEquals(listOf("Wow..."), noticeSentences("Wow..."))
    }

    @Test
    fun `a blank line separates sentences and a line with no terminator is one`() {
        assertEquals(listOf("Bin day changed", "Collection moves to Wednesday."), noticeSentences("Bin day changed\n\nCollection moves to Wednesday."))
        assertEquals(listOf("one long line with no punctuation at all"), noticeSentences("one long line with no punctuation at all"))
    }

    @Test
    fun `empty and whitespace bodies yield no sentences`() {
        assertEquals(emptyList(), noticeSentences(""))
        assertEquals(emptyList(), noticeSentences("   \n\t\n  "))
    }

    @Test
    fun `a leading terminator does not open an empty sentence`() {
        assertEquals(listOf("Hi."), noticeSentences("Hi." ))
    }

    @Test
    fun `a sentence that starts with a capital reads on its own`() {
        val body = "There is a change to your bin day. Collection moves to Wednesday 18 November. Thank you."
        assertEquals("Collection moves to Wednesday 18 November.", keptNoticeSentence(body, "Bins", NOW, UTC))
    }

    @Test
    fun `a lowercase fragment pulls the sentence before it in`() {
        val body = "There is a change to your bin day. collection moves to Wednesday 18 November. Thank you."
        assertEquals(
            "There is a change to your bin day. collection moves to Wednesday 18 November.",
            keptNoticeSentence(body, "Bins", NOW, UTC),
        )
    }

    @Test
    fun `a sentence with its own subject is kept alone`() {
        val body = "Your bin collection moves to 18 November. Please put them out the night before."
        assertEquals("Your bin collection moves to 18 November.", keptNoticeSentence(body, "Bins", NOW, UTC))
    }

    @Test
    fun `a dated sentence that starts with a digit is not treated as a fragment`() {
        val body = "Bin day is changing. 18 November is the new collection day."
        assertEquals("18 November is the new collection day.", keptNoticeSentence(body, "Bins", NOW, UTC))
    }

    @Test
    fun `when no sentence yields a date the first sentence is kept`() {
        val body = "Thank you for your patience. There have been delays."
        assertEquals("Thank you for your patience.", keptNoticeSentence(body, "Service update", NOW, UTC))
    }

    @Test
    fun `when the body is empty the subject is the kept text`() {
        assertEquals("Bins moved", keptNoticeSentence("", "Bins moved", NOW, UTC))
        assertEquals("Bins moved", keptNoticeSentence("   ", "Bins moved", NOW, UTC))
        assertNull(keptNoticeSentence("", "", NOW, UTC))
        assertNull(keptNoticeSentence("   ", "  ", NOW, UTC))
    }

    @Test
    fun `the first sentence with a date wins when later sentences also name one`() {
        val body = "Collection moves to 18 November. A reminder will follow on 20 November."
        assertEquals("Collection moves to 18 November.", keptNoticeSentence(body, "Bins", NOW, UTC))
    }

    @Test
    fun `a fragment at the very start is not pulled past the beginning`() {
        val body = "collection moves to 18 November. Thanks."
        assertEquals("collection moves to 18 November.", keptNoticeSentence(body, "Bins", NOW, UTC))
    }

    @Test
    fun `a leg of two sentences keeps at most the pair`() {
        val body = "Please note. collection moves to 18 November. See you then."
        val kept = keptNoticeSentence(body, "Bins", NOW, UTC)
        assertTrue(kept!!.contains("Please note."))
        assertEquals("Please note. collection moves to 18 November.", kept)
    }
}
