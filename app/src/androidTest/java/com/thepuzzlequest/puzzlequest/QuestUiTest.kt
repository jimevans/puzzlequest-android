package com.thepuzzlequest.puzzlequest

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A player's quest screen, against the real test server. Each test makes its
 * own players and quest, starts it, and plays it through the API while the
 * screen is open, so what the screen shows can only come from its live
 * updates.
 */
@OptIn(ExperimentalTestApi::class)
class QuestUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun needsTheTestServer() = TestServer.require()

    /** Signs in as the player and opens the quest from the home screen, once it follows live updates. */
    private fun openQuest(
        questName: String,
        player: String,
    ) {
        compose.signIn(player, TestServer.password)
        compose.waitFor("home.quest.$questName")
        compose.onNodeWithTag("home.quest.$questName").performClick()
        compose.waitFor("quest.summary")
        compose.waitForLiveUpdates("quest.live-updates")
    }

    @Test
    fun theCurrentStopShowsAndPausingIsShownAsItHappens() {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, _) = TestServer.makeQuest(player, admin)
        TestServer.start(questName, admin)

        launchApp().use {
            openQuest(questName, player)
            val stop = compose.onNodeWithTag("quest.current-stop")
            stop.assertTextContains("STOP 1")
            stop.assertTextContains("A puzzle")
            // It never says how many stops there are.
            val words = stop.fetchSemanticsNode().config.toString()
            assertFalse("the stop says $words", words.contains(" of "))
            compose.onNodeWithTag("quest.summary").assertTextContains("Total time")

            TestServer.pause(questName, admin)
            compose.waitFor("quest.paused", 15_000)
            TestServer.resume(questName, admin)
            compose.waitUntilDoesNotExist(hasTestTag("quest.paused"), 15_000)
        }
    }

    @Test
    fun finishingARaceShowsTheFinishTheSolutionAndTheStandings() {
        val player = TestServer.newPlayer()
        val rival = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, displayName) = TestServer.makeQuest(listOf(player, rival), admin)
        TestServer.start(questName, admin)
        // The rival finishes first.
        TestServer.solveCurrentStop(questName, TestServer.token(rival), admin)

        launchApp().use {
            openQuest(questName, player)
            // Racing: nothing of the rival yet.
            assertEquals(0, standingRows())

            TestServer.solveCurrentStop(questName, TestServer.token(player), admin)
            compose.waitFor("quest.finished", 15_000)
            compose.onNodeWithTag("quest.finished").assertTextContains("End of the line")
            compose.onNodeWithTag("quest.finished").assertTextContains(displayName, substring = true)
            compose.onNodeWithTag("quest.current-stop").assertDoesNotExist()

            // Both have finished: the final standings, the rival first, and
            // the player's own entry marked, second.
            val list = compose.onNodeWithTag("quest.list")
            list.performScrollToNode(hasText("Final standings"))
            compose.waitUntil(15_000) { standingRows() == 2 }
            list.performScrollToNode(hasText("You"))
            compose
                .onNode(hasTestTagStartingWith("quest.standing.") and hasText("You", substring = true))
                .assertTextContains("2nd")

            // The solved stop opens to its solution.
            list.performScrollToNode(hasTestTagStartingWith("quest.solved."))
            compose.onNode(hasTestTagStartingWith("quest.solved.")).performClick()
            compose.waitFor("stop.solution", 15_000)
            compose.onNodeWithTag("stop.solution").assertTextEquals("A piano")
        }
    }

    @Test
    fun aQuestResetWhileOpenIsNoLongerAvailableAsItHappens() {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, _) = TestServer.makeQuest(player, admin)
        TestServer.start(questName, admin)

        launchApp().use {
            openQuest(questName, player)
            TestServer.reset(questName, admin)
            compose.waitFor("quest.not-available", 15_000)
            compose.onNode(hasText("Back to your quests")).performClick()
            compose.waitFor("home.list", 15_000)
        }
    }

    @Test
    fun aChallengeArrivesAsItIsSentAndARightAnswerShowsTheConfirmation() {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, _) = TestServer.makeQuest(player, admin)
        TestServer.start(questName, admin)

        launchApp().use {
            openQuest(questName, player)
            TestServer.sendChallenge(questName, player, "What lights the harbour?", admin)
            // It pops up over the quest screen, to answer there.
            compose.waitFor("quest.challenge.message", 15_000)
            compose.onNodeWithTag("quest.challenge.message").assertTextEquals("What lights the harbour?")

            compose.onNodeWithTag("quest.challenge.answer").performScrollTo().performTextReplacement("an organ")
            compose.onNodeWithTag("quest.challenge.answer.send").performScrollTo().performClick()
            compose.waitFor("quest.challenge.error", 15_000)
            compose.onNodeWithTag("quest.challenge.error").assertTextContains("not the answer", substring = true)

            // Keywords, as puzzle answers: other words around it are fine.
            compose
                .onNodeWithTag(
                    "quest.challenge.answer",
                ).performScrollTo()
                .performTextReplacement("It's the Lighthouse!")
            compose.onNodeWithTag("quest.challenge.answer.send").performScrollTo().performClick()
            compose.waitForInside("quest.challenge.confirmation", 15_000)
            compose
                .onNodeWithTag("quest.challenge.confirmation", useUnmergedTree = true)
                .assertTextEquals("Show this to the bartender")
            compose.onNodeWithTag("quest.challenge.answered").assertTextContains("Show this to the staff")
            compose.onNodeWithTag("quest.challenge.answer").assertDoesNotExist()

            // Put away, a row brings the confirmation back for the staff.
            compose.onNodeWithTag("quest.messages.done").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("quest.challenge.answered"), 15_000)
            compose.waitFor("quest.challenge-row", 15_000)
            compose.onNodeWithTag("quest.challenge-row").assertTextContains("Challenge answered")
            compose.onNodeWithTag("quest.challenge-row").performClick()
            compose.waitForInside("quest.challenge.confirmation", 15_000)
            compose.onNodeWithTag("quest.messages.done").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("quest.challenge.answered"), 15_000)

            // Hidden, the row is gone for good.
            compose.onNodeWithTag("quest.challenge-row.hide").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("quest.challenge-row"), 15_000)

            // The challenge is in the history, and opens to its confirmation.
            compose.onNodeWithTag("quest.messages-row").performClick()
            compose.waitFor("quest.messages", 15_000)
            compose.onNode(hasText("What lights the harbour?", substring = true)).performClick()
            compose.waitForInside("quest.challenge.confirmation", 15_000)
        }
    }

    private fun standingRows() =
        compose
            .onAllNodes(hasTestTagStartingWith("quest.standing."))
            .fetchSemanticsNodes()
            .size
}

/** A node whose test tag starts with [prefix], such as each row of a list. */
private fun hasTestTagStartingWith(prefix: String) =
    androidx.compose.ui.test.SemanticsMatcher("TestTag starts with '$prefix'") { node ->
        node.config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
            ?.startsWith(prefix) == true
    }
