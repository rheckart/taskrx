package com.taskrx.mobile.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.PrimaryButton

/**
 * Sign in / sign up with an emailed passcode. Mirrors the Expo `(auth)/sign-in` and
 * `sign-up` screens: one email field, then a six-digit code.
 */
@Composable
fun AuthContent(state: AuthState, actions: AuthActions, modifier: Modifier = Modifier) {
    val signIn = state.mode == AuthMode.SignIn
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(if (signIn) "Sign in to TaskRx" else "Create your account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            if (signIn) "Enter your email to receive a passcode." else "Enter your email to receive a verification code.",
            color = TaskRxColors.Secondary,
            textAlign = TextAlign.Center,
        )

        OutlinedTextField(
            value = state.email,
            onValueChange = actions.onEmail,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy && !state.codeSent,
            singleLine = true,
            label = { Text("Email address") },
            placeholder = { Text("you@example.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )

        state.error?.let { Text(it, color = TaskRxColors.Error, textAlign = TextAlign.Center) }

        if (state.codeSent) {
            Text("Enter the 6-digit code sent to your email.", color = TaskRxColors.Secondary, textAlign = TextAlign.Center)
            OutlinedTextField(
                value = state.code,
                onValueChange = { actions.onCode(it.take(CODE_LENGTH)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                singleLine = true,
                label = { Text(if (signIn) "Passcode" else "Verification code") },
                placeholder = { Text("000000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            PrimaryButton("Verify", actions.onVerify, busy = state.busy)
            TextButton(onClick = actions.onChangeEmail, enabled = !state.busy) { Text("Use a different email") }
        } else {
            PrimaryButton(if (signIn) "Send passcode" else "Send code", actions.onSend, busy = state.busy)
        }

        TextButton(onClick = { actions.onMode(if (signIn) AuthMode.SignUp else AuthMode.SignIn) }, enabled = !state.busy) {
            Text(if (signIn) "Don't have an account? Sign up" else "Already have an account? Sign in")
        }
    }
}

private const val CODE_LENGTH = 6

data class AuthActions(
    val onEmail: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onSend: () -> Unit,
    val onVerify: () -> Unit,
    val onChangeEmail: () -> Unit,
    val onMode: (AuthMode) -> Unit,
)
