package com.taskrx.mobile.sync

import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.mobile.ui.search.DrugMatch
import com.taskrx.mobile.ui.search.DrugSearchService
import com.taskrx.mobile.ui.search.DrugSearchUnavailable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RxNorm lookup through the server's `rxnorm.search` action (which authenticates, and returns
 * at most ten `{rxcui, name}` pairs, or an empty list when RxNav does not answer).
 *
 * [available] says whether a call can be made at all - signed in with a live connection. When
 * it cannot, the screen is told so ([DrugSearchUnavailable]) instead of showing a failed
 * search the person could do nothing about.
 */
class ConvexDrugSearch(
    private val gateway: ConvexGateway,
    private val available: () -> Boolean,
) : DrugSearchService {
    override suspend fun search(query: String): List<DrugMatch> {
        if (!available()) throw DrugSearchUnavailable()
        val result = gateway.action(ConvexFunctions.RXNORM_SEARCH, mapOf("query" to query))
        return (result as? JsonArray).orEmpty().mapNotNull { element ->
            val match = element as? JsonObject ?: return@mapNotNull null
            val rxcui = (match["rxcui"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val name = (match["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (rxcui == null || name == null) null else DrugMatch(rxcui, name)
        }
    }
}
