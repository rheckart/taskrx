package com.taskrx.mobile.sync

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val AT = 1_780_000_100_000L

/**
 * The fire-time check's own read (ADR 0016). On the phone the first attempt after an idle wake can
 * land before Android lifts the background firewall, so a failed attempt is tried again inside the
 * bound; these run on a clock that moves (Robolectric's SystemClock stands still).
 */
@RunWith(RobolectricTestRunner::class)
class MomentReaderTest {
    private val wall: () -> Long = { System.nanoTime() / 1_000_000 }
    private val empty: JsonElement = Json.parseToJsonElement("[]")

    private fun reader(token: suspend (Boolean) -> String = { "jwt" }, query: suspend (Int) -> JsonElement): Pair<MomentReader, () -> Int> {
        var attempts = 0
        val reader =
            MomentReader(
                query = { _, _, _ -> query(++attempts) },
                token = token,
                clock = wall,
                pauseMs = 20,
                retryForMs = 300,
            )
        return reader to { attempts }
    }

    @Test
    fun `an attempt cut off by the firewall is tried again and the next answers`() {
        val (reader, attempts) = reader { n -> if (n == 1) throw IOException("network blocked") else empty }
        assertEquals(emptyList(), runBlocking { reader.read(AT) })
        assertEquals(2, attempts())
    }

    @Test
    fun `a token Clerk cannot hand over is tried again too`() {
        var asked = 0
        val (reader, _) = reader(token = { if (++asked == 1) error("Clerk token request failed") else "jwt" }) { empty }
        assertEquals(emptyList(), runBlocking { reader.read(AT) })
        assertEquals(2, asked)
    }

    @Test
    fun `attempts that all fail give up once the retry window is spent`() {
        val (reader, attempts) = reader { throw IOException("network blocked") }
        val started = wall()
        assertFailsWith<IOException> { runBlocking { reader.read(AT) } }
        assertTrue(wall() - started < 1_000, "gave up near the window")
        assertTrue(attempts() > 3, "kept trying inside it: ${attempts()}")
    }

    @Test
    fun `a refused token is fetched fresh for the next attempt`() {
        val fresh = mutableListOf<Boolean>()
        val (reader, _) =
            reader(token = { fresh += it; "jwt" }) { n ->
                if (n == 1) throw ConvexHttpError("InvalidAuthHeader: Token expired", refusedToken = true) else empty
            }
        runBlocking { reader.read(AT) }
        assertEquals(listOf(false, true), fresh, "Clerk's cached token first, a new one after the refusal")
    }

    @Test
    fun `a reply this build cannot read is not asked again`() {
        val (reader, attempts) = reader { Json.parseToJsonElement("""[{"status":"completed"}]""") }
        assertFailsWith<SerializationException> { runBlocking { reader.read(AT) } }
        assertEquals(1, attempts())
    }

    @Test
    fun `the HTTP reply gives the value, or the error it carries`() {
        assertEquals(empty, ConvexHttp.reply(200, """{"status":"success","value":[]}"""))

        val threw = assertFailsWith<ConvexHttpError> { ConvexHttp.reply(200, """{"status":"error","errorMessage":"Uncaught Error: Not authenticated"}""") }
        assertTrue(threw.isAuthRefusal(), "a function that refused the caller")
        assertFalse(threw.refusedToken)

        val refused = assertFailsWith<ConvexHttpError> { ConvexHttp.reply(401, """{"code":"InvalidAuthHeader","message":"Token expired"}""") }
        assertTrue(refused.refusedToken)
        assertEquals("InvalidAuthHeader: Token expired", refused.message)

        assertFailsWith<IOException>("a proxy's error page is worth another attempt") { ConvexHttp.reply(502, "<html>Bad Gateway</html>") }
    }
}
