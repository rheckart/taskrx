package com.taskrx.mobile.harness

import android.content.Intent
import android.net.Uri
import com.taskrx.mobile.notifications.TapHandler
import com.taskrx.mobile.notifications.withNotifs
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

private val NOW = Instant.parse("2026-08-06T12:00:30Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class DeviceHarnessTest {
    @BeforeTest
    fun fresh() {
        DeviceHarness.reset()
        ShadowLog.clear()
    }

    @AfterTest
    fun after() = DeviceHarness.reset()

    private fun view(url: String, action: String = Intent.ACTION_VIEW) = Intent(action, Uri.parse(url))

    private fun lines() = ShadowLog.getLogsForTag(DeviceHarness.TAG).map { it.msg }

    private fun send(url: String, fx: com.taskrx.mobile.notifications.NotifFixture): Boolean {
        val handled = DeviceHarness.handle(fx.context, fx.fx.container, view(url))
        runBlocking { DeviceHarness.lastJob?.join() }
        return handled
    }

    @Test
    fun `a harness link logs the action and then its reply on the lines the script greps`() =
        withNotifs(NOW) {
            assertTrue(send("taskrx:///harness/pending-count/x/0/n1", this))

            assertEquals(listOf("[harness] action=pending-count key=pending-count/x/0/n1", "[harness] result: pending_count=0"), lines())
        }

    @Test
    fun `the host form of the link reaches the same action`() =
        withNotifs(NOW) {
            assertTrue(send("taskrx://harness/pending-count/x/0/n1", this))

            assertEquals("[harness] result: pending_count=0", lines().last())
        }

    @Test
    fun `a link with an unknown action asks how many are pending`() =
        withNotifs(NOW) {
            send("taskrx:///harness/bogus/x/0/n1", this)

            assertEquals("[harness] result: pending_count=0", lines().last())
        }

    @Test
    fun `the same link twice runs once, and a new nonce runs again`() =
        withNotifs(NOW) {
            send("taskrx:///harness/pending-count/x/0/n1", this)
            send("taskrx:///harness/pending-count/x/0/n1", this)
            assertEquals(1, lines().count { it.startsWith("[harness] result:") })

            send("taskrx:///harness/pending-count/x/0/n2", this)
            assertEquals(2, lines().count { it.startsWith("[harness] result:") })
        }

    @Test
    fun `an action that throws is reported as an error rather than swallowed`() =
        withNotifs(NOW) {
            fx.database.openHelper.writableDatabase.execSQL("DROP TABLE sync_queue")

            send("taskrx:///harness/queue-count/x/0/n1", this)

            assertTrue(lines().last().startsWith("[harness] error: "), lines().toString())
        }

    @Test
    fun `a scheduling link works signed out and arms through the production scheduler`() =
        withNotifs(NOW) {
            send("taskrx:///harness/schedule/kill-test/60000/n1", this)

            assertTrue(lines().last().startsWith("[harness] result: scheduled id=kill-test at=${NOW + 60_000}"), lines().toString())
            assertEquals(NOW + 60_000, alarmManager.nextAlarmClock.triggerTime)
            // And the signed-in actions refuse on the same install.
            send("taskrx:///harness/auth-status/x/0/n2", this)
            assertEquals("[harness] result: signed_in=false user_id=none", lines().last())
        }

    @Test
    fun `the answer link opens the answer screen through the tap handler, legacy key included`() =
        withNotifs(NOW) {
            assertTrue(send("taskrx:///dose/log?medicationId=rem-1&scheduledTime=1700000000000", this))

            val route = fx.container.notifications.taps.route.value
            assertNotNull(route)
            assertTrue("rem-1" in route && "1700000000000" in route, route)
            assertEquals(emptyList(), lines())
        }

    @Test
    fun `a link that is not the harness's is left to the app`() =
        withNotifs(NOW) {
            assertFalse(send("taskrx:///meds", this))
            assertFalse(send("https://example.com/harness/pending-count", this))
            assertFalse(DeviceHarness.handle(context, fx.container, view("taskrx:///harness/pending-count", Intent.ACTION_MAIN)))
            assertFalse(DeviceHarness.handle(context, fx.container, Intent(Intent.ACTION_VIEW)))
            assertFalse(DeviceHarness.handle(context, fx.container, null))
            assertNull(fx.container.notifications.taps.route.value)
            assertEquals(emptyList(), lines())
            assertTrue(TapHandler.ACTION_TAP.isNotEmpty())
        }
}
