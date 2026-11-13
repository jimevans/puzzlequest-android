package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The sign-in screen's state: whether what's typed is being sent, and why it
 * last failed. The screen keeps what's typed, and passes it in.
 */
class SignInModel(
    private val session: SessionStore,
) {
    data class State(
        val isSigningIn: Boolean = false,
        val failure: SignInFailure? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun canSignIn(
        userName: String,
        password: String,
    ): Boolean = userName.trim().isNotEmpty() && password.isNotEmpty() && !state.value.isSigningIn

    /** @return whether it signed in. */
    suspend fun signIn(
        userName: String,
        password: String,
    ): Boolean {
        if (!canSignIn(userName, password)) return false
        mutableState.value = State(isSigningIn = true)
        return try {
            // User names are typed by hand, often on a phone's keyboard.
            session.signIn(userName.trim(), password)
            mutableState.value = State()
            true
        } catch (error: ApiError) {
            mutableState.value = State(failure = SignInFailure.of(error))
            false
        } finally {
            mutableState.update { it.copy(isSigningIn = false) }
        }
    }
}

/** Why signing in failed, as the screen explains it. */
enum class SignInFailure {
    /** The user name and password don't match an account. */
    WRONG_USER_NAME_OR_PASSWORD,

    /** Too many failed attempts; wait before trying again. */
    TOO_MANY_ATTEMPTS,

    /** The server didn't accept what was sent. */
    INVALID_REQUEST,

    /** The server couldn't be reached. */
    UNREACHABLE,

    /** Anything else, such as a fault on the server. */
    UNEXPECTED,
    ;

    companion object {
        fun of(error: ApiError): SignInFailure =
            when (error) {
                is ApiError.Refused -> {
                    when (error.problem.type) {
                        Problem.Types.SIGN_IN_FAILED -> WRONG_USER_NAME_OR_PASSWORD
                        Problem.Types.TOO_MANY_REQUESTS -> TOO_MANY_ATTEMPTS
                        Problem.Types.INVALID_REQUEST -> INVALID_REQUEST
                        else -> UNEXPECTED
                    }
                }

                ApiError.Unreachable -> {
                    UNREACHABLE
                }

                is ApiError.UnexpectedResponse -> {
                    UNEXPECTED
                }
            }
    }
}
