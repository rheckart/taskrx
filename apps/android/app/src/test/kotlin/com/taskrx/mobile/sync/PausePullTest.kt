package com.taskrx.mobile.sync

import com.taskrx.core.domain.PauseWindow
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

private fun doc(localId: String, extra: String) =
    """{"_id":"c-$localId","_creationTime":1.0,"localId":"$localId","userId":"$USER","kind":"medication",
    "name":"Warfarin","schedule":{"type":"daily","times":["09:00"]},"priority":"normal","tags":[],"caregiverIds":[],
    "active":true,"createdAt":1780000000000.0,"updatedAt":1780000000000.0 $extra}"""

@RunWith(RobolectricTestRunner::class)
class PausePullTest {
    private suspend fun com.taskrx.mobile.Fixture.pull(vararg docs: String) =
        PullApplier(container.reminders, container.instances)
            .applyJson(Json.parseToJsonElement(docs.joinToString(prefix = "[", postfix = "]")), Json.parseToJsonElement("[]"))

    @Test
    fun `a pulled Reminder carries the windows another device set, floats and all`() =
        withFixture(T0) {
            pull(doc("r1", ""","pauses":[{"from":1780000000000.0,"until":1.7800009E12},{"from":1790000000000.0}]"""))

            assertEquals(
                listOf(PauseWindow(1_780_000_000_000, 1_780_000_900_000), PauseWindow(1_790_000_000_000)),
                container.reminders.get("r1")!!.pauses,
            )
        }

    @Test
    fun `a document from before pausing, or with a malformed pause, is simply not paused`() =
        withFixture(T0) {
            pull(doc("old", ""), doc("bad", ""","pauses":[{"from":"x"}]"""))

            assertEquals(emptyList(), container.reminders.get("old")!!.pauses)
            assertEquals(emptyList(), container.reminders.get("bad")!!.pauses)
        }
}
