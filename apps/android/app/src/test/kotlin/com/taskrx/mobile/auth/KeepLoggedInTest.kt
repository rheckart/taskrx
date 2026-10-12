package com.taskrx.mobile.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KeepLoggedInTest {
    /**
     * A client that refuses the first [failures] logins and accepts the next, the way Clerk
     * fails for a few seconds right after a reconnect. [stranded] is what ConvexSession feeds
     * the loop: true logged out while signed in, null mid-login, false logged in.
     */
    private class Client(var failures: Int) {
        val stranded = MutableStateFlow<Boolean?>(true)
        val pauses = mutableListOf<Long>()
        var logins = 0

        fun login() {
            logins++
            stranded.value = null
            stranded.value = if (failures-- > 0) true else false
        }
    }

    private fun run(client: Client, block: suspend () -> Unit) =
        runBlocking {
            val job = Job()
            CoroutineScope(job + Dispatchers.Default).launch {
                keepLoggedIn(client.stranded, client::login) { client.pauses += it; delay(1) }
            }
            try {
                block()
            } finally {
                job.cancel()
            }
        }

    private suspend fun until(what: String, check: () -> Boolean) {
        repeat(500) {
            if (check()) return
            delay(10)
        }
        throw AssertionError("never saw: $what")
    }

    @Test
    fun `a login that fails right after a reconnect is tried again until one succeeds`() {
        val client = Client(failures = 3)
        run(client) { until("logged in") { client.stranded.value == false } }
    }

    @Test
    fun `the pauses grow while the logins keep failing, and stop once one succeeds`() {
        val client = Client(failures = 3)
        run(client) {
            until("logged in") { client.stranded.value == false }
            delay(100)
        }
        assertEquals(4, client.logins, "three failures, then the one that succeeded, then no more")
        // The fifth is the wait already under way when the login landed; it is cancelled.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), client.pauses.take(4))
    }

    @Test
    fun `logged in, or signed out, it asks for nothing`() {
        val client = Client(failures = 0)
        client.stranded.value = false
        run(client) { delay(100) }
        assertEquals(0, client.logins)
    }
}
