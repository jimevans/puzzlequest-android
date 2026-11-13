package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether anyone is signed in, kept across launches by the session's token. */
class SessionStore(
    private val api: PuzzleQuestApi,
    private val tokens: TokenStore,
) {
    sealed interface State {
        /** Checking a token kept from before with the server. */
        data object Restoring : State

        data object SignedOut : State

        data class SignedIn(
            val user: SignedInUser,
        ) : State

        /**
         * A token was kept, but the server couldn't say whose it is (it
         * couldn't be reached, or failed). The token is kept to try again.
         */
        data object RestoreFailed : State
    }

    private val mutableState = MutableStateFlow<State>(State.Restoring)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Picks up the session kept from before, if it hasn't ended. A token the
     * server no longer accepts is dropped.
     */
    suspend fun restore() {
        if (tokens.read() == null) {
            mutableState.value = State.SignedOut
            return
        }
        mutableState.value = State.Restoring
        mutableState.value =
            try {
                State.SignedIn(api.currentUser())
            } catch (error: ApiError) {
                if (error is ApiError.Refused && error.problem.status == 401) {
                    tokens.delete()
                    State.SignedOut
                } else {
                    State.RestoreFailed
                }
            }
    }

    /** @throws ApiError if the server refused, or couldn't be reached. */
    suspend fun signIn(
        userName: String,
        password: String,
    ) {
        val (token, user) = api.signIn(userName, password)
        // If the token can't be kept, the session lasts until the app closes.
        try {
            tokens.save(token)
        } catch (_: Exception) {
        }
        mutableState.value = State.SignedIn(user)
    }

    /**
     * Ends the session on the server, then forgets it here, even if the
     * server couldn't be told.
     */
    suspend fun signOut() {
        try {
            api.signOut()
        } catch (_: ApiError) {
        }
        tokens.delete()
        mutableState.value = State.SignedOut
    }
}
