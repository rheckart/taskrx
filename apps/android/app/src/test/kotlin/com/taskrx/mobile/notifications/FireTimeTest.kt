package com.taskrx.mobile.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import com.taskrx.mobile.sync.FireCheck
import com.taskrx.mobile.sync.FireGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

private val DOSE = NotificationSpec("med-1000", Tier.Medication, 1000, "Metformin", "Time to take (500mg)", "med", 1000)

/** The check at fire time (ADR 0016): only a positive answer from the server keeps a dose quiet. */
@RunWith(RobolectricTestRunner::class)
class FireTimeTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun fire(spec: NotificationSpec = DOSE, timeoutMs: Long = 300, check: suspend (NotificationSpec) -> FireCheck) =
        runBlocking { AlarmReceiver().handle(context, Intent().putExtra(AlarmReceiver.EXTRA_SPEC, SpecCodec.encode(spec)), timeoutMs, check = check).join() }

    private fun posted() = shadowOf(manager).getNotification(DOSE.id, 0)

    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

    /** The backup alarms armed now: what each would deliver, and when. */
    private fun backups(): List<Pair<Intent, Long>> =
        alarms.scheduledAlarms
            .map { shadowOf(it.operation!!).savedIntent to it.triggerAtTime }
            .filter { (intent, _) -> intent.action == AlarmReceiver.ACTION_BACKUP }

    @Test
    fun `a dose answered on another device stays quiet`() {
        fire { FireCheck.Answered }
        assertNull(posted())
    }

    @Test
    fun `every outcome short of an answer posts the dose`() {
        for (outcome in FireCheck.entries - FireCheck.Answered) {
            manager.cancelAll()
            fire { outcome }
            assertNotNull(posted(), "$outcome")
        }
    }

    @Test
    fun `a check that throws, an Error included, posts the dose`() {
        fire { throw IllegalStateException("no network") }
        assertNotNull(posted())
        manager.cancelAll()
        fire { throw UnsatisfiedLinkError("the Convex library would not load") }
        assertNotNull(posted())
    }

    @Test
    fun `a server that never answers posts the dose when the wait runs out`() {
        val started = System.nanoTime()
        fire(timeoutMs = 200) { awaitCancellation() }
        assertNotNull(posted())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun `a check that blocks its thread does not hold the dose past the wait`() {
        val started = System.nanoTime()
        fire(timeoutMs = 200) {
            Thread.sleep(5_000)
            FireCheck.Answered
        }
        assertNotNull(posted(), "an answer that comes too late is ignored")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "posted within the wait, not after the blocked work")
    }

    @Test
    fun `a take-another-dose prompt posts without asking`() {
        var asked = false
        fire(spec = DOSE.copy(asNeeded = true)) {
            asked = true
            FireCheck.Answered
        }
        assertFalse(asked)
        assertNotNull(posted())
    }

    @Test
    fun `this build's own check posts, having no server`() {
        runBlocking { AlarmReceiver().handle(context, Intent().putExtra(AlarmReceiver.EXTRA_SPEC, SpecCodec.encode(DOSE))).join() }
        assertEquals("Metformin", posted().extras.getString("android.title"))
    }

    @Test
    fun `the backup outlasts the wait`() {
        assertTrue(DoseBackup.DELAY_MS >= FireGate.TIMEOUT_MS + 10_000, "the check must have finished, or given up, well before the backup fires")
    }

    @Test
    fun `a backup holding the dose is armed before the check runs, and goes once the dose posts`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val before = System.currentTimeMillis()
        var seen: List<Pair<Intent, Long>> = emptyList()
        fire {
            seen = backups()
            FireCheck.Open
        }
        val (intent, at) = seen.single()
        assertEquals(DOSE, SpecCodec.decode(intent.getStringExtra(AlarmReceiver.EXTRA_SPEC)))
        assertTrue(at >= before + DoseBackup.DELAY_MS && at <= System.currentTimeMillis() + DoseBackup.DELAY_MS, "fires DELAY_MS after the alarm: $at")
        assertNotNull(posted())
        assertEquals(emptyList(), backups())
    }

    @Test
    fun `an answer from the server takes the backup away too`() {
        fire { FireCheck.Answered }
        assertNull(posted())
        assertEquals(emptyList(), backups())
    }

    @Test
    fun `a check that fails or runs out of time posts and takes the backup away`() {
        fire { throw IllegalStateException("no network") }
        assertEquals(emptyList(), backups())
        fire(timeoutMs = 100) { awaitCancellation() }
        assertEquals(emptyList(), backups())
    }

    @Test
    fun `without exact alarms the backup still arms, inexact`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        var armed = 0
        fire {
            armed = backups().size
            FireCheck.Open
        }
        assertEquals(1, armed)
    }

    @Test
    fun `the backup firing posts the dose without asking the server`() {
        // What is left when the process dies mid-check: the backup, and nothing to cancel it.
        lateinit var backup: Intent
        fire {
            backup = backups().single().first
            FireCheck.Answered
        }
        assertNull(posted())

        var asked = false
        runBlocking {
            AlarmReceiver().handle(context, backup, check = {
                asked = true
                FireCheck.Answered
            }).join()
        }
        assertFalse(asked)
        assertNotNull(posted())
    }

    @Test
    fun `a take-another-dose prompt arms no backup`() {
        fire(spec = DOSE.copy(asNeeded = true)) { FireCheck.Open }
        assertEquals(emptyList(), backups())
    }
}
