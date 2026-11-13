package com.thepuzzlequest.puzzlequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import com.thepuzzlequest.puzzlequest.core.QuestModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A quest's screen, and the details of its stops, kept while the screen is
 * rebuilt (as on rotating). Navigation gives each opened quest its own, and
 * clears it when the player goes back to the list.
 */
class QuestViewModel(
    questName: String,
    api: PuzzleQuestApi,
) : ViewModel() {
    val model = QuestModel(questName, api)

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
}
