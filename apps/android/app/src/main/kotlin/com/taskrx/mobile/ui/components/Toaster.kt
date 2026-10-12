package com.taskrx.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ToastKind { Success, Error, Info }

/** A one-line message shown for a moment, whichever screen is up when it lands. */
data class ToastRequest(val message: String, val kind: ToastKind, val id: Long = 0)

/**
 * The app's one toast. Mirrors the module-level store behind archive/expo-rn:apps/mobile's `useToast`:
 * a screen that saves and then navigates away still has its confirmation shown by the
 * screen it lands on, so the message cannot live in the screen that raised it.
 */
class Toaster {
    private val mutable = MutableStateFlow<ToastRequest?>(null)
    val current: StateFlow<ToastRequest?> = mutable

    private var counter = 0L

    fun show(request: ToastRequest) {
        mutable.value = request.copy(id = ++counter)
    }

    fun show(message: String, kind: ToastKind) = show(ToastRequest(message, kind))

    fun dismiss() {
        mutable.value = null
    }
}

const val TOAST_AUTO_DISMISS_MS: Long = 3_000

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val toast by toaster.current.collectAsState()
    val shown = toast ?: return

    // Keyed on the id, so a second toast restarts the timer instead of inheriting the first's.
    LaunchedEffect(shown.id) {
        delay(TOAST_AUTO_DISMISS_MS)
        toaster.dismiss()
    }

    val (background, glyph) =
        when (shown.kind) {
            ToastKind.Success -> Color(0xFF2E7D32) to "✓"
            ToastKind.Error -> Color(0xFFC62828) to "✕"
            ToastKind.Info -> Color(0xFF1565C0) to "ℹ"
        }

    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Surface(
            color = background,
            contentColor = Color.White,
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .clickable(onClickLabel = "Dismiss") { toaster.dismiss() },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // The glyph is decoration; the message says it all.
                Text("$glyph  ", modifier = Modifier.clearAndSetSemantics { }, color = Color.White)
                Text(shown.message, color = Color.White)
            }
        }
    }
}
