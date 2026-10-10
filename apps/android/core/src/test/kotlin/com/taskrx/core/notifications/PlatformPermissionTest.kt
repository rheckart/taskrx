package com.taskrx.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAlarmSettingTest {
    @Test
    fun `before API 31 the concept does not exist`() {
        assertEquals(ANDROID_ALARM_NOT_SUPPORTED, androidAlarmSetting(30, false))
    }

    @Test
    fun `from API 31 it follows canScheduleExactAlarms`() {
        assertEquals(ANDROID_ALARM_ENABLED, androidAlarmSetting(34, true))
        assertEquals(ANDROID_ALARM_DISABLED, androidAlarmSetting(34, false))
    }
}

class NotificationAuthorizationTest {
    @Test
    fun `granted and enabled is authorized`() {
        assertEquals(NOTIFICATION_AUTH_AUTHORIZED, notificationAuthorization(34, true, true, false))
    }

    @Test
    fun `granted but switched off in Settings is denied`() {
        assertEquals(NOTIFICATION_AUTH_DENIED, notificationAuthorization(34, true, false, false))
    }

    @Test
    fun `not granted and never asked is not determined`() {
        assertEquals(NOTIFICATION_AUTH_NOT_DETERMINED, notificationAuthorization(34, false, false, false))
    }

    @Test
    fun `not granted after the prompt was shown is denied`() {
        assertEquals(NOTIFICATION_AUTH_DENIED, notificationAuthorization(34, false, false, true))
    }

    @Test
    fun `before API 33 there is no permission to lack`() {
        assertEquals(NOTIFICATION_AUTH_AUTHORIZED, notificationAuthorization(32, false, true, false))
        assertEquals(NOTIFICATION_AUTH_DENIED, notificationAuthorization(32, false, false, false))
    }
}
