package com.thepuzzlequest.puzzlequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thepuzzlequest.puzzlequest.core.MessagesSeen
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import com.thepuzzlequest.puzzlequest.core.QuestModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A quest's screen, the details of its stops, and the puzzle master's
 * messages, kept while the screen is rebuilt (as on rotating). Navigation
 * gives each opened quest its own, and clears it when the player goes back
 * to the list.
 */
class QuestViewModel(
    questName: String,
    api: PuzzleQuestApi,
    messagesSeen: MessagesSeen,
) : ViewModel() {
    val model = QuestModel(questName, api, messagesSeen)

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

    /** Answers the challenge; [onDone] hears whether it was right. It runs here, so a rebuilt screen doesn't cut it off. */
    fun answerChallenge(
        answer: String,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch { onDone(model.answerChallenge(answer)) }
    }
}
