package com.taskrx.mobile.notices

import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateWatchedSourceInput
import com.taskrx.mobile.withFixture
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW = 1_780_000_000_000L
private val ZONE: ZoneId = ZoneOffset.UTC

private fun fixtureIntake(fx: Fixture) =
    NoticeIntake(fx.container.notices, fx.container.watchSources, zone = { ZONE })

private fun message(
    sourceKind: SourceKind = SourceKind.App,
    sourceValue: String = "com.example.waste",
    title: String = "Bins moved",
    body: String = "Collection moves to Wednesday 18 November.",
    externalId: String? = "notification-key-1",
) = IntakeMessage(sourceKind, sourceValue, title, body, NOW, externalId)

@RunWith(RobolectricTestRunner::class)
class NoticeIntakeTest {
    private suspend fun Fixture.watch(kind: SourceKind, value: String) =
        container.watchSources.create(CreateWatchedSourceInput(USER, kind, value, value))

    @Test
    fun `an unwatched source is dropped and nothing is written`() =
        withFixture(NOW) {
            val result = fixtureIntake(this).admit(USER, message())

            assertEquals(IntakeResult.NotWatched, result)
            assertTrue(container.notices.list(USER).isEmpty())
        }

    @Test
    fun `a watched source admits the message and keeps the date's sentence`() =
        withFixture(NOW) {
            watch(SourceKind.App, "com.example.waste")

            val result = assertIs<IntakeResult.Stored>(fixtureIntake(this).admit(USER, message()))

            assertEquals("Bins moved", result.notice.title)
            assertEquals("Collection moves to Wednesday 18 November.", result.notice.sentence)
            assertEquals(SourceKind.App, result.notice.sourceKind)
            assertEquals("com.example.waste", result.notice.sourceValue)
            assertEquals("notification-key-1", result.notice.externalId)
            assertEquals(1, container.notices.list(USER).size)
        }

    @Test
    fun `the same external id is not stored twice`() =
        withFixture(NOW) {
            watch(SourceKind.App, "com.example.waste")
            val intake = fixtureIntake(this)
            assertIs<IntakeResult.Stored>(intake.admit(USER, message()))

            assertEquals(IntakeResult.Duplicate, intake.admit(USER, message()))
            assertEquals(1, container.notices.list(USER).size)
        }

    @Test
    fun `a different external id is a new notice`() =
        withFixture(NOW) {
            watch(SourceKind.App, "com.example.waste")
            val intake = fixtureIntake(this)
            assertIs<IntakeResult.Stored>(intake.admit(USER, message(externalId = "key-1")))

            assertIs<IntakeResult.Stored>(intake.admit(USER, message(externalId = "key-2")))
            assertEquals(2, container.notices.list(USER).size)
        }

    @Test
    fun `a message with no id is admitted each time and no external id is stored`() =
        withFixture(NOW) {
            watch(SourceKind.App, "com.example.waste")
            val intake = fixtureIntake(this)

            val first = assertIs<IntakeResult.Stored>(intake.admit(USER, message(externalId = null)))
            assertNull(first.notice.externalId)
            assertIs<IntakeResult.Stored>(intake.admit(USER, message(externalId = null)))
            assertEquals(2, container.notices.list(USER).size)
        }

    @Test
    fun `an email is matched case-insensitively against a watched sender`() =
        withFixture(NOW) {
            watch(SourceKind.Email, "waste@city.example")

            val result =
                assertIs<IntakeResult.Stored>(
                    fixtureIntake(this).admit(USER, message(sourceKind = SourceKind.Email, sourceValue = "Waste@City.Example")),
                )

            assertEquals("waste@city.example", result.notice.sourceValue)
        }

    @Test
    fun `an app package of the same spelling as an email is not a match`() =
        withFixture(NOW) {
            watch(SourceKind.Email, "waste@city.example")

            assertEquals(
                IntakeResult.NotWatched,
                fixtureIntake(this).admit(USER, message(sourceKind = SourceKind.App, sourceValue = "waste@city.example")),
            )
        }

    @Test
    fun `a message with neither subject nor text is empty`() =
        withFixture(NOW) {
            watch(SourceKind.App, "com.example.waste")

            assertEquals(IntakeResult.Empty, fixtureIntake(this).admit(USER, message(title = "  ", body = " ")))
            assertTrue(container.notices.list(USER).isEmpty())
        }

    @Test
    fun `a watch-only-road message with a title and no body keeps the title and no date`() =
        withFixture(NOW) {
            watch(SourceKind.App, "com.example.waste")

            val result = assertIs<IntakeResult.Stored>(fixtureIntake(this).admit(USER, message(body = "")))

            assertEquals("Bins moved", result.notice.title)
            assertNull(result.notice.sentence)
        }

    @Test
    fun `a source with no usable value is not watched`() =
        withFixture(NOW) {
            watch(SourceKind.Email, "waste@city.example")

            assertEquals(IntakeResult.NotWatched, fixtureIntake(this).admit(USER, message(sourceKind = SourceKind.Email, sourceValue = "  ")))
        }
}
