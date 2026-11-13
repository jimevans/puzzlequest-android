package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.models.PuzzleType
import java.net.URI
import java.net.URISyntaxException
import com.thepuzzlequest.puzzlequest.api.models.NextHint as ApiNextHint
import com.thepuzzlequest.puzzlequest.api.models.PlayerPuzzle as ApiPlayerPuzzle

/**
 * A stop's puzzle as its players see it: waiting to be found and activated,
 * being solved, or solved. Like the quest, it never says how many stops
 * there are.
 */
data class PlayerPuzzle(
    val questName: String,
    val questDisplayName: String,
    /** The player's entry in the quest: whether it has finished. */
    val questStatus: QuestStatus,
    /**
     * True while the puzzle master has paused the quest: its clocks stop, and
     * the players can't activate, guess, or reveal hints.
     */
    val paused: Boolean,
    /** The quest's clock, with hint penalties. */
    val clock: QuestClock,
    val puzzleName: String,
    val displayName: String,
    val status: StopStatus,
    /** Which stop it is, from 1. */
    val stopNumber: Int,
    /** True if the puzzle master skipped it for the players. */
    val skipped: Boolean = false,
    /** What it asks; null until it's activated. */
    val content: PuzzleContent? = null,
    /** The hints revealed so far, in order. */
    val hints: List<RevealedHint> = emptyList(),
    /** What the next hint would cost; null if it isn't being solved, or has no more hints. */
    val nextHint: NextHint? = null,
    /** The solution, once solved. */
    val solutionText: String? = null,
    /** The stop after this one, once the players have reached it. */
    val next: NextStop? = null,
) {
    val questFinished: Boolean get() = questStatus == QuestStatus.COMPLETED
}

/** What a puzzle shows. */
sealed interface PuzzleContent {
    /** Text, as Markdown. */
    data class Text(
        val markdown: String,
    ) : PuzzleContent

    /** A picture, at its address on the server. */
    data class Image(
        val url: String,
    ) : PuzzleContent

    /** A recording, at its address on the server. */
    data class Audio(
        val url: String,
    ) : PuzzleContent

    /** A film, at its address on the server. */
    data class Video(
        val url: String,
    ) : PuzzleContent
}

data class RevealedHint(
    val text: String,
    /** Seconds it added to the players' time. */
    val penaltySeconds: Long,
)

/** What revealing a puzzle's next hint would cost. */
data class NextHint(
    /** Seconds it would add to the players' time. */
    val penaltySeconds: Long,
    /** True if it gives the solution away, so the player is asked first. */
    val givesSolutionAway: Boolean,
)

/** The stop after one just solved. */
data class NextStop(
    val puzzleName: String,
    val displayName: String,
)

/**
 * Something about a puzzle that changed while it was on screen, for telling
 * TalkBack users what a sighted player sees happen, as the web app does.
 */
sealed interface PuzzleChange {
    data object Paused : PuzzleChange

    data object Resumed : PuzzleChange

    /** Activated: what it asks is showing. */
    data object Started : PuzzleChange

    data class Solved(
        val solution: String?,
    ) : PuzzleChange

    data object Skipped : PuzzleChange

    data class HintRevealed(
        val text: String,
    ) : PuzzleChange

    data object QuestFinished : PuzzleChange

    companion object {
        /** What changed between two views of a puzzle, in the order a player would read them. */
        fun between(
            before: PlayerPuzzle,
            after: PlayerPuzzle,
        ): List<PuzzleChange> =
            buildList {
                if (before.paused != after.paused) add(if (after.paused) Paused else Resumed)
                if (before.status == StopStatus.AWAITING_ACTIVATION && after.status != StopStatus.AWAITING_ACTIVATION) {
                    add(Started)
                }
                if (before.status != StopStatus.COMPLETED && after.status == StopStatus.COMPLETED) {
                    add(if (after.skipped) Skipped else Solved(after.solutionText))
                }
                after.hints.drop(before.hints.size).forEach { add(HintRevealed(it.text)) }
                if (after.questFinished && !before.questFinished) add(QuestFinished)
            }
    }
}

// From the API's schemas.

/** @param server the site, which media paths are on. */
internal fun PlayerPuzzle(
    view: ApiPlayerPuzzle,
    server: String,
): PlayerPuzzle {
    val puzzle = view.puzzle
    return PlayerPuzzle(
        questName = view.quest.questName,
        questDisplayName = view.quest.displayName,
        questStatus = QuestStatus(view.quest.status),
        paused = view.quest.paused,
        clock = QuestClock(view.quest.progress),
        puzzleName = puzzle.puzzleName,
        displayName = puzzle.displayName,
        status = StopStatus(puzzle.status),
        stopNumber = puzzle.stopNumber.toInt(),
        skipped = puzzle.skipped,
        content = puzzleContent(puzzle.type, puzzle.text, server),
        hints = puzzle.hints.map { RevealedHint(it.text, it.timePenaltySeconds) },
        nextHint = puzzle.nextHint?.let(::NextHint),
        solutionText = puzzle.solutionText,
        next = view.next?.let { NextStop(it.puzzleName, it.displayName) },
    )
}

internal fun NextHint(hint: ApiNextHint): NextHint = NextHint(hint.timePenaltySeconds, hint.solutionWarning)

/**
 * What a puzzle shows, or null before it's activated, or if it's of a kind
 * this version of the app doesn't know (version 1 may add kinds).
 */
internal fun puzzleContent(
    type: PuzzleType?,
    text: String?,
    server: String,
): PuzzleContent? {
    if (type == null || text == null) return null
    if (type == PuzzleType.text) return PuzzleContent.Text(text)
    // A path on the site, such as /puzzleData/…; resolved against it.
    val url = resolve(text, server) ?: return null
    return when (type) {
        PuzzleType.image -> PuzzleContent.Image(url)
        PuzzleType.audio -> PuzzleContent.Audio(url)
        PuzzleType.video -> PuzzleContent.Video(url)
        else -> null
    }
}

/**
 * An address in a puzzle, resolved against the site, if it uses one of the
 * schemes allowed; null otherwise.
 */
internal fun resolve(
    address: String,
    server: String,
    schemes: Set<String> = setOf("http", "https"),
): String? =
    try {
        val url = URI(server.trimEnd('/') + "/").resolve(URI(address.trim()))
        url.toString().takeIf { url.scheme?.lowercase() in schemes }
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
