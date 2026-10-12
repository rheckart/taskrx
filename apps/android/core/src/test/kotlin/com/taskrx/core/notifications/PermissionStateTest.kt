package com.taskrx.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsExactAlarmGrantedTest {
    @Test
    fun `returns true when the platform exposes no alarm setting (alarm is null)`() {
        assertTrue(isExactAlarmGranted(null))
    }

    @Test
    fun `returns true on Android under 12 (NOT_SUPPORTED)`() {
        assertTrue(isExactAlarmGranted(ANDROID_ALARM_NOT_SUPPORTED))
    }

    @Test
    fun `returns true when the user has enabled the permission`() {
        assertTrue(isExactAlarmGranted(ANDROID_ALARM_ENABLED))
    }

    @Test
    fun `returns false when the user has denied the permission`() {
        assertFalse(isExactAlarmGranted(ANDROID_ALARM_DISABLED))
    }
}

class DerivePermissionStatusTest {
    @Test
    fun `authorized - notifications granted + alarm enabled`() {
        assertEquals(
            PermissionSnapshot(PermissionStatus.Authorized, notificationGranted = true, exactAlarmGranted = true),
            derivePermissionStatus(PermissionSnapshotInput(NOTIFICATION_AUTH_AUTHORIZED, ANDROID_ALARM_ENABLED)),
        )
    }

    @Test
    fun `authorized with no alarm setting (alarm null) and notifications granted`() {
        assertEquals(
            PermissionSnapshot(PermissionStatus.Authorized, notificationGranted = true, exactAlarmGranted = true),
            derivePermissionStatus(PermissionSnapshotInput(NOTIFICATION_AUTH_AUTHORIZED, null)),
        )
    }

    @Test
    fun `partial - notifications granted but exact-alarm denied`() {
        assertEquals(
            PermissionSnapshot(PermissionStatus.Partial, notificationGranted = true, exactAlarmGranted = false),
            derivePermissionStatus(PermissionSnapshotInput(NOTIFICATION_AUTH_AUTHORIZED, ANDROID_ALARM_DISABLED)),
        )
    }

    @Test
    fun `partial - provisional notifications + alarm denied`() {
        assertEquals(
            PermissionSnapshot(PermissionStatus.Partial, notificationGranted = true, exactAlarmGranted = false),
            derivePermissionStatus(PermissionSnapshotInput(NOTIFICATION_AUTH_PROVISIONAL, ANDROID_ALARM_DISABLED)),
        )
    }

    @Test
    fun `denied - notifications explicitly denied (regardless of alarm state)`() {
        assertEquals(
            PermissionStatus.Denied,
            derivePermissionStatus(PermissionSnapshotInput(NOTIFICATION_AUTH_DENIED, ANDROID_ALARM_ENABLED)).status,
        )
    }

    @Test
    fun `undetermined - notifications not yet decided (fix for upstream 1237)`() {
        // The platform must report this state correctly before the user has been
        // prompted, rather than the old behaviour of returning DENIED on Android 13+.
        assertEquals(
            PermissionStatus.Undetermined,
            derivePermissionStatus(
                PermissionSnapshotInput(NOTIFICATION_AUTH_NOT_DETERMINED, ANDROID_ALARM_ENABLED),
            ).status,
        )
    }

    @Test
    fun `Android under 12 with granted notifications is authorized (NOT_SUPPORTED alarm)`() {
        assertEquals(
            PermissionStatus.Authorized,
            derivePermissionStatus(
                PermissionSnapshotInput(NOTIFICATION_AUTH_AUTHORIZED, ANDROID_ALARM_NOT_SUPPORTED),
            ).status,
        )
    }

    @Test
    fun `an authorization value nobody recognises is undetermined rather than authorized`() {
        // Not a vitest case (TypeScript's union made it unrepresentable); the
        // Kotlin raw value is an Int, so the rule it protects needs a case.
        val snapshot = derivePermissionStatus(PermissionSnapshotInput(99, ANDROID_ALARM_ENABLED))

        assertEquals(PermissionStatus.Undetermined, snapshot.status)
        assertFalse(snapshot.notificationGranted)
    }
}

/**
 * The two decisions issue #20 turns on: whether to put the OS prompt in front
 * of someone, and what to tell them when the answer leaves reminders unable to
 * arrive.
 */
private fun snapshot(status: PermissionStatus, exactAlarmGranted: Boolean = true): PermissionSnapshot =
    PermissionSnapshot(
        status = status,
        notificationGranted = status == PermissionStatus.Authorized || status == PermissionStatus.Partial,
        exactAlarmGranted = exactAlarmGranted,
    )

class ShouldRequestNotificationPermissionTest {
    @Test
    fun `asks when the person has never been asked`() {
        assertTrue(
            shouldRequestNotificationPermission(PermissionGateInput(snapshot(PermissionStatus.Undetermined), asked = false)),
        )
    }

    @Test
    fun `does not ask twice in one session`() {
        // Android leaves the state undetermined when the dialog is dismissed
        // without an answer, so this is reachable, and a re-ask on every return to
        // the foreground would be a dialog someone cannot get away from.
        assertFalse(
            shouldRequestNotificationPermission(PermissionGateInput(snapshot(PermissionStatus.Undetermined), asked = true)),
        )
    }

    @Test
    fun `does not ask again after a denial`() {
        // Android 13+ answers a repeat request for a denied permission without
        // showing anything, so asking would look to the app like a fresh refusal
        // and to the person like nothing at all. Settings is the only route left.
        assertFalse(
            shouldRequestNotificationPermission(PermissionGateInput(snapshot(PermissionStatus.Denied), asked = false)),
        )
    }

    @Test
    fun `does not ask when notifications are already granted`() {
        assertFalse(
            shouldRequestNotificationPermission(PermissionGateInput(snapshot(PermissionStatus.Authorized), asked = false)),
        )
    }

    @Test
    fun `does not ask when only the exact-alarm half is missing`() {
        // `partial` means POST_NOTIFICATIONS is granted. The gap is real and is
        // reported below, but it is not a thing this prompt can fix.
        assertFalse(
            shouldRequestNotificationPermission(
                PermissionGateInput(snapshot(PermissionStatus.Partial, exactAlarmGranted = false), asked = false),
            ),
        )
    }
}

class DerivePermissionGapTest {
    @Test
    fun `reports nothing when reminders can arrive on time`() {
        assertNull(derivePermissionGap(PermissionGateInput(snapshot(PermissionStatus.Authorized), asked = true)))
    }

    @Test
    fun `stays quiet until the prompt has actually been shown`() {
        // The one moment a gap is real and must not be rendered: the app is about
        // to ask. A banner here would be shouting at someone a half-second before
        // the dialog they are going to say yes to.
        assertNull(derivePermissionGap(PermissionGateInput(snapshot(PermissionStatus.Undetermined), asked = false)))
    }

    @Test
    fun `reports a blocking gap once the prompt has been shown and answered with nothing`() {
        val gap = derivePermissionGap(PermissionGateInput(snapshot(PermissionStatus.Undetermined), asked = true))

        assertEquals(PermissionSeverity.Blocking, gap?.severity)
        assertEquals(PermissionRemedy.Request, gap?.remedy)
    }

    @Test
    fun `sends a denial to the notification settings, not back to the prompt`() {
        val gap = derivePermissionGap(PermissionGateInput(snapshot(PermissionStatus.Denied), asked = true))

        assertEquals(PermissionSeverity.Blocking, gap?.severity)
        assertEquals(PermissionRemedy.NotificationSettings, gap?.remedy)
    }

    @Test
    fun `reports a denial even when the app has not asked yet`() {
        // Revoked from Settings between two foregrounds — the app never asked in
        // this session and the reminders are dropped all the same.
        assertEquals(
            PermissionSeverity.Blocking,
            derivePermissionGap(PermissionGateInput(snapshot(PermissionStatus.Denied), asked = false))?.severity,
        )
    }

    @Test
    fun `calls a missing exact alarm degraded rather than blocking`() {
        // Reminders still arrive; they arrive late. Saying "reminders cannot be
        // shown" here would be false, and a person who has learned that the app
        // cries wolf reads past the message that means it.
        val gap =
            derivePermissionGap(
                PermissionGateInput(snapshot(PermissionStatus.Partial, exactAlarmGranted = false), asked = true),
            )

        assertEquals(PermissionSeverity.Degraded, gap?.severity)
        assertEquals(PermissionRemedy.AlarmSettings, gap?.remedy)
    }

    @Test
    fun `names the notification gap first when both halves are missing`() {
        // A denial makes every reminder vanish; a missing exact alarm makes it
        // late. Offering the alarm-settings route first would send someone to fix
        // the smaller of two problems.
        assertEquals(
            PermissionRemedy.NotificationSettings,
            derivePermissionGap(
                PermissionGateInput(snapshot(PermissionStatus.Denied, exactAlarmGranted = false), asked = true),
            )?.remedy,
        )
    }

    @Test
    fun `says what happens to the reminders, not what the permission is called`() {
        // The person reading this has no reason to know what POST_NOTIFICATIONS
        // is. What they need to know is that doses will not reach them.
        val gap = derivePermissionGap(PermissionGateInput(snapshot(PermissionStatus.Denied), asked = true))
        val body = gap?.body.orEmpty()

        assertTrue(Regex("reminder", RegexOption.IGNORE_CASE).containsMatchIn(body))
        assertFalse(Regex("POST_NOTIFICATIONS").containsMatchIn(body))
    }

    @Test
    fun `gives every gap a label for the button that resolves it`() {
        val inputs =
            listOf(
                PermissionGateInput(snapshot(PermissionStatus.Undetermined), asked = true),
                PermissionGateInput(snapshot(PermissionStatus.Denied), asked = true),
                PermissionGateInput(snapshot(PermissionStatus.Partial, exactAlarmGranted = false), asked = true),
            )
        for (input in inputs) {
            val gap = assertNotNull(derivePermissionGap(input))
            assertTrue(gap.title.isNotEmpty())
            assertTrue(gap.remedyLabel.isNotEmpty())
        }
    }
}
