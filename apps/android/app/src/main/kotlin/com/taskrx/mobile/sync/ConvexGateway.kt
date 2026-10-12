package com.taskrx.mobile.sync

import dev.convex.android.ConvexClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

/** What `getCurrentUserId` in packages/convex/convex/auth.ts throws with no valid token. */
internal const val NOT_AUTHENTICATED = "Not authenticated"

internal fun Throwable.isAuthRefusal(): Boolean = message?.contains(NOT_AUTHENTICATED) == true

/**
 * Asks for a fresh login when the server refuses the caller, then passes the refusal on.
 *
 * The client library holds one token and does not recover by itself once the server stops
 * accepting it: measured on a Pixel, after airplane mode every mutation was refused with
 * "Not authenticated" for minutes while `authState` still read Authenticated, and only an app
 * restart (a fresh login) let the queue drain. Push and pull both pass through here, so a
 * user with nothing to upload still gets the server's changes back.
 *
 * [onRefused] is asked at most once per [minIntervalMs]: every queued entry and both
 * subscriptions can be refused in the same second, and one login answers them all.
 */
class ReauthenticatingGateway(
    private val inner: ConvexGateway,
    private val onRefused: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = REAUTH_INTERVAL_MS,
) : ConvexGateway {
    private var lastAsked: Long? = null

    override suspend fun query(function: String, args: Map<String, Any?>): JsonElement = refusalsAsked { inner.query(function, args) }

    override suspend fun mutation(function: String, args: Map<String, Any?>): JsonElement = refusalsAsked { inner.mutation(function, args) }

    override suspend fun action(function: String, args: Map<String, Any?>): JsonElement = refusalsAsked { inner.action(function, args) }

    override fun subscribe(function: String, args: Map<String, Any?>): Flow<JsonElement> =
        inner.subscribe(function, args).catch { e ->
            if (e.isAuthRefusal()) refused()
            throw e
        }

    private inline fun refusalsAsked(call: () -> JsonElement): JsonElement =
        try {
            call()
        } catch (e: Exception) {
            if (e.isAuthRefusal()) refused()
            throw e
        }

    @Synchronized
    private fun refused() {
        val at = now()
        val last = lastAsked
        if (last != null && at - last < minIntervalMs) return
        lastAsked = at
        onRefused()
    }

    private companion object {
        const val REAUTH_INTERVAL_MS = 5_000L
    }
}

class RealConvexGateway(private val client: ConvexClient) : ConvexGateway {
    override suspend fun query(function: String, args: Map<String, Any?>): JsonElement =
        client.subscribe<JsonElement>(function, args).first().getOrThrow()

    override suspend fun mutation(function: String, args: Map<String, Any?>): JsonElement = client.mutation<JsonElement>(function, args)

    override suspend fun action(function: String, args: Map<String, Any?>): JsonElement = client.action<JsonElement>(function, args)

    override fun subscribe(function: String, args: Map<String, Any?>): Flow<JsonElement> =
        client.subscribe<JsonElement>(function, args).map { it.getOrThrow() }
}
