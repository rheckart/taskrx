package com.taskrx.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taskrx.mobile.ui.TaskRxColors

/** A section label above a field. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Secondary)
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Error)
}

/** A full-width filled button that shows a spinner while it works. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    color: Color = TaskRxColors.Accent,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        colors =
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = color,
                disabledContainerColor = color.copy(alpha = 0.45f),
                disabledContentColor = Color.White,
            ),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        // The label stays while it works, so the button still says what it is to a screen reader.
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).padding(end = 0.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A row of mutually exclusive buttons over a small closed set. Kind, Priority and the
 * schedule type are all "pick one of a handful" and all need the selected state spoken to
 * a screen reader rather than shown only as a fill colour, hence the radio-group semantics.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.selectableGroup().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((key, label) in options) {
            val selected = key == value
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (selected) TaskRxColors.Accent else Color.Transparent,
                contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, if (selected) TaskRxColors.Accent else TaskRxColors.Outline),
                modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(key) }),
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * A count the person can clear. An empty box means "no value", a different thing from zero:
 * a supply of 0 pills is a claim, a blank field is the absence of one.
 *
 * The box holds the text and the caller holds the number, which is not redundancy:
 * rendering the parsed number back into the box would erase the decimal point the moment
 * it was typed ("1." parses to 1 and renders as "1", and the "5" of "1.5" has nowhere to
 * land). So the text is authoritative while the field has focus and only what it parses
 * to reaches the caller. [initialValue], not `value`: nothing outside re-drives the box.
 */
@Composable
fun NumberField(
    label: String,
    initialValue: Number?,
    onChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf(initialValue?.let { formatInitial(it) } ?: "") }
    Column(modifier.fillMaxWidth()) {
        FieldLabel(label)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(com.taskrx.mobile.ui.forms.parseCount(it))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            placeholder = { Text("—") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

/** 30.0 is shown as "30", 1.5 as "1.5": the box is a person's number, not a Double's. */
private fun formatInitial(value: Number): String {
    val d = value.toDouble()
    return if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
}

@Composable
fun ScreenCenter(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
