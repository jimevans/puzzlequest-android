package com.thepuzzlequest.puzzlequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thepuzzlequest.puzzlequest.core.HomeModel
import com.thepuzzlequest.puzzlequest.core.SessionStore
import com.thepuzzlequest.puzzlequest.core.SignedInUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A player's home screen, kept while the screen is rebuilt (as on rotating). */
class HomeViewModel(
    user: SignedInUser,
    private val session: SessionStore,
) : ViewModel() {
    val model = HomeModel(user, session.api)

    private val mutableRefreshing = MutableStateFlow(false)

    /** Whether a pull to refresh is under way. */
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    init {
        viewModelScope.launch { model.load() }
    }

    fun retry() {
        viewModelScope.launch { model.load() }
    }

    /**
     * Fetches the quests again, and who is signed in, in case their email
     * address has been verified since.
     */
    fun refresh() {
        viewModelScope.launch {
            mutableRefreshing.value = true
            launch { session.refreshUser() }
            model.load()
            mutableRefreshing.value = false
        }
    }

    fun sendVerificationEmail() {
        viewModelScope.launch { model.emailReminder.send() }
    }
}
