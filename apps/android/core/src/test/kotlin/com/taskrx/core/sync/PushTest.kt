package com.taskrx.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

private const val REMINDER =
    """{"localId":"r1","userId":"u1","kind":"medication","name":"Metformin","rxcui":null,"dosage":null,
    "instructions":null,"schedule":{"type":"weekly","daysOfWeek":[1,3],"times":["08:00"]},"priority":null,
    "tags":null,"pillsRemaining":null,"pillsPerDose":null,"refillReminderDays":null,"caregiverIds":null,
    "active":true,"createdAt":1000,"updatedAt":2000}"""

class ToWireTest {
    @Test
    fun `turns every number into a double, so the client never sends an Int64`() {
        val wire = toWire(Json.parseToJsonElement("""{"a":1,"b":[2,{"c":3.5}],"d":"x","e":true,"f":false,"g":null}"""))
        assertEquals(
            mapOf("a" to 1.0, "b" to listOf(2.0, mapOf("c" to 3.5)), "d" to "x", "e" to true, "f" to false, "g" to null),
            wire,
        )
    }

    @Test
    fun `keeps a numeric looking string a string`() {
        assertEquals("12", toWire(Json.parseToJsonElement("\"12\"")))
        assertEquals("true", toWire(Json.parseToJsonElement("\"true\"")))
    }
}

class ReminderPushTest {
    @Test
    fun `create sends the required fields and omits what this device has no value for`() {
        val call = reminderCreateCall(obj(REMINDER))
        assertEquals("reminders:create", call.function)
        assertEquals(
            mapOf(
                "localId" to "r1",
                "userId" to "u1",
                "kind" to "medication",
                "name" to "Metformin",
                "schedule" to mapOf("type" to "weekly", "daysOfWeek" to listOf(1.0, 3.0), "times" to listOf("08:00")),
                "active" to true,
                "createdAt" to 1000.0,
                "updatedAt" to 2000.0,
            ),
            call.args,
        )
    }

    @Test
    fun `create sends every optional field it does have`() {
        val payload =
            obj(
                REMINDER.replace(""""rxcui":null""", """"rxcui":"860975"""")
                    .replace(""""dosage":null""", """"dosage":"500 mg"""")
                    .replace(""""instructions":null""", """"instructions":"with food"""")
                    .replace(""""priority":null""", """"priority":"high"""")
                    .replace(""""tags":null""", """"tags":["a"]""")
                    .replace(""""pillsRemaining":null""", """"pillsRemaining":30""")
                    .replace(""""pillsPerDose":null""", """"pillsPerDose":1.5""")
                    .replace(""""refillReminderDays":null""", """"refillReminderDays":7""")
                    .replace(""""caregiverIds":null""", """"caregiverIds":["c1"]"""),
            )
        val args = reminderCreateCall(payload).args
        assertEquals("860975", args["rxcui"])
        assertEquals("500 mg", args["dosage"])
        assertEquals("with food", args["instructions"])
        assertEquals("high", args["priority"])
        assertEquals(listOf("a"), args["tags"])
        assertEquals(30.0, args["pillsRemaining"])
        assertEquals(1.5, args["pillsPerDose"])
        assertEquals(7.0, args["refillReminderDays"])
        assertEquals(listOf("c1"), args["caregiverIds"])
    }

    @Test
    fun `treats an absent optional like a null one`() {
        val args = reminderCreateCall(obj("""{"localId":"r","userId":"u","kind":"task","name":"n","schedule":{"type":"none"},"active":true,"createdAt":1,"updatedAt":2}""")).args
        assertEquals(8, args.size)
    }

    @Test
    fun `create refuses a payload missing a required field`() {
        val error = assertFailsWith<PushRejected> { reminderCreateCall(obj(REMINDER.replace(""""name":"Metformin",""", ""))) }
        assertEquals("Payload is missing name", error.message)
        assertFailsWith<PushRejected> { reminderCreateCall(obj(REMINDER.replace(""""userId":"u1"""", """"userId":null"""))) }
    }

    @Test
    fun `update addresses the server id and carries the mutable fields`() {
        val call = reminderUpdateCall(obj(REMINDER.replace(""""dosage":null""", """"dosage":"10 mg"""")), "cid")
        assertEquals("reminders:update", call.function)
        assertEquals(
            setOf("id", "name", "schedule", "active", "updatedAt", "dosage"),
            call.args.keys,
        )
        assertEquals("cid", call.args["id"])
        assertEquals(2000.0, call.args["updatedAt"])
        assertFailsWith<PushRejected> { reminderUpdateCall(obj("{}"), "cid") }
    }

    @Test
    fun `delete is a soft delete of the server id`() {
        assertEquals(PushCall("reminders:softDelete", mapOf("id" to "cid")), reminderDeleteCall("cid"))
    }
}

class InstancePushTest {
    private fun instance(status: String, extra: String = "") =
        obj(
            """{"localId":"i1","userId":"u1","reminderId":"r1","reminderKind":"medication","scheduledTime":5000,
            "status":"$status","completedAt":null,"snoozedUntil":null,"snoozeCount":2,"notes":null,
            "createdAt":10,"updatedAt":20 $extra}""",
        )

    @Test
    fun `create points at the parent's server id, not the local one`() {
        val call = instanceCreateCall(instance("pending"), "parent-cid")
        assertEquals("reminderInstances:create", call.function)
        assertEquals("parent-cid", call.args["reminderId"])
        assertEquals(2.0, call.args["snoozeCount"])
        assertEquals(5000.0, call.args["scheduledTime"])
        assertFalse(call.args.containsKey("completedAt"))
        assertFalse(call.args.containsKey("reminderKind"))
    }

    @Test
    fun `create carries the optional fields it has`() {
        val payload = obj(instance("completed").toString().replace(""""completedAt":null""", """"completedAt":99""").replace(""""notes":null""", """"notes":"ok""""))
        val args = instanceCreateCall(payload, "p").args
        assertEquals(99.0, args["completedAt"])
        assertEquals("ok", args["notes"])
    }

    @Test
    fun `completed marks completed with the time it was done`() {
        val withTime = obj(instance("completed").toString().replace(""""completedAt":null""", """"completedAt":99"""))
        assertEquals(PushCall("reminderInstances:markCompleted", mapOf("id" to "c", "completedAt" to 99.0)), instanceUpdateCall(withTime, "c"))
        assertEquals(PushCall("reminderInstances:markCompleted", mapOf("id" to "c")), instanceUpdateCall(instance("completed"), "c"))
    }

    @Test
    fun `snoozed snoozes until the stored time and leaves the count to the server`() {
        val snoozed = obj(instance("snoozed").toString().replace(""""snoozedUntil":null""", """"snoozedUntil":7000"""))
        assertEquals(PushCall("reminderInstances:snooze", mapOf("id" to "c", "snoozedUntil" to 7000.0)), instanceUpdateCall(snoozed, "c"))
    }

    @Test
    fun `a snooze with no snoozedUntil is not sent`() {
        val error = assertFailsWith<PushRejected> { instanceUpdateCall(instance("snoozed"), "c") }
        assertEquals("Snoozed instance is missing snoozedUntil", error.message)
    }

    @Test
    fun `skipped carries its note when there is one`() {
        assertEquals(PushCall("reminderInstances:markSkipped", mapOf("id" to "c")), instanceUpdateCall(instance("skipped"), "c"))
        val noted = obj(instance("skipped").toString().replace(""""notes":null""", """"notes":"felt sick""""))
        assertEquals(mapOf("id" to "c", "notes" to "felt sick"), instanceUpdateCall(noted, "c").args)
    }

    @Test
    fun `missed marks missed`() {
        assertEquals(PushCall("reminderInstances:markMissed", mapOf("id" to "c")), instanceUpdateCall(instance("missed"), "c"))
    }

    @Test
    fun `pending reopens, which is how an undo reaches the server`() {
        assertEquals(PushCall("reminderInstances:reopen", mapOf("id" to "c")), instanceUpdateCall(instance("pending"), "c"))
    }

    @Test
    fun `unknown and malformed statuses are refused`() {
        assertEquals("Unknown instance status taken", assertFailsWith<PushRejected> { instanceUpdateCall(instance("taken"), "c") }.message)
        assertTrue(assertFailsWith<PushRejected> { instanceUpdateCall(obj("""{"status":{"a":1}}"""), "c") }.message!!.startsWith("Unknown"))
        assertFailsWith<PushRejected> { instanceUpdateCall(obj("{}"), "c") }
    }
}
