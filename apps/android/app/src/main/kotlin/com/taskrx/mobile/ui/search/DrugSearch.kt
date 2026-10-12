package com.taskrx.mobile.ui.search

/** One RxNorm concept: the identifier a medication is stored against, and its name. */
data class DrugMatch(val rxcui: String, val name: String)

/**
 * Looks drugs up by name. A seam, because the lookup is the server's `rxnorm.search`
 * action and there is no server until sign-in and sync land: the screen is built against
 * this, and the Convex-backed implementation replaces [OfflineDrugSearch] in that step with
 * no change here.
 *
 * Throws [DrugSearchUnavailable] when the lookup cannot be made at all (offline, signed
 * out); any other failure is the call going wrong and is shown as a failed search.
 */
interface DrugSearchService {
    suspend fun search(query: String): List<DrugMatch>
}

/** The lookup cannot be made from here, as opposed to having been made and found nothing. */
class DrugSearchUnavailable(message: String = "Drug search needs a connection") : Exception(message)

/** The only implementation until the sync step: there is no server to ask. */
object OfflineDrugSearch : DrugSearchService {
    override suspend fun search(query: String): List<DrugMatch> = throw DrugSearchUnavailable()
}
