package com.taskrx.mobile.harness

import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessSpecTest {
    private fun parse(vararg segments: String, now: Long = 5_000L) = parseHarnessSpec(segments.toList(), now)

    @Test
    fun `reads action, id, delay and nonce in that order`() {
        val spec = parse("schedule", "kill-test", "60000", "n7")
        assertEquals(HarnessAction.Schedule, spec.action)
        assertEquals("kill-test", spec.id)
        assertEquals(60_000L, spec.delayMs)
        assertEquals(60_000L, spec.value)
        assertEquals("n7", spec.nonce)
    }

    @Test
    fun `every action word the script sends is known`() {
        val sent =
            listOf(
                "schedule", "schedule-weekly", "schedule-task", "schedule-task-flood", "cancel-all", "pending-count", "pending-ids",
                "exact-alarm-status", "notification-status", "db-probe", "auth-status", "seed-due-reminder", "instance-at",
                "queue-count", "sync-status", "cleanup-seeded", "purge-seeded",
            )
        assertEquals(sent.sorted(), HarnessAction.entries.map { it.wire }.sorted())
        for (word in sent) assertEquals(word, parse(word).action.wire)
    }

    @Test
    fun `an unknown or missing action asks how many are pending`() {
        assertEquals(HarnessAction.PendingCount, parse("no-such-action").action)
        assertEquals(HarnessAction.PendingCount, parseHarnessSpec(emptyList(), 0).action)
    }

    @Test
    fun `a missing id is minted from the clock and a missing delay is ninety seconds`() {
        val spec = parseHarnessSpec(listOf("schedule"), 1234)
        assertEquals("harness-1234", spec.id)
        assertEquals(90_000L, spec.delayMs)
        assertEquals("", spec.nonce)
    }

    @Test
    fun `the delay is floored at one second but the value is not`() {
        val spec = parse("instance-at", "r", "0")
        assertEquals(1_000L, spec.delayMs)
        assertEquals(0L, spec.value)
        assertEquals(1_700_000_000_000L, parse("instance-at", "r", "1700000000000").value)
    }

    @Test
    fun `a delay reads like parseInt, leading digits only, and falls back when there are none`() {
        assertEquals(5_000L, parse("schedule", "x", "5000ms").delayMs)
        assertEquals(90_000L, parse("schedule", "x", "soon").delayMs)
        assertEquals(1_000L, parse("schedule", "x", "-5").delayMs)
        assertEquals(7_000L, parse("schedule", "x", "+7000").delayMs)
    }

    @Test
    fun `the first whole minute at or after a moment rounds up and never down`() {
        assertEquals(120_000L, nextWholeMinute(60_001))
        assertEquals(60_000L, nextWholeMinute(60_000))
        assertEquals(60_000L, nextWholeMinute(1))
    }

    @Test
    fun `an id is counted only when the rest of it is exactly what the case expects`() {
        val ids = listOf("task-t-1-500", "task-t-1-other-500", "task-t-1-", "other-task-t-1-5", "task-t-1-5x")
        assertEquals(listOf("task-t-1-500"), armedUnder(ids, "task-t-1-", Regex("\\d+")))
        assertEquals(listOf("task-t-1-other-500"), armedUnder(ids, "task-t-1-", Regex("other-\\d+")))
    }
}
