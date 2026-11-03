package com.taskrx.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private fun parse(text: String) = pausesFromJson(Json.parseToJsonElement(text))

class PauseTest {
    @Test
    fun `a window must end after it starts`() {
        assertFailsWith<IllegalArgumentException> { PauseWindow(10, 10) }
        assertFailsWith<IllegalArgumentException> { PauseWindow(10, 9) }
        PauseWindow(10, 11)
        PauseWindow(10, null)
    }

    @Test
    fun `the start moment is paused and the end moment is not`() {
        val windows = listOf(PauseWindow(100, 200))

        assertFalse(isPausedAt(windows, 99))
        assertTrue(isPausedAt(windows, 100))
        assertTrue(isPausedAt(windows, 199))
        assertFalse(isPausedAt(windows, 200))
    }

    @Test
    fun `an open window pauses everything from its start`() {
        val windows = listOf(PauseWindow(100))

        assertFalse(isPausedAt(windows, 99))
        assertTrue(isPausedAt(windows, 100))
        assertTrue(isPausedAt(windows, Long.MAX_VALUE))
    }

    @Test
    fun `any window of several can pause a moment`() {
        val windows = listOf(PauseWindow(10, 20), PauseWindow(50, 60))

        assertTrue(isPausedAt(windows, 15))
        assertFalse(isPausedAt(windows, 30))
        assertTrue(isPausedAt(windows, 55))
        assertFalse(isPausedAt(emptyList(), 55))
    }

    @Test
    fun `the live pause is the one that has not finished`() {
        val over = PauseWindow(10, 20)
        val running = PauseWindow(30, 90)
        val open = PauseWindow(100)

        assertNull(livePause(emptyList(), 50))
        assertNull(livePause(listOf(over), 50))
        assertEquals(running, livePause(listOf(over, running), 50))
        assertEquals(running, livePause(listOf(running), 10), "one that starts later is live too")
        assertEquals(open, livePause(listOf(over, open), 500))
        assertNull(livePause(listOf(running), 90), "it is over at its end moment")
    }

    @Test
    fun `adding a pause keeps the earlier ones`() {
        val over = PauseWindow(10, 20)

        assertEquals(listOf(over, PauseWindow(100, 200)), addPause(listOf(over), 100, 200, now = 50))
        assertEquals(listOf(PauseWindow(100)), addPause(emptyList(), 100, null, now = 50))
    }

    @Test
    fun `a second pause is refused while one is running or waiting`() {
        assertFailsWith<IllegalArgumentException> { addPause(listOf(PauseWindow(10)), 100, 200, now = 50) }
        assertFailsWith<IllegalArgumentException> { addPause(listOf(PauseWindow(100, 200)), 300, 400, now = 50) }
    }

    @Test
    fun `a window that ends before it starts is refused`() {
        assertFailsWith<IllegalArgumentException> { addPause(emptyList(), 100, 100, now = 50) }
    }

    @Test
    fun `resuming a running pause ends it now and keeps its moments paused`() {
        val resumed = resumePause(listOf(PauseWindow(10, 20), PauseWindow(30)), now = 50)

        assertEquals(listOf(PauseWindow(10, 20), PauseWindow(30, 50)), resumed)
        assertTrue(isPausedAt(resumed, 49))
        assertFalse(isPausedAt(resumed, 50))
    }

    @Test
    fun `resuming a pause with an end ends it early`() {
        assertEquals(listOf(PauseWindow(30, 50)), resumePause(listOf(PauseWindow(30, 90)), now = 50))
    }

    @Test
    fun `resuming a pause that has not started removes it`() {
        assertEquals(listOf(PauseWindow(10, 20)), resumePause(listOf(PauseWindow(10, 20), PauseWindow(100, 200)), now = 50))
        assertEquals(emptyList(), resumePause(listOf(PauseWindow(50)), now = 50), "starting this very moment is not yet paused")
    }

    @Test
    fun `resuming with nothing live changes nothing`() {
        val windows = listOf(PauseWindow(10, 20))

        assertEquals(windows, resumePause(windows, now = 50))
        assertEquals(emptyList(), resumePause(emptyList(), now = 50))
    }

    @Test
    fun `windows round-trip through the wire shape`() {
        val windows = listOf(PauseWindow(1_700_000_000_000, 1_700_000_900_000), PauseWindow(1_800_000_000_000))

        assertEquals(windows, pausesFromJson(pausesToJson(windows)))
        assertEquals("[]", pausesToJson(emptyList()).toString())
        assertEquals("""[{"from":5}]""", pausesToJson(listOf(PauseWindow(5))).toString())
    }

    @Test
    fun `a server float is read as the whole number it spells`() {
        assertEquals(listOf(PauseWindow(1_700_000_000_000, 1_700_000_900_000)), parse("""[{"from":1.7e12,"until":1700000900000.0}]"""))
        assertEquals(listOf(PauseWindow(5)), parse("""[{"from":5}]"""))
    }

    @Test
    fun `anything that is not a well-formed list reads as not paused`() {
        assertEquals(emptyList(), pausesFromJson(null))
        assertEquals(emptyList(), pausesFromJson(JsonNull))
        assertEquals(emptyList(), pausesFromJson(JsonPrimitive("x")))
        assertEquals(emptyList(), parse("""[5]"""))
        assertEquals(emptyList(), parse("""[{"until":9}]"""))
        assertEquals(emptyList(), parse("""[{"from":"5"}]"""))
        assertEquals(emptyList(), parse("""[{"from":5.5}]"""))
        assertEquals(emptyList(), parse("""[{"from":true}]"""))
        assertEquals(emptyList(), parse("""[{"from":{}}]"""))
        assertEquals(emptyList(), parse("""[{"from":5,"until":"9"}]"""))
        assertEquals(emptyList(), parse("""[{"from":5,"until":5}]"""))
        assertEquals(emptyList(), parse("""[{"from":5,"until":4}]"""))
        assertEquals(emptyList(), parse("""[{"from":1,"until":2},{"from":"x"}]"""), "one bad window spoils the list")
    }
}
