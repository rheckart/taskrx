package com.taskrx.mobile.ui.notices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.PrimaryButton

/**
 * What the share sheet opens: the shared text, a subject the person can correct, and Save.
 * The text is kept as a Notice; the date is set on the Notices screen, not read here.
 */
@Composable
fun ShareContent(
    state: ShareState,
    onTitle: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        FieldLabel("Subject", Modifier.padding(top = 0.dp))
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitle,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
        )
        FieldLabel("Shared text")
        Text(state.text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        HintText("Kept as a notice. Nothing is read from it, and no task changes until you say so.")
        state.error?.let { ErrorText("Save failed: $it", Modifier.padding(top = 16.dp)) }
        PrimaryButton("Save to Notices", onSave, Modifier.padding(top = 24.dp), enabled = state.canSave, busy = state.saving)
    }
}
