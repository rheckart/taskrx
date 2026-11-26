package com.taskrx.mobile.ui.notices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.PrimaryButton

/**
 * What the share sheet opens: the shared text, a subject the person can correct, and the
 * choice to turn it straight into a new task or keep it in Notices for later.
 */
@Composable
fun ShareContent(
    state: ShareState,
    onTitle: (String) -> Unit,
    onSave: () -> Unit,
    onCreateTask: () -> Unit = {},
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
        HintText("Create a task straight from this text, or keep it in Notices to act on later. Nothing changes until you save.")
        state.error?.let { ErrorText("Save failed: $it", Modifier.padding(top = 16.dp)) }
        PrimaryButton("Create new task", onCreateTask, Modifier.padding(top = 24.dp), enabled = state.canSave)
        OutlinedButton(
            onClick = onSave,
            enabled = state.canSave && !state.saving,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("Save to Notices", fontWeight = FontWeight.SemiBold)
        }
    }
}
