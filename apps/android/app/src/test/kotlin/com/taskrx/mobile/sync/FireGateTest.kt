package com.taskrx.mobile.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val AT = 1_780_000_100_000L

/**
 * The bound at fire time is per moment (ADR 0016). Android delivers a process's alarms one at a
 * time, each holding the next until its receiver is done; these run the doses of one instant in
 * that order, through one gate.
 */
@RunWith(RobolectricTestRunner::class)
class FireGateTest {
    private val wall: () -> Long = { System.nanoTime() / 1_000_000 }

    private fun timed(block: suspend () -> FireCheck): Pair<FireCheck, Long> {
        val started = wall()
        val outcome = runBlocking { block() }
        return outcome to wall() - started
    }

    /** A dose whose check is only the moment's read: Open once the rows come. */
    private suspend fun FireGate.dose(moment: Long = AT, timeoutMs: Long, read: suspend () -> List<ServerInstance>) =
        decide(moment, timeoutMs) { shared ->
            shared(read)
            FireCheck.Open
        }

    @Test
    fun `the doses of one instant share one read`() {
        val gate = FireGate(wall)
        val reads = AtomicInteger()
        val read: suspend () -> List<ServerInstance> = {
            reads.incrementAndGet()
            emptyList()
        }
        repeat(3) { assertEquals(FireCheck.Open, runBlocking { gate.dose(timeoutMs = 1_000, read = read) }) }
        assertEquals(1, reads.get())
    }

    @Test
    fun `with no network the second dose of an instant waits out what is left, not a bound of its own`() {
        val gate = FireGate(wall)
        val (first, firstTook) = timed { gate.dose(timeoutMs = 1_000) { awaitCancellation() } }
        val (second, secondTook) = timed { gate.dose(timeoutMs = 1_000) { awaitCancellation() } }
        assertEquals(FireCheck.TimedOut, first)
        assertTrue(firstTook >= 1_000, "the first waits the whole bound: $firstTook ms")
        assertEquals(FireCheck.TimedOut, second)
        assertTrue(secondTook < FireGate.LATE_FLOOR_MS + 300, "the second only the floor: $secondTook ms")
    }

    @Test
    fun `a read that lands after the first dose gave up still answers the next`() {
        // 08:30 on the phone: the first dose timed out on a slow wake, the read it started came
        // back moments later, and the second dose should have had it at once.
        val gate = FireGate(wall)
        val reads = AtomicInteger()
        val slow: suspend () -> List<ServerInstance> = {
            reads.incrementAndGet()
            delay(300)
            emptyList()
        }
        assertEquals(FireCheck.TimedOut, runBlocking { gate.dose(timeoutMs = 200, read = slow) })
        assertEquals(FireCheck.Open, runBlocking { gate.dose(timeoutMs = 200, read = slow) })
        assertEquals(1, reads.get())
    }

    @Test
    fun `another instant has a read and a bound of its own`() {
        val gate = FireGate(wall)
        val reads = AtomicInteger()
        assertEquals(FireCheck.TimedOut, runBlocking { gate.dose(timeoutMs = 200) { awaitCancellation() } })
        val (other, took) =
            timed {
                gate.dose(moment = AT + 60_000, timeoutMs = 1_000) {
                    reads.incrementAndGet()
                    delay(400)
                    emptyList()
                }
            }
        assertEquals(FireCheck.Open, other, "given its own bound, not the floor left over from the other instant")
        assertTrue(took >= 400)
        assertEquals(1, reads.get())
    }

    @Test
    fun `the same instant asked again later reads afresh`() {
        // A snoozed dose fires again for the same instant, minutes on.
        var now = 0L
        val gate = FireGate { now }
        val reads = AtomicInteger()
        val read: suspend () -> List<ServerInstance> = {
            reads.incrementAndGet()
            emptyList()
        }
        runBlocking { gate.dose(timeoutMs = 1_000, read = read) }
        now += FireGate.SHARE_MS
        assertEquals(FireCheck.Open, runBlocking { gate.dose(timeoutMs = 1_000, read = read) })
        assertEquals(2, reads.get())
    }

    @Test
    fun `a shared read that throws fails each dose that waits on it, and each posts`() {
        val gate = FireGate(wall)
        val broken: suspend () -> List<ServerInstance> = { error("no network") }
        assertEquals(FireCheck.Failed, runBlocking { gate.dose(timeoutMs = 1_000, read = broken) })
        assertEquals(FireCheck.Failed, runBlocking { gate.dose(timeoutMs = 1_000, read = broken) })
    }
}
