package com.thepuzzlequest.puzzlequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thepuzzlequest.puzzlequest.core.PuzzleModel
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A stop's puzzle screen, kept while the screen is rebuilt (as on rotating).
 * The player's actions run here rather than in the screen, so one under way
 * finishes even if the screen is rebuilt meanwhile.
 */
class PuzzleViewModel(
    questName: String,
    puzzleName: String,
    api: PuzzleQuestApi,
) : ViewModel() {
    val model = PuzzleModel(questName, puzzleName, api)

    private val mutableRefreshing = MutableStateFlow(false)

    /** Whether a pull to refresh is under way. */
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    init {
        viewModelScope.launch { model.load() }
    }

    fun retry() {
        viewModelScope.launch { model.load() }
    }

    fun refresh() {
        viewModelScope.launch {
            mutableRefreshing.value = true
            model.load()
            mutableRefreshing.value = false
        }
    }

    /** Activates the puzzle with a typed code; [onDone] hears whether it was accepted. */
    fun activate(
        code: String,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            model.activate(code)
            onDone(model.state.value.actionError == null)
        }
    }

    fun scanned(code: String) {
        viewModelScope.launch { model.scanned(code) }
    }

    /** Guesses the solution; [onDone] hears whether it was right. */
    fun solve(
        guess: String,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch { onDone(model.solve(guess)) }
    }

    fun revealHint() {
        viewModelScope.launch { model.revealHint() }
    }

    fun goToNextStop() {
        viewModelScope.launch { model.goToNextStop() }
    }
}
