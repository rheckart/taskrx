package com.taskrx.mobile.ui

import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.USER
import com.taskrx.mobile.ui.settings.SettingsViewModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @Test
    fun `refresh lists the watched sources`() =
        withUi(NOW) {
            container.watchSources.create(
                com.taskrx.mobile.data.CreateWatchedSourceInput(USER, SourceKind.Email, "waste@city.example", "Waste"),
            )
            val vm = SettingsViewModel(container)

            vm.refresh().join()

            assertFalse(vm.state.value.loading)
            assertEquals(listOf("Waste"), vm.state.value.sources.map { it.label })
        }

    @Test
    fun `an email is lowercased and must look like an address`() =
        withUi(NOW) {
            val vm = SettingsViewModel(container)
            vm.startAdd()
            vm.setValue("  Waste@City.Example ")

            assertEquals("waste@city.example", vm.state.value.normalizedValue)

            vm.setValue("not an address")
            assertNull(vm.state.value.normalizedValue)
            assertFalse(vm.state.value.canSave)
        }

    @Test
    fun `saving stores the canonical value and clears the form`() =
        withUi(NOW) {
            val vm = SettingsViewModel(container)
            vm.startAdd()
            vm.setValue("Waste@City.Example")
            vm.setLabel("Waste")

            vm.saveSource().join()

            assertFalse(vm.state.value.adding)
            assertEquals("", vm.state.value.value)
            val source = container.watchSources.list(USER).single()
            assertEquals("waste@city.example", source.value)
            assertEquals("Waste", source.label)
        }

    @Test
    fun `an app package keeps its spelling`() =
        withUi(NOW) {
            val vm = SettingsViewModel(container)
            vm.startAdd()
            vm.setKind(SourceKind.App)
            vm.setValue(" com.example.app ")

            assertEquals("com.example.app", vm.state.value.normalizedValue)
        }

    @Test
    fun `removing a source deletes it`() =
        withUi(NOW) {
            val created =
                container.watchSources.create(
                    com.taskrx.mobile.data.CreateWatchedSourceInput(USER, SourceKind.Email, "waste@city.example", "Waste"),
                )
            val vm = SettingsViewModel(container)
            vm.refresh().join()

            vm.remove(created).join()

            assertTrue(container.watchSources.list(USER).isEmpty())
            assertTrue(vm.state.value.sources.isEmpty())
        }
}
