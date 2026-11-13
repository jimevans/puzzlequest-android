package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.models.EntrantKind
import com.thepuzzlequest.puzzlequest.api.models.Progress
import com.thepuzzlequest.puzzlequest.api.models.PuzzleClock
import com.thepuzzlequest.puzzlequest.api.models.Quest
import com.thepuzzlequest.puzzlequest.api.models.QuestEntry
import com.thepuzzlequest.puzzlequest.api.models.SpectatorQuest
import java.time.Instant
import com.thepuzzlequest.puzzlequest.api.models.QuestStatus as ApiQuestStatus
import com.thepuzzlequest.puzzlequest.api.models.StopStatus as ApiStopStatus

/**
 * A quest as its player sees it: their own entry, and the stops it has
 * reached. Players never learn how many stops a quest has, so nothing here
 * says, and the stops are only those reached so far.
 */
data class PlayerQuest(
    val questName: String,
    val displayName: String,
    val entry: QuestEntryProgress,
)

/** Where a player's entry (theirs alone, or their team's) has got to. */
data class QuestEntryProgress(
    val id: String,
    /** Whether a team plays it, rather than one player. */
    val isTeam: Boolean,
    val status: QuestStatus,
    /** The status in the server's English words, for display. */
    val statusDescription: String,
    /** True while the puzzle master has paused it; its clocks stop. */
    val paused: Boolean,
    val startTime: Instant?,
    val endTime: Instant?,
    /** The time on the whole quest, with hint penalties. */
    val clock: QuestClock,
    /** The stops reached, in the order they were reached. */
    val stops: List<Stop>,
) {
    val isFinished: Boolean get() = status == QuestStatus.COMPLETED

    /** The stop being played: reached, and not yet solved or skipped. */
    val currentStop: Stop? get() = stops.lastOrNull { it.status != StopStatus.COMPLETED }

    /** The stops solved (or skipped), the latest first. */
    val finishedStops: List<Stop> get() = stops.filter { it.status == StopStatus.COMPLETED }.reversed()
}

/** A stop the entry has reached: its puzzle, and how it went. */
data class Stop(
    /** Which stop it is, from 1, in the order reached. */
    val number: Int,
    val puzzleName: String,
    val displayName: String,
    val status: StopStatus,
    /** The status in the server's English words, for display. */
    val statusDescription: String,
    /** True if the puzzle master skipped it for the players. */
    val skipped: Boolean = false,
    /** When the stop was reached. */
    val startTime: Instant? = null,
    /** When the players found the puzzle and activated it. */
    val activationTime: Instant? = null,
    /** When it was solved or skipped. */
    val endTime: Instant? = null,
    /** The solution, once solved. */
    val solutionText: String? = null,
    /** The time on this stop, with its hint penalties. */
    val clock: QuestClock? = null,
)

/** Where a stop stands. Version 1 may add statuses, shown as [OTHER]. */
enum class StopStatus {
    NOT_REACHED,

    /** Reached: the players are looking for the puzzle, to activate it. */
    AWAITING_ACTIVATION,

    /** Activated: the players are solving it. */
    IN_PROGRESS,

    /** Solved, or skipped. */
    COMPLETED,
    OTHER,
}

/** A race's standings, as a player may see them once their own entry has finished. */
data class Standings(
    /** True until every entry has finished: a later finisher could still beat these times. */
    val provisional: Boolean,
    /** True while the player's own entry is still racing, so the others are hidden from them. */
    val rivalsHidden: Boolean,
    /** In leaderboard order. */
    val entries: List<Standing>,
) {
    /** Whether the ranks are settled: every entry has finished. */
    val isFinal: Boolean get() = !provisional
}

/** One entry in the standings. */
data class Standing(
    val id: String,
    /** The team's or player's display name; null if it has been deleted. */
    val displayName: String?,
    val isTeam: Boolean,
    val status: QuestStatus,
    val statusDescription: String,
    val paused: Boolean,
    /** Its place once it has finished; null until then. */
    val rank: Int?,
    /** Whether it has started, so has a time to show. */
    val started: Boolean,
    val clock: QuestClock,
)

/**
 * Something about a quest that changed while it was on screen, for telling
 * TalkBack users what a sighted player sees happen. The clocks counting up
 * aren't changes.
 */
sealed interface QuestChange {
    data object Paused : QuestChange

    data object Resumed : QuestChange

    data class StopReached(
        val number: Int,
        val displayName: String,
    ) : QuestChange

    data class StopSolved(
        val displayName: String,
    ) : QuestChange

    data object Finished : QuestChange

    /** Another entry in the race finished, in that place. */
    data class RivalFinished(
        val entry: Standing,
        val rank: Int,
    ) : QuestChange

    /** Every entry has finished: these won (more than one if tied). */
    data class StandingsFinal(
        val winners: List<Standing>,
    ) : QuestChange

    companion object {
        /** What changed between two views of a quest, in the order a player would read them. */
        fun between(
            before: PlayerQuest,
            after: PlayerQuest,
        ): List<QuestChange> =
            buildList {
                if (before.entry.paused != after.entry.paused) add(if (after.entry.paused) Paused else Resumed)
                for (stop in after.entry.stops) {
                    val earlier = before.entry.stops.firstOrNull { it.puzzleName == stop.puzzleName }
                    if (earlier == null) {
                        add(StopReached(stop.number, stop.displayName))
                    } else if (stop.status == StopStatus.COMPLETED && earlier.status != StopStatus.COMPLETED) {
                        add(StopSolved(stop.displayName))
                    }
                }
                if (after.entry.isFinished && !before.entry.isFinished) add(Finished)
            }

        /**
         * What changed in the standings, leaving out the player's own entry
         * (whose finishing is announced as the quest's).
         */
        fun between(
            before: Standings,
            after: Standings,
            ownEntryId: String,
        ): List<QuestChange> =
            buildList {
                for (entry in after.entries) {
                    if (entry.id == ownEntryId) continue
                    val rank = entry.rank ?: continue
                    if (before.entries.firstOrNull { it.id == entry.id }?.rank == null) add(RivalFinished(entry, rank))
                }
                if (after.isFinal && !before.isFinal) add(StandingsFinal(after.entries.filter { it.rank == 1 }))
            }
    }
}

// From the API's schemas.

/** The player's quest, or null if it has no entry of theirs (as after the puzzle master resets it). */
internal fun playerQuest(quest: Quest): PlayerQuest? {
    val entry = quest.propertyEntries.firstOrNull() ?: return null
    return PlayerQuest(quest.questName, quest.displayName, QuestEntryProgress(entry))
}

internal fun QuestEntryProgress(entry: QuestEntry): QuestEntryProgress {
    val clocks = entry.progress.puzzles.associateBy({ it.puzzleName }, ::QuestClock)
    return QuestEntryProgress(
        id = entry.id,
        isTeam = entry.entrant.kind == EntrantKind.team,
        status = QuestStatus(entry.status),
        statusDescription = entry.statusDescription,
        paused = entry.paused,
        startTime = entry.progress.startTime?.toInstant(),
        endTime = entry.progress.endTime?.toInstant(),
        clock = QuestClock(entry.progress),
        stops =
            entry.puzzles.mapIndexed { index, puzzle ->
                Stop(
                    number = index + 1,
                    puzzleName = puzzle.puzzleName,
                    displayName = puzzle.displayName,
                    status = StopStatus(puzzle.status),
                    statusDescription = puzzle.statusDescription,
                    skipped = puzzle.skipped,
                    startTime = puzzle.startTime?.toInstant(),
                    activationTime = puzzle.activationTime?.toInstant(),
                    endTime = puzzle.endTime?.toInstant(),
                    solutionText = puzzle.solutionText,
                    clock = clocks[puzzle.puzzleName],
                )
            },
    )
}

internal fun Standings(quest: SpectatorQuest): Standings =
    Standings(
        provisional = quest.provisional,
        rivalsHidden = quest.rivalsHidden,
        entries =
            quest.propertyEntries.map { entry ->
                Standing(
                    id = entry.id,
                    displayName = entry.entrant.displayName,
                    isTeam = entry.entrant.kind == EntrantKind.team,
                    status = QuestStatus(entry.status),
                    statusDescription = entry.statusDescription,
                    paused = entry.paused,
                    rank = entry.rank?.toInt(),
                    started = entry.progress.startTime != null,
                    clock = QuestClock(entry.progress),
                )
            },
    )

internal fun QuestStatus(status: ApiQuestStatus): QuestStatus =
    when (status) {
        ApiQuestStatus.notStarted -> QuestStatus.NOT_STARTED
        ApiQuestStatus.inProgress -> QuestStatus.IN_PROGRESS
        ApiQuestStatus.completed -> QuestStatus.COMPLETED
        ApiQuestStatus.unknown_default_open_api -> QuestStatus.OTHER
    }

internal fun StopStatus(status: ApiStopStatus): StopStatus =
    when (status) {
        ApiStopStatus.notReached -> StopStatus.NOT_REACHED
        ApiStopStatus.awaitingActivation -> StopStatus.AWAITING_ACTIVATION
        ApiStopStatus.inProgress -> StopStatus.IN_PROGRESS
        ApiStopStatus.completed -> StopStatus.COMPLETED
        ApiStopStatus.unknown_default_open_api -> StopStatus.OTHER
    }

internal fun QuestClock(progress: Progress): QuestClock =
    QuestClock(
        running = progress.running,
        clockStart = progress.clockStart?.toInstant(),
        elapsedMs = progress.elapsedMs,
        penaltyMs = progress.penaltyMs,
        totalMs = progress.totalMs,
    )

internal fun QuestClock(clock: PuzzleClock): QuestClock =
    QuestClock(
        running = clock.running,
        clockStart = clock.clockStart?.toInstant(),
        elapsedMs = clock.elapsedMs,
        penaltyMs = clock.penaltyMs,
        totalMs = clock.totalMs,
    )
