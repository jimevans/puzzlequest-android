package com.thepuzzlequest.puzzlequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import com.thepuzzlequest.puzzlequest.core.SessionStore
import com.thepuzzlequest.puzzlequest.core.SignInModel
import com.thepuzzlequest.puzzlequest.core.TokenStore
import kotlinx.coroutines.launch

/**
 * The app's connection to its server and its session, for as long as the app
 * is open. A ViewModel outlives the screen's being rebuilt (on rotating, say),
 * and work started in its `viewModelScope` isn't cut off by that.
 */
class AppViewModel(
    server: String,
    tokens: TokenStore,
) : ViewModel() {
    val api = PuzzleQuestApi(server, tokens)
    val session = SessionStore(api, tokens)
    val signIn = SignInModel(session)

    init {
        restore()
    }

    fun restore() {
        viewModelScope.launch { session.restore() }
    }

    fun signIn(
        userName: String,
        password: String,
    ) {
        viewModelScope.launch { signIn.signIn(userName, password) }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }
}
