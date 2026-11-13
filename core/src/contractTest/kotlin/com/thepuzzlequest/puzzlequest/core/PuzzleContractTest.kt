package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** A player's puzzle screen, against the test server (see TestSite). */
class PuzzleContractTest {
    @Before
    fun needsTheTestServer() {
        assumeTrue("Set PQ_TEST_SERVER_URL (see scripts/start-test-server.sh)", TestSite.url != null)
    }

    private class Started(
        val api: PuzzleQuestApi,
        val admin: String,
        val questName: String,
        val puzzleName: String,
    )

    /** A started quest of [stops] stops for a new player, and its first stop. */
    private suspend fun started(stops: Int = 1): Started {
        val player = TestSite.newPlayer()
        val admin = TestSite.adminToken()
        val questName = TestSite.makeQuest(player.userName, admin, stops)
        TestSite.start(questName, admin)
        val stop =
            player.api
                .playerQuest(questName)!!
                .entry.currentStop!!
        return Started(player.api, admin, questName, stop.puzzleName)
    }

    /** Why an action was refused, or null if it wasn't. */
    private suspend fun refusal(action: suspend () -> Unit): PuzzleActionError? =
        try {
            action()
            null
        } catch (error: ApiError) {
            PuzzleActionError.of(error)
        }

    // runBlocking, not runTest: these wait on a real server, in real time.

    @Test
    fun `a puzzle waiting to be found shows nothing of itself, and a wrong code is refused`() =
        runBlocking {
            val stop = started()
            val (api, questName, puzzleName) = Triple(stop.api, stop.questName, stop.puzzleName)
            val waiting = api.playerPuzzle(questName, puzzleName)!!
            assertEquals(StopStatus.AWAITING_ACTIVATION, waiting.status)
            assertEquals(1, waiting.stopNumber)
            assertEquals("A puzzle", waiting.displayName)
            assertNull(waiting.content)
            assertNull(waiting.nextHint)

            assertEquals(
                PuzzleActionError.WRONG_CODE,
                refusal { api.activatePuzzle(questName, puzzleName, "not-the-code", null) },
            )
            // Any letter case.
            val code = TestSite.activationCode(questName, puzzleName, stop.admin)
            api.activatePuzzle(questName, puzzleName, code.lowercase(), null)
            val puzzle = api.playerPuzzle(questName, puzzleName)!!
            assertEquals(StopStatus.IN_PROGRESS, puzzle.status)
            assertEquals(PuzzleContent.Text("What has *keys*?"), puzzle.content)
            assertEquals(NextHint(60, givesSolutionAway = false), puzzle.nextHint)
        }

    @Test
    fun `hints add their penalties, a wrong guess is refused, and the right one solves it`() =
        runBlocking {
            val stop = started(stops = 2)
            val api = stop.api
            val code = TestSite.activationCode(stop.questName, stop.puzzleName, stop.admin)
            api.activatePuzzle(stop.questName, stop.puzzleName, code, null)
            api.revealHint(stop.questName, stop.puzzleName, null)
            var puzzle = api.playerPuzzle(stop.questName, stop.puzzleName)!!
            assertEquals(listOf(RevealedHint("It makes music", 60)), puzzle.hints)
            assertEquals(NextHint(300, givesSolutionAway = true), puzzle.nextHint)
            assertEquals(60_000L, puzzle.clock.penaltyMs)

            assertEquals(
                PuzzleActionError.WRONG_ANSWER,
                refusal { api.attemptSolution(stop.questName, stop.puzzleName, "an organ", null) },
            )
            // Letter case and punctuation don't matter.
            api.attemptSolution(stop.questName, stop.puzzleName, "A Piano!", null)
            puzzle = api.playerPuzzle(stop.questName, stop.puzzleName)!!
            assertEquals(StopStatus.COMPLETED, puzzle.status)
            assertEquals("A piano", puzzle.solutionText)
            assertNull(puzzle.nextHint)
            assertFalse(puzzle.questFinished)
            val next = puzzle.next!!
            assertEquals("Another puzzle", next.displayName)
            val following = api.playerPuzzle(stop.questName, next.puzzleName)!!
            assertEquals(2, following.stopNumber)
            assertEquals(StopStatus.AWAITING_ACTIVATION, following.status)
        }

    @Test
    fun `while the quest is paused, actions are refused as paused`() =
        runBlocking {
            val stop = started()
            TestSite.pause(stop.questName, stop.admin)
            assertTrue(stop.api.playerPuzzle(stop.questName, stop.puzzleName)!!.paused)
            val code = TestSite.activationCode(stop.questName, stop.puzzleName, stop.admin)
            assertEquals(
                PuzzleActionError.PAUSED,
                refusal { stop.api.activatePuzzle(stop.questName, stop.puzzleName, code, null) },
            )
        }

    @Test
    fun `a stop not yet reached, or after a reset, isn't available`() =
        runBlocking {
            val stop = started()
            assertNull(stop.api.playerPuzzle(stop.questName, "no-such-puzzle"))
            TestSite.reset(stop.questName, stop.admin)
            assertNull(stop.api.playerPuzzle(stop.questName, stop.puzzleName))
        }
}
