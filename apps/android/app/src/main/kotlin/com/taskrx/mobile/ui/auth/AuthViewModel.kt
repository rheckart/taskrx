package com.taskrx.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.mobile.auth.AuthGateway
import com.taskrx.mobile.auth.AuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SignIn, SignUp }

data class AuthState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val code: String = "",
    /** True once a passcode has been requested: the code field and Verify replace Send. */
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * The sign-in and sign-up screens' logic: email, then the passcode sent to it. Mirrors the
 * Expo `(auth)/sign-in` and `sign-up` screens (email passcode; the magic-link option is not
 * ported). On success the gateway's status flips to signed in and the app swaps the screens
 * for the day; nothing navigates from here.
 */
class AuthViewModel(private val gateway: AuthGateway) : ViewModel() {
    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun setEmail(value: String) = _state.update { it.copy(email = value) }

    fun setCode(value: String) = _state.update { it.copy(code = value) }

    /** Switching between the two screens starts that one over. */
    fun switchMode(mode: AuthMode) = _state.update { AuthState(mode = mode, email = it.email) }

    /** Back to the email step, to correct the address or have the code sent again. */
    fun changeEmail() = _state.update { it.copy(codeSent = false, code = "", error = null) }

    fun sendCode() {
        val current = _state.value
        val email = current.email.trim()
        if (email.isEmpty()) return fail("Please enter your email address.")
        run {
            when (current.mode) {
                AuthMode.SignIn -> gateway.startSignIn(email)
                AuthMode.SignUp -> gateway.startSignUp(email)
            }
        }
    }

    fun verify() {
        val current = _state.value
        val code = current.code.trim()
        if (code.isEmpty()) {
            return fail(if (current.mode == AuthMode.SignIn) "Please enter the passcode." else "Please enter the verification code.")
        }
        run {
            when (current.mode) {
                AuthMode.SignIn -> gateway.completeSignIn(code)
                AuthMode.SignUp -> gateway.completeSignUp(code)
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    /** Runs one gateway step; whether it was the code request or the verification decides what success means. */
    private fun run(step: suspend () -> AuthResult) {
        val sending = !_state.value.codeSent
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result =
                try {
                    step()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AuthResult.Failed(e.message ?: "Something went wrong. Please try again.")
                }
            _state.update {
                when (result) {
                    AuthResult.Ok -> it.copy(busy = false, codeSent = if (sending) true else it.codeSent)
                    is AuthResult.Failed -> it.copy(busy = false, error = result.message)
                }
            }
        }
    }
}
