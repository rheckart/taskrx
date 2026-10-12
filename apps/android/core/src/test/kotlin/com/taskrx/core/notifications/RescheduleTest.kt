package com.taskrx.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun spec(id: String, tier: Tier = Tier.Medication) =
    NotificationSpec(id, tier, 1, "t", "b", "r", 1)

class PlanRescheduleTest {
    @Test
    fun `cancels what is in neither set and creates what is missing`() {
        val plan = planReschedule(
            desired = listOf(spec("a"), spec("b")),
            armed = listOf(ArmedNotification("b", Tier.Medication), ArmedNotification("stale", Tier.Medication)),
            exactAllowed = true,
        )
        assertEquals(listOf("stale"), plan.cancel)
        assertEquals(listOf("a"), plan.create.map { it.id })
    }

    @Test
    fun `an unchanged world plans nothing`() {
        val desired = listOf(spec("a"), spec("task-x-1", Tier.Task))
        val plan = planReschedule(desired, desired.map { ArmedNotification(it.id, it.tier) }, exactAllowed = true)
        assertTrue(plan.cancel.isEmpty())
        assertTrue(plan.create.isEmpty())
    }

    @Test
    fun `a pending snooze is never cancelled as stale`() {
        val plan = planReschedule(emptyList(), listOf(ArmedNotification("snooze-a-1-2", Tier.Medication), ArmedNotification("a-1", Tier.Medication)), true)
        assertEquals(listOf("a-1"), plan.cancel)
    }

    @Test
    fun `one Tier's armed ids survive a pass that desires them`() {
        val dose = spec("a")
        val task = spec("task-b-1", Tier.Task)
        val plan = planReschedule(listOf(dose, task), listOf(ArmedNotification("a", Tier.Medication), ArmedNotification("task-b-1", Tier.Task)), true)
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun `still cancels a task whose reminder is gone`() {
        assertEquals(listOf("task-gone-1"), planReschedule(emptyList(), listOf(ArmedNotification("task-gone-1", Tier.Task)), true).cancel)
    }

    @Test
    fun `re-arms a degraded dose once exact alarms are allowed`() {
        val plan = planReschedule(listOf(spec("a")), listOf(ArmedNotification("a", Tier.Medication, degraded = true)), exactAllowed = true)
        assertEquals(listOf("a"), plan.create.map { it.id })
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun `leaves a degraded dose alone while exact alarms are still denied`() {
        val plan = planReschedule(listOf(spec("a")), listOf(ArmedNotification("a", Tier.Medication, degraded = true)), exactAllowed = false)
        assertTrue(plan.create.isEmpty())
    }

    @Test
    fun `arms again a dose recorded as exact once exact alarms are denied, since revoking cancelled it`() {
        val plan = planReschedule(listOf(spec("a")), listOf(ArmedNotification("a", Tier.Medication)), exactAllowed = false)
        assertEquals(listOf("a"), plan.create.map { it.id })
    }

    @Test
    fun `denied exact alarms do not re-arm a task`() {
        val plan = planReschedule(listOf(spec("task-a-1", Tier.Task)), listOf(ArmedNotification("task-a-1", Tier.Task)), exactAllowed = false)
        assertTrue(plan.create.isEmpty())
    }

    @Test
    fun `leaves an exactly armed dose alone`() {
        assertTrue(planReschedule(listOf(spec("a")), listOf(ArmedNotification("a", Tier.Medication)), exactAllowed = true).create.isEmpty())
    }

    @Test
    fun `degraded is a dose concern, not a task one`() {
        val plan = planReschedule(listOf(spec("task-a-1", Tier.Task)), listOf(ArmedNotification("task-a-1", Tier.Task, degraded = true)), true)
        assertTrue(plan.create.isEmpty())
    }
}

class RearmTest {
    @Test
    fun `a reboot and an update lose the alarms but not the work queue`() {
        assertEquals(setOf(Tier.Medication), tiersToRearm(RearmCause.Boot))
        assertEquals(setOf(Tier.Medication), tiersToRearm(RearmCause.PackageReplaced))
    }

    @Test
    fun `a clock change loses both`() {
        assertEquals(setOf(Tier.Medication, Tier.Task), tiersToRearm(RearmCause.TimeChanged))
    }

    @Test
    fun `a permission change loses nothing`() {
        assertEquals(emptySet(), tiersToRearm(RearmCause.ExactAlarmPermissionChanged))
    }
}

class RearmPlanTest {
    @Test
    fun `an armed record on a rearmed Tier is created again and not cancelled`() {
        val plan = planReschedule(listOf(spec("a")), listOf(ArmedNotification("a", Tier.Medication)), true, rearm = setOf(Tier.Medication))
        assertEquals(listOf("a"), plan.create.map { it.id })
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun `the other Tier is left alone`() {
        val plan = planReschedule(listOf(spec("task-a-1", Tier.Task)), listOf(ArmedNotification("task-a-1", Tier.Task)), true, rearm = setOf(Tier.Medication))
        assertTrue(plan.create.isEmpty())
    }

    @Test
    fun `a stale record on a rearmed Tier is still cancelled`() {
        val plan = planReschedule(emptyList(), listOf(ArmedNotification("gone", Tier.Medication)), true, rearm = setOf(Tier.Medication))
        assertEquals(listOf("gone"), plan.cancel)
    }

    @Test
    fun `rearms only future snoozes on a lost Tier`() {
        val future = spec("snooze-a", Tier.Medication).copy(fireAt = 200)
        val past = spec("snooze-b", Tier.Medication).copy(fireAt = 50)
        val task = spec("snooze-c", Tier.Task).copy(fireAt = 200)
        assertEquals(listOf(future), snoozesToRearm(listOf(future, past, task), RearmCause.Boot, now = 100))
        assertEquals(listOf(future, task), snoozesToRearm(listOf(future, past, task), RearmCause.TimeChanged, now = 100))
        assertEquals(emptyList(), snoozesToRearm(listOf(future), RearmCause.ExactAlarmPermissionChanged, now = 100))
    }
}

class RearmCauseOfTest {
    @Test
    fun `maps each system broadcast`() {
        assertEquals(RearmCause.Boot, rearmCauseOf("android.intent.action.BOOT_COMPLETED"))
        assertEquals(RearmCause.PackageReplaced, rearmCauseOf("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals(RearmCause.TimeChanged, rearmCauseOf("android.intent.action.TIME_SET"))
        assertEquals(RearmCause.TimeChanged, rearmCauseOf("android.intent.action.TIMEZONE_CHANGED"))
        assertEquals(
            RearmCause.ExactAlarmPermissionChanged,
            rearmCauseOf("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"),
        )
    }

    @Test
    fun `ignores anything else`() {
        assertEquals(null, rearmCauseOf("android.intent.action.MAIN"))
        assertEquals(null, rearmCauseOf(null))
    }
}
