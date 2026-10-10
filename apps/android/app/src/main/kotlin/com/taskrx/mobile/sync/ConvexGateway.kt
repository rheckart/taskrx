package com.taskrx.mobile.sync

import dev.convex.android.ConvexClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/**
 * The thin seam between the sync code and the Convex client library. Everything the push
 * worker, the pull and drug search need from the server goes through here, so each is tested
 * against a fake and the library is touched in exactly one place ([RealConvexGateway]).
 *
 * Values cross as [JsonElement]s, the library's own JSON shape: documents carry `_id` and
 * `_creationTime`, and every number is a float64 (see [com.taskrx.core.sync.toWire] for the
 * outbound half of that). [function] is the `module:name` form. All calls throw on error.
 */
interface ConvexGateway {
    suspend fun query(function: String, args: Map<String, Any?> = emptyMap()): JsonElement

    suspend fun mutation(function: String, args: Map<String, Any?>): JsonElement

    suspend fun action(function: String, args: Map<String, Any?>): JsonElement

    /** A live query: emits the full result again on every change. A server error fails the flow. */
    fun subscribe(function: String, args: Map<String, Any?> = emptyMap()): Flow<JsonElement>
}

class RealConvexGateway(private val client: ConvexClient) : ConvexGateway {
    override suspend fun query(function: String, args: Map<String, Any?>): JsonElement =
        client.subscribe<JsonElement>(function, args).first().getOrThrow()

    override suspend fun mutation(function: String, args: Map<String, Any?>): JsonElement = client.mutation<JsonElement>(function, args)

    override suspend fun action(function: String, args: Map<String, Any?>): JsonElement = client.action<JsonElement>(function, args)

    override fun subscribe(function: String, args: Map<String, Any?>): Flow<JsonElement> =
        client.subscribe<JsonElement>(function, args).map { it.getOrThrow() }
}
