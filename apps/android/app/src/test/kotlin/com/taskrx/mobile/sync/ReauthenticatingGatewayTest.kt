package com.taskrx.mobile.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class ReauthenticatingGatewayTest {
    private val inner = FakeGateway()
    private var now = 0L
    private var logins = 0
    private val gateway = ReauthenticatingGateway(inner, onRefused = { logins++ }, now = { now }, minIntervalMs = 5_000)

    private val refused: suspend (String, Map<String, Any?>) -> Nothing = { _, _ -> error("[CONVEX M(reminders:create)] Uncaught Error: Not authenticated") }

    @Test
    fun `a refused mutation asks for a login and still fails, so the worker keeps the entry`() =
        runBlocking {
            inner.onMutation = refused

            assertFailsWith<IllegalStateException> { gateway.mutation("reminders:create", emptyMap()) }
            assertEquals(1, logins)
        }

    @Test
    fun `a refused query, action or subscription asks too`() =
        runBlocking {
            inner.onQuery = refused
            inner.onAction = refused
            inner.feeds["reminders:list"] = flow { error("Not authenticated") }

            assertFailsWith<IllegalStateException> { gateway.query("reminders:getByLocalId") }
            now += 5_000
            assertFailsWith<IllegalStateException> { gateway.action("rxnorm:search", emptyMap()) }
            now += 5_000
            assertFailsWith<IllegalStateException> { gateway.subscribe("reminders:list").toList() }

            assertEquals(3, logins)
        }

    @Test
    fun `a burst of refusals asks once, and a later one asks again`() =
        runBlocking {
            inner.onMutation = refused

            repeat(4) { assertFailsWith<IllegalStateException> { gateway.mutation("reminders:create", emptyMap()) } }
            assertEquals(1, logins)

            now += 5_000
            assertFailsWith<IllegalStateException> { gateway.mutation("reminders:create", emptyMap()) }
            assertEquals(2, logins)
        }

    @Test
    fun `any other failure, and a success, leave the login alone`() =
        runBlocking {
            inner.onMutation = { _, _ -> error("Cannot create reminder for another user") }
            assertFailsWith<IllegalStateException> { gateway.mutation("reminders:create", emptyMap()) }
            inner.onMutation = { _, _ -> JsonPrimitive("srv") }
            assertEquals(JsonPrimitive("srv"), gateway.mutation("reminders:create", emptyMap()))

            assertEquals(0, logins)
        }
}
