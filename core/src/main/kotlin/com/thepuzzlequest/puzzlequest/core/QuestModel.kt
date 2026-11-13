package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A player's quest screen: their entry's progress, kept up to date by the
 * quest's live updates, and once it has finished, the race's standings.
 */
class QuestModel(
    val questName: String,
    private val api: PuzzleQuestApi,
    connector: LiveSocketConnector = OkHttpLiveSocketConnector(),
    makeFollower: (LiveChannel, suspend () -> String) -> LiveUpdateFollower = { channel, ticket ->
        LiveUpdateFollower(api.liveUpdatesUrl, channel, connector, ticket)
    },
) {
    data class State(
        /** The quest, once fetched; null until then, or once it isn't available. */
        val quest: PlayerQuest? = null,
        /**
         * True once the server says the player has no entry to see, as after
         * the puzzle master resets the quest.
         */
        val notAvailable: Boolean = false,
        /**
         * Whether the latest fetch failed. A screen showing the quest keeps
         * it; one that hasn't explains, with a way to try again.
         */
        val loadFailed: Boolean = false,
        /**
         * The race's standings, once the player's entry has finished and if
         * others played it; null otherwise.
         */
        val standings: Standings? = null,
        /** What changed in the latest fetch that changed anything, for TalkBack. */
        val changes: List<QuestChange> = emptyList(),
        /**
         * Counts the fetches that changed something, so the screen can tell
         * when [changes] is new, even if it's the same as before.
         */
        val changeCount: Int = 0,
        /**
         * Whether live updates are connected and the quest fetched since, so
         * from here on a change arrives as an update.
         */
        val isLive: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val follower = makeFollower(LiveChannel.Quest(questName)) { api.questLiveTicket(questName) }

    /** Fetches can overlap; only the latest one's answer is kept. */
    private var latestFetch = 0

    /** Fetches the quest, as when the screen appears or is pulled down. */
    suspend fun load() = fetch()

    /**
     * Keeps the quest up to date until cancelled (the screen goes away, or the
     * app to the background): fetches it again on every update about it (the
     * web app does the same), and each time the connection is made, since
     * changes may have been missed, as they would be in the background.
     */
    suspend fun followLiveUpdates() {
        try {
            follower.run { event ->
                when (event) {
                    is LiveEvent.Connected -> {
                        fetch()
                        mutableState.update { it.copy(isLive = true) }
                    }

                    is LiveEvent.Update -> {
                        val update = event.update
                        if (update is LiveUpdate.QuestChanged && update.questName == questName) fetch()
                    }

                    LiveEvent.Disconnected -> {
                        mutableState.update { it.copy(isLive = false) }
                    }
                }
            }
        } finally {
            mutableState.update { it.copy(isLive = false) }
        }
    }

    private suspend fun fetch() {
        val fetch = ++latestFetch
        val fetched: PlayerQuest?
        var fetchedStandings: Standings? = null
        try {
            fetched = api.playerQuest(questName)
            // Standings only for a finished entry: during a race, a player
            // still racing isn't shown the others.
            if (fetched != null && fetched.entry.isFinished) {
                fetchedStandings =
                    try {
                        api.standings(questName)
                    } catch (_: ApiError) {
                        null
                    }
            }
        } catch (_: ApiError) {
            if (fetch == latestFetch) mutableState.update { it.copy(loadFailed = true) }
            return
        }
        if (fetch != latestFetch) return
        mutableState.update { state ->
            if (fetched == null) {
                return@update state.copy(quest = null, standings = null, notAvailable = true, loadFailed = false)
            }
            val changes = mutableListOf<QuestChange>()
            state.quest?.let { changes += QuestChange.between(it, fetched) }
            val standings =
                when {
                    // A quest one player or team played has no standings to show.
                    fetchedStandings != null -> {
                        fetchedStandings.takeIf { it.entries.size >= 2 }?.also { shown ->
                            state.standings?.let { changes += QuestChange.between(it, shown, fetched.entry.id) }
                        }
                    }

                    // Keep what was shown if only the standings couldn't be fetched.
                    fetched.entry.isFinished -> {
                        state.standings
                    }

                    else -> {
                        null
                    }
                }
            state.copy(
                quest = fetched,
                notAvailable = false,
                loadFailed = false,
                standings = standings,
                changes = if (changes.isEmpty()) state.changes else changes,
                changeCount = state.changeCount + if (changes.isEmpty()) 0 else 1,
            )
        }
    }
}
