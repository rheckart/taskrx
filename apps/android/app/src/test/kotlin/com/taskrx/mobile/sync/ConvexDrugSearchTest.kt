package com.taskrx.mobile.sync

import com.taskrx.mobile.ui.search.DrugMatch
import com.taskrx.mobile.ui.search.DrugSearchUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

class ConvexDrugSearchTest {
    private val gateway = FakeGateway()
    private var available = true
    private val search = ConvexDrugSearch(gateway) { available }

    @Test
    fun `asks the rxnorm search action and returns its matches`() =
        runBlocking {
            gateway.onAction = { _, _ -> Json.parseToJsonElement("""[{"rxcui":"6809","name":"metformin"},{"rxcui":"861004","name":"metformin 500 MG"}]""") }

            val matches = search.search("metformin")

            assertEquals(listOf(DrugMatch("6809", "metformin"), DrugMatch("861004", "metformin 500 MG")), matches)
            assertEquals(FakeGateway.Call("action", "rxnorm:search", mapOf("query" to "metformin")), gateway.calls.single())
        }

    @Test
    fun `an empty answer is no matches, not a failure`() =
        runBlocking {
            gateway.onAction = { _, _ -> Json.parseToJsonElement("[]") }
            assertTrue(search.search("zzz").isEmpty())
        }

    @Test
    fun `drops entries that are not a name and a code, and an answer that is not a list`() =
        runBlocking {
            gateway.onAction = { _, _ -> Json.parseToJsonElement("""[{"rxcui":"1"},{"name":"x"},{"rxcui":2,"name":"y"},"junk",{"rxcui":"3","name":"ok"}]""") }
            assertEquals(listOf(DrugMatch("3", "ok")), search.search("q"))

            gateway.onAction = { _, _ -> JsonNull }
            assertTrue(search.search("q").isEmpty())
        }

    @Test
    fun `signed out or offline it does not call the server and says so`() =
        runBlocking {
            available = false
            assertFailsWith<DrugSearchUnavailable> { search.search("metformin") }
            assertTrue(gateway.calls.isEmpty())
        }

    @Test
    fun `a failing call propagates as a failed search`() =
        runBlocking {
            gateway.onAction = { _, _ -> error("Not authenticated") }
            assertEquals("Not authenticated", assertFailsWith<IllegalStateException> { search.search("q") }.message)
        }
}
