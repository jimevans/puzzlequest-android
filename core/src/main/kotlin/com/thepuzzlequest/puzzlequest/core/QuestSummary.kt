package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.models.Clock
import java.time.Duration
import java.time.Instant
import com.thepuzzlequest.puzzlequest.api.models.QuestStatus as ApiQuestStatus
import com.thepuzzlequest.puzzlequest.api.models.QuestSummary as ApiQuestSummary

/**
 * One of the quests a player plays, as their home screen lists it. It says
 * nothing about how many stops the quest has: players never learn that.
 */
data class QuestSummary(
    val questName: String,
    val displayName: String,
    val status: QuestStatus,
    /** The status in the server's English words, for display. */
    val statusDescription: String,
    /**
     * True while the puzzle master has paused the player's entry; its clock
     * stops, and its status stays in progress.
     */
    val paused: Boolean,
    /** The time spent on the quest. */
    val progress: QuestClock,
)

/**
 * Where a quest stands. Version 1 may add statuses, which the app shows as
 * [OTHER] rather than failing.
 */
enum class QuestStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, OTHER }

/**
 * How long players have spent on a quest, as the API last reported it. While
 * it runs, the time is worked out from when it (in effect) started, so a
 * screen can count up without asking the server again.
 */
data class QuestClock(
    /** True while the clock runs: started, not stopped, and not paused. */
    val running: Boolean,
    /**
     * While running, when the clock would have started had there been no
     * pauses; null while not running.
     */
    val clockStart: Instant?,
    /** Milliseconds on the clock as of the response, leaving out paused time. */
    val elapsedMs: Long,
    /** The revealed hints' time penalties, in milliseconds. */
    val penaltyMs: Long,
    /** Elapsed time plus penalties, as of the response: the score. */
    val totalMs: Long,
) {
    /** Whether the time changes from moment to moment, so needs redrawing. */
    val isCounting: Boolean get() = running && clockStart != null

    /** The time spent at a moment, not counting penalties, in milliseconds. */
    fun elapsedMs(at: Instant): Long {
        val start = clockStart
        if (!running || start == null) return elapsedMs
        return Duration.between(start, at).toMillis()
    }

    /** The players' score at a moment: the time spent plus penalties, in milliseconds. */
    fun totalMs(at: Instant): Long = if (isCounting) elapsedMs(at) + penaltyMs else totalMs

    companion object {
        /**
         * A length of time as a clock shows it, such as 4:05 or 1:04:05. A
         * negative time (a phone whose clock is behind the server's) shows as
         * 0:00.
         */
        fun format(milliseconds: Long): String {
            val seconds = maxOf(0, milliseconds / 1000)
            val minutes = seconds / 60
            val hours = minutes / 60
            val twoDigits = { n: Long -> n.toString().padStart(2, '0') }
            return if (hours > 0) {
                "$hours:${twoDigits(minutes % 60)}:${twoDigits(seconds % 60)}"
            } else {
                "$minutes:${twoDigits(seconds % 60)}"
            }
        }
    }
}

internal fun QuestSummary(summary: ApiQuestSummary) =
    QuestSummary(
        questName = summary.questName,
        displayName = summary.displayName,
        status =
            when (summary.status) {
                ApiQuestStatus.notStarted -> QuestStatus.NOT_STARTED
                ApiQuestStatus.inProgress -> QuestStatus.IN_PROGRESS
                ApiQuestStatus.completed -> QuestStatus.COMPLETED
                ApiQuestStatus.unknown_default_open_api -> QuestStatus.OTHER
            },
        statusDescription = summary.statusDescription,
        paused = summary.paused,
        progress = QuestClock(summary.progress),
    )

internal fun QuestClock(clock: Clock) =
    QuestClock(
        running = clock.running,
        clockStart = clock.clockStart?.toInstant(),
        elapsedMs = clock.elapsedMs,
        penaltyMs = clock.penaltyMs,
        totalMs = clock.totalMs,
    )
