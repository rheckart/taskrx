package com.taskrx.mobile.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** A [ConvexGateway] the test scripts: every call is recorded, every answer is a lambda. */
class FakeGateway : ConvexGateway {
    data class Call(val kind: String, val function: String, val args: Map<String, Any?>)

    val calls = mutableListOf<Call>()

    /** Default: a create returns `cid-<localId>`, anything else echoes the id it was given. */
    var onMutation: suspend (String, Map<String, Any?>) -> JsonElement = { _, args ->
        JsonPrimitive("cid-" + (args["localId"] ?: args["id"]))
    }
    var onQuery: suspend (String, Map<String, Any?>) -> JsonElement = { _, _ -> JsonNull }
    var onAction: suspend (String, Map<String, Any?>) -> JsonElement = { _, _ -> JsonNull }
    val feeds = mutableMapOf<String, Flow<JsonElement>>()

    val mutations get() = calls.filter { it.kind == "mutation" }

    override suspend fun query(function: String, args: Map<String, Any?>): JsonElement {
        calls += Call("query", function, args)
        return onQuery(function, args)
    }

    override suspend fun mutation(function: String, args: Map<String, Any?>): JsonElement {
        calls += Call("mutation", function, args)
        return onMutation(function, args)
    }

    override suspend fun action(function: String, args: Map<String, Any?>): JsonElement {
        calls += Call("action", function, args)
        return onAction(function, args)
    }

    override fun subscribe(function: String, args: Map<String, Any?>): Flow<JsonElement> = feeds[function] ?: emptyFlow()
}

class FakeConnectivity(initial: Boolean? = true) : Connectivity {
    override val connected: MutableStateFlow<Boolean?> = MutableStateFlow(initial)
    var failCheck: Exception? = null

    override fun isConnected(): Boolean {
        failCheck?.let { throw it }
        return connected.value == true
    }
}

/** A map has no Int or Long anywhere inside it: the client would send those as Int64, which `v.number()` refuses. */
fun assertNoIntegers(value: Any?, path: String = "args") {
    when (value) {
        is Int, is Long -> throw AssertionError("$path is a ${value::class.simpleName}, which the client sends as Int64")
        is Map<*, *> -> value.forEach { (k, v) -> assertNoIntegers(v, "$path.$k") }
        is List<*> -> value.forEachIndexed { i, v -> assertNoIntegers(v, "$path[$i]") }
        else -> Unit
    }
}

