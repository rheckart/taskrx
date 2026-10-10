package com.taskrx.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * What the push worker sends for a sync-queue entry, ported from `processReminderEntry` and
 * `processReminderInstanceEntry` in `apps/mobile/src/sync/worker.ts`. The Convex functions
 * and their argument validators (packages/convex/convex) are the contract.
 *
 * Pure: a queue payload goes in, the call to make comes out. The worker does the I/O.
 */

/** One Convex function call: `module:name` and its arguments. */
data class PushCall(val function: String, val args: Map<String, Any?>)

/** The entry cannot be turned into a call. Retried like any failure, and dead-lettered at the limit. */
class PushRejected(message: String) : Exception(message)

object ConvexFunctions {
    const val REMINDERS_CREATE = "reminders:create"
    const val REMINDERS_UPDATE = "reminders:update"
    const val REMINDERS_SOFT_DELETE = "reminders:softDelete"
    const val REMINDERS_GET_BY_LOCAL_ID = "reminders:getByLocalId"
    const val REMINDERS_LIST = "reminders:list"
    const val INSTANCES_CREATE = "reminderInstances:create"
    const val INSTANCES_MARK_COMPLETED = "reminderInstances:markCompleted"
    const val INSTANCES_SNOOZE = "reminderInstances:snooze"
    const val INSTANCES_MARK_SKIPPED = "reminderInstances:markSkipped"
    const val INSTANCES_MARK_MISSED = "reminderInstances:markMissed"
    const val INSTANCES_GET_BY_LOCAL_ID = "reminderInstances:getByLocalId"
    const val INSTANCES_LIST = "reminderInstances:list"
    const val RXNORM_SEARCH = "rxnorm:search"
}

/**
 * A JSON value as the plain Kotlin value the Convex client sends.
 *
 * Every number becomes a [Double]. The client encodes a Kotlin Int or Long as an Int64
 * (`{"$integer": ...}`), which a `v.number()` validator rejects - every number in this
 * schema is a float64 - so a count or a timestamp left as a Long would fail on the server.
 */
fun toWire(value: JsonElement): Any? =
    when (value) {
        is JsonNull -> null
        is JsonObject -> value.mapValues { (_, element) -> toWire(element) }
        is JsonArray -> value.map(::toWire)
        is JsonPrimitive ->
            when {
                value.isString -> value.content
                value.content == "true" -> true
                value.content == "false" -> false
                else -> value.content.toDouble()
            }
    }

private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

private fun JsonObject.required(key: String): JsonElement = present(key) ?: throw PushRejected("Payload is missing $key")

private fun JsonObject.requiredWire(key: String): Any? = toWire(required(key))

/** Optional fields are left out when the payload has no value - "leave the server's copy alone". */
private fun MutableMap<String, Any?>.putIfPresent(payload: JsonObject, keys: List<String>) {
    for (key in keys) payload.present(key)?.let { put(key, toWire(it)) }
}

private val OPTIONAL_REMINDER_FIELDS =
    listOf("priority", "tags", "caregiverIds", "rxcui", "dosage", "instructions", "pillsRemaining", "pillsPerDose", "refillReminderDays")

/**
 * `reminders.create`. A repeated create for the same `localId` is an upsert on the server,
 * which is why an edit made while the first create is still queued can send this again.
 *
 * Clearing a field does not round-trip: a null is omitted, which the server reads as
 * "leave alone". That is a limit of the validators (no nullable member), documented in the
 * Expo worker, and not changed here.
 */
fun reminderCreateCall(payload: JsonObject): PushCall =
    PushCall(
        ConvexFunctions.REMINDERS_CREATE,
        buildMap {
            for (key in listOf("localId", "userId", "kind", "name", "schedule", "active", "createdAt", "updatedAt")) {
                put(key, payload.requiredWire(key))
            }
            putIfPresent(payload, OPTIONAL_REMINDER_FIELDS)
        },
    )

fun reminderUpdateCall(payload: JsonObject, convexId: String): PushCall =
    PushCall(
        ConvexFunctions.REMINDERS_UPDATE,
        buildMap {
            put("id", convexId)
            for (key in listOf("name", "schedule", "active", "updatedAt")) put(key, payload.requiredWire(key))
            putIfPresent(payload, OPTIONAL_REMINDER_FIELDS)
        },
    )

fun reminderDeleteCall(convexId: String): PushCall = PushCall(ConvexFunctions.REMINDERS_SOFT_DELETE, mapOf("id" to convexId))

/** `reminderInstances.create`; [parentConvexId] is the parent's server id, which must already exist. */
fun instanceCreateCall(payload: JsonObject, parentConvexId: String): PushCall =
    PushCall(
        ConvexFunctions.INSTANCES_CREATE,
        buildMap {
            for (key in listOf("localId", "userId", "scheduledTime", "status", "snoozeCount", "createdAt", "updatedAt")) {
                put(key, payload.requiredWire(key))
            }
            put("reminderId", parentConvexId)
            putIfPresent(payload, listOf("completedAt", "snoozedUntil", "notes"))
        },
    )

/**
 * An instance update is one of four mutations, chosen by the status the row now has.
 * `snooze` increments the count server-side rather than taking the local one, so two
 * offline devices both land. There is no mutation for `pending`: nothing returns an
 * answered instance to pending.
 */
fun instanceUpdateCall(payload: JsonObject, convexId: String): PushCall =
    when (val status = (payload.required("status") as? JsonPrimitive)?.content) {
        "completed" ->
            PushCall(
                ConvexFunctions.INSTANCES_MARK_COMPLETED,
                buildMap {
                    put("id", convexId)
                    putIfPresent(payload, listOf("completedAt"))
                },
            )
        "snoozed" ->
            PushCall(
                ConvexFunctions.INSTANCES_SNOOZE,
                mapOf(
                    "id" to convexId,
                    "snoozedUntil" to toWire(payload.present("snoozedUntil") ?: throw PushRejected("Snoozed instance is missing snoozedUntil")),
                ),
            )
        "skipped" ->
            PushCall(
                ConvexFunctions.INSTANCES_MARK_SKIPPED,
                buildMap {
                    put("id", convexId)
                    putIfPresent(payload, listOf("notes"))
                },
            )
        "missed" -> PushCall(ConvexFunctions.INSTANCES_MARK_MISSED, mapOf("id" to convexId))
        "pending" -> throw PushRejected("Cannot sync pending reminder instance update")
        else -> throw PushRejected("Unknown instance status $status")
    }
