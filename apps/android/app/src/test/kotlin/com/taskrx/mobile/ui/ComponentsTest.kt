package com.taskrx.mobile.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.taskrx.mobile.Session
import com.taskrx.mobile.notifications.CurrentUser
import com.taskrx.mobile.notifications.NotificationPrefs
import com.taskrx.mobile.ui.components.NumberField
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.Toaster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a count keeps what was typed, reports the number, and reports nothing when cleared`() {
        val reported = mutableListOf<Double?>()
        compose.setContent { TaskRxTheme { NumberField("Pills remaining", null, onChange = { reported += it }) } }

        compose.onNodeWithText("Pills remaining").assertExists()
        compose.onNodeWithText("—").performTextInput("1.")
        compose.onNodeWithText("1.").assertExists()
        compose.onNodeWithText("1.").performTextInput("5")
        compose.onNodeWithText("1.5").performTextClearance()

        assertEquals(listOf(1.0, 1.5, null), reported)
    }

    @Test
    fun `a whole starting number is shown without a decimal point, a fraction with one`() {
        compose.setContent {
            TaskRxTheme {
                androidx.compose.foundation.layout.Column {
                    NumberField("Whole", 30.0, onChange = {})
                    NumberField("Fraction", 1.5, onChange = {})
                    NumberField("Days", 7, onChange = {})
                }
            }
        }

        compose.onNodeWithText("30").assertExists()
        compose.onNodeWithText("1.5").assertExists()
        compose.onNodeWithText("7").assertExists()
    }

    @Test
    fun `each toast replaces the last and is numbered so its timer restarts`() {
        val toaster = Toaster()
        assertNull(toaster.current.value)

        toaster.show("one", ToastKind.Success)
        val first = toaster.current.value!!
        toaster.show("two", ToastKind.Error)
        val second = toaster.current.value!!

        assertEquals("two", second.message)
        assertEquals(ToastKind.Error, second.kind)
        assertNotEquals(first.id, second.id)

        toaster.dismiss()
        assertNull(toaster.current.value)
    }

    @Test
    fun `the session is the local user until sign-in replaces it, and starting it makes it durable`() {
        val prefs = NotificationPrefs(ApplicationProvider.getApplicationContext())
        val current = CurrentUser(prefs)
        val session = Session(current)

        assertEquals(Session.LOCAL_USER_ID, session.userId)
        assertNull(current.get(), "reading the session must not write")

        session.start()
        assertEquals(Session.LOCAL_USER_ID, current.get())

        current.set("someone-else")
        session.start()
        assertEquals("someone-else", session.userId, "start must not overwrite a user already set")
        current.set(null)
    }
}
