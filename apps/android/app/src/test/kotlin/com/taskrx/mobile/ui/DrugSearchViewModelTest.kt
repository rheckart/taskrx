package com.taskrx.mobile.ui

import com.taskrx.mobile.ui.search.DrugMatch
import com.taskrx.mobile.ui.search.DrugSearchService
import com.taskrx.mobile.ui.search.DrugSearchState
import com.taskrx.mobile.ui.search.DrugSearchUnavailable
import com.taskrx.mobile.ui.search.DrugSearchViewModel
import com.taskrx.mobile.ui.search.OfflineDrugSearch
import com.taskrx.mobile.ui.search.SearchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class DrugSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Fake(val answer: suspend (String) -> List<DrugMatch>) : DrugSearchService {
        val asked = mutableListOf<String>()

        override suspend fun search(query: String): List<DrugMatch> {
            asked += query
            return answer(query)
        }
    }

    private val metformin = DrugMatch("6809", "metformin")

    @Test
    fun `typing is debounced into one search for what was typed last`() =
        runTest(dispatcher) {
            val service = Fake { listOf(metformin) }
            val vm = DrugSearchViewModel(service)

            vm.setQuery("m")
            advanceTimeBy(100)
            vm.setQuery("met")
            advanceTimeBy(100)
            vm.setQuery(" metformin ")
            assertIs<SearchStatus.Searching>(vm.state.value.status)
            advanceUntilIdle()

            assertEquals(listOf("metformin"), service.asked)
            assertEquals(SearchStatus.Done(listOf(metformin)), vm.state.value.status)
        }

    @Test
    fun `a slow answer to an old query cannot overwrite the newer one`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<List<DrugMatch>>()
            val service = Fake { q -> if (q == "met") slow.await() else listOf(metformin) }
            val vm = DrugSearchViewModel(service, debounceMs = 10)

            vm.setQuery("met")
            advanceTimeBy(11)
            vm.setQuery("metformin")
            advanceUntilIdle()
            slow.complete(listOf(DrugMatch("1", "stale")))
            advanceUntilIdle()

            assertEquals(SearchStatus.Done(listOf(metformin)), vm.state.value.status)
        }

    @Test
    fun `an old answer from a service that ignores cancellation is still not written`() =
        runTest(dispatcher) {
            val oldGate = CompletableDeferred<Unit>()
            val newGate = CompletableDeferred<Unit>()
            val service =
                Fake { q ->
                    if (q == "met") {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldGate.await() }
                        listOf(DrugMatch("1", "stale"))
                    } else {
                        newGate.await()
                        listOf(metformin)
                    }
                }
            val vm = DrugSearchViewModel(service, debounceMs = 10)

            vm.setQuery("met")
            advanceTimeBy(11)
            vm.setQuery("metformin")
            advanceTimeBy(11)
            // The old lookup finishes while the new one is still out.
            oldGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(SearchStatus.Searching, vm.state.value.status)

            newGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(SearchStatus.Done(listOf(metformin)), vm.state.value.status)
        }

    @Test
    fun `clearing the box returns to the prompt and searches nothing`() =
        runTest(dispatcher) {
            val service = Fake { listOf(metformin) }
            val vm = DrugSearchViewModel(service)

            vm.setQuery("met")
            vm.setQuery("   ")
            advanceUntilIdle()

            assertEquals(DrugSearchState(query = "   "), vm.state.value)
            assertEquals(emptyList(), service.asked)
        }

    @Test
    fun `a lookup that cannot be made says it is unavailable, not that it failed`() =
        runTest(dispatcher) {
            val vm = DrugSearchViewModel(OfflineDrugSearch)

            vm.setQuery("met")
            advanceUntilIdle()

            assertEquals(SearchStatus.Unavailable, vm.state.value.status)
        }

    @Test
    fun `any other error is a failed search with its message`() =
        runTest(dispatcher) {
            val vm = DrugSearchViewModel(Fake { error("boom") })
            vm.setQuery("met")
            advanceUntilIdle()
            assertEquals(SearchStatus.Failed("boom"), vm.state.value.status)

            val blank = DrugSearchViewModel(Fake { throw RuntimeException() })
            blank.setQuery("met")
            advanceUntilIdle()
            assertEquals(SearchStatus.Failed("Search failed"), blank.state.value.status)
        }

    @Test
    fun `a seeded query is searched on arrival`() =
        runTest(dispatcher) {
            val service = Fake { listOf(metformin) }
            val vm = DrugSearchViewModel(service, initialQuery = "metformin")

            advanceUntilIdle()

            assertEquals(listOf("metformin"), service.asked)
            assertEquals("metformin", vm.state.value.query)
        }

    @Test
    fun `the offline service always reports itself unavailable`() =
        runTest(dispatcher) {
            assertFailsWith<DrugSearchUnavailable> { OfflineDrugSearch.search("x") }
        }
}
