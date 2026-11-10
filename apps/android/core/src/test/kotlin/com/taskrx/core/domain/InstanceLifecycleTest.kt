package com.taskrx.core.domain

import com.taskrx.core.undo.UNDOABLE_STATUSES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceLifecycleTest {
    @Test
    fun `every status offers exactly the actions its lifecycle allows`() {
        assertEquals(
            setOf(InstanceAction.Complete, InstanceAction.Skip, InstanceAction.Snooze),
            availableActions(InstanceStatus.Pending),
        )
        assertEquals(
            setOf(InstanceAction.Complete, InstanceAction.Skip, InstanceAction.Snooze, InstanceAction.Undo),
            availableActions(InstanceStatus.Snoozed),
        )
        assertEquals(
            setOf(InstanceAction.Undo, InstanceAction.ChangeCompletedTime),
            availableActions(InstanceStatus.Completed),
        )
        assertEquals(setOf(InstanceAction.Undo), availableActions(InstanceStatus.Skipped))
        assertEquals(
            setOf(InstanceAction.Complete, InstanceAction.Skip, InstanceAction.Snooze),
            availableActions(InstanceStatus.Missed),
        )
    }

    @Test
    fun `the answerable and undoable sets are the ones the rest of the app imports`() {
        assertEquals(setOf(InstanceStatus.Pending, InstanceStatus.Snoozed, InstanceStatus.Missed), ANSWERABLE_STATUSES)
        assertEquals(setOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Snoozed), UNDOABLE_STATUSES)
        for (status in InstanceStatus.entries) {
            assertEquals(InstanceAction.Complete in availableActions(status), isAnswerable(status), "$status answerable")
            assertEquals(InstanceAction.Undo in availableActions(status), status in UNDOABLE_STATUSES, "$status undoable")
        }
    }

    @Test
    fun `an offered action targets the status it names`() {
        assertEquals(InstanceStatus.Completed, actionTarget(InstanceAction.Complete, InstanceStatus.Pending))
        assertEquals(InstanceStatus.Skipped, actionTarget(InstanceAction.Skip, InstanceStatus.Missed))
        assertEquals(InstanceStatus.Snoozed, actionTarget(InstanceAction.Snooze, InstanceStatus.Snoozed))
        assertEquals(InstanceStatus.Pending, actionTarget(InstanceAction.Undo, InstanceStatus.Completed))
        assertEquals(InstanceStatus.Completed, actionTarget(InstanceAction.ChangeCompletedTime, InstanceStatus.Completed))
    }

    @Test
    fun `an action the status does not offer has no target`() {
        assertNull(actionTarget(InstanceAction.Complete, InstanceStatus.Completed))
        assertNull(actionTarget(InstanceAction.Skip, InstanceStatus.Skipped))
        assertNull(actionTarget(InstanceAction.Snooze, InstanceStatus.Completed))
        assertNull(actionTarget(InstanceAction.Undo, InstanceStatus.Pending))
        assertNull(actionTarget(InstanceAction.ChangeCompletedTime, InstanceStatus.Pending))
    }

    @Test
    fun `a row may be created in any status and rewritten to the one it already has`() {
        for (status in InstanceStatus.entries) {
            assertTrue(allowsInstanceTransition(null, status), "create $status")
            assertTrue(allowsInstanceTransition(status, status), "rewrite $status")
        }
    }

    @Test
    fun `a pending row can become any status`() {
        for (status in InstanceStatus.entries) {
            assertTrue(allowsInstanceTransition(InstanceStatus.Pending, status), "pending -> $status")
        }
    }

    @Test
    fun `a snoozed row can become any status, because a deferral is not an answer`() {
        for (status in InstanceStatus.entries) {
            assertTrue(allowsInstanceTransition(InstanceStatus.Snoozed, status), "snoozed -> $status")
        }
    }

    @Test
    fun `a skipped row can be reopened or answered but never swept as missed`() {
        assertTrue(allowsInstanceTransition(InstanceStatus.Skipped, InstanceStatus.Pending))
        assertTrue(allowsInstanceTransition(InstanceStatus.Skipped, InstanceStatus.Completed))
        assertTrue(allowsInstanceTransition(InstanceStatus.Skipped, InstanceStatus.Snoozed))
        assertFalse(allowsInstanceTransition(InstanceStatus.Skipped, InstanceStatus.Missed))
    }

    @Test
    fun `a missed row can be answered but not reopened`() {
        assertFalse(allowsInstanceTransition(InstanceStatus.Missed, InstanceStatus.Pending))
        assertTrue(allowsInstanceTransition(InstanceStatus.Missed, InstanceStatus.Completed))
        assertTrue(allowsInstanceTransition(InstanceStatus.Missed, InstanceStatus.Skipped))
        assertTrue(allowsInstanceTransition(InstanceStatus.Missed, InstanceStatus.Snoozed))
    }

    @Test
    fun `a completed row is final until the person undoes it`() {
        assertTrue(allowsInstanceTransition(InstanceStatus.Completed, InstanceStatus.Pending))
        assertFalse(allowsInstanceTransition(InstanceStatus.Completed, InstanceStatus.Skipped))
        assertFalse(allowsInstanceTransition(InstanceStatus.Completed, InstanceStatus.Snoozed))
        assertFalse(allowsInstanceTransition(InstanceStatus.Completed, InstanceStatus.Missed))
    }

    @Test
    fun `the refusal explains the pair it refused, and says nothing when the move is legal`() {
        assertNull(instanceTransitionProblem(InstanceStatus.Pending, InstanceStatus.Missed))
        assertNull(instanceTransitionProblem(InstanceStatus.Completed, InstanceStatus.Completed))

        val message = instanceTransitionProblem(InstanceStatus.Completed, InstanceStatus.Skipped)
        assertTrue(message != null && "completed" in message && "skipped" in message, message)
    }
}