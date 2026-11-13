package com.thepuzzlequest.puzzlequest

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A player's puzzle screen, against the real test server. The tests type
 * each code: Google's scanner is a screen of Google Play services', which the
 * tests can't drive, and the emulator has no QR code to point at.
 */
@OptIn(ExperimentalTestApi::class)
class PuzzleUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun needsTheTestServer() = TestServer.require()

    /** Signs in as the player, opens the quest, and opens its current stop, once it follows live updates. */
    private fun openCurrentStop(
        questName: String,
        player: String,
    ) {
        compose.signIn(player, TestServer.password)
        compose.waitFor("home.quest.$questName")
        compose.onNodeWithTag("home.quest.$questName").performClick()
        compose.waitFor("quest.current-stop")
        compose.onNodeWithTag("quest.current-stop").performClick()
        compose.waitFor("puzzle.header")
        compose.waitForLiveUpdates("puzzle.live-updates")
    }

    /** Types into a field, replacing what's there, and sends it with its button. */
    private fun send(
        text: String,
        tag: String,
    ) {
        compose.typeThenTap(tag, text, "$tag.send")
    }

    @Test
    fun findingSolvingAndGoingOnToTheNextStop() {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, _) = TestServer.makeQuest(player, admin, stops = 2)
        TestServer.start(questName, admin)
        val puzzleName = TestServer.currentStop(questName, TestServer.token(player))
        val code = TestServer.activationCode(questName, puzzleName, admin)

        launchApp().use {
            openCurrentStop(questName, player)
            val header = compose.onNodeWithTag("puzzle.header")
            header.assertTextContains("STOP 1")
            header.assertTextContains("A puzzle")
            // Nothing of the puzzle until it's found.
            compose.onNode(hasText("What has keys?")).assertDoesNotExist()

            // A wrong code, then the right one, in any letter case.
            send("not-the-code", "puzzle.activation-code")
            compose.waitFor("puzzle.action-error", 15_000)
            compose
                .onNodeWithTag(
                    "puzzle.action-error",
                ).assertTextContains("isn't this puzzle's code", substring = true)
            send(code.lowercase(), "puzzle.activation-code")
            // The Markdown is drawn, not shown as typed.
            compose.waitUntilExactlyOneExists(hasText("What has keys?"), 15_000)

            // A hint, at its cost; the next gives the solution away, so it asks.
            val revealHint = compose.onNodeWithTag("puzzle.reveal-hint")
            revealHint.assert(hasContentDescription("1 minute", substring = true))
            revealHint.performScrollTo().performClick()
            compose.waitFor("puzzle.hint.1", 15_000)
            compose.onNodeWithTag("puzzle.hint.1").assertTextContains("It makes music")
            compose.onNodeWithTag("puzzle.reveal-hint").performScrollTo().performClick()
            compose.waitFor("puzzle.keep-trying", 15_000)
            compose.onNodeWithTag("puzzle.keep-trying").performClick()
            compose.onNodeWithTag("puzzle.hint.2").assertDoesNotExist()

            send("an organ", "puzzle.answer")
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasTestTag("puzzle.action-error") and hasText("not the answer", substring = true))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            send("A Piano", "puzzle.answer")
            compose.waitFor("puzzle.solution", 15_000)
            compose.onNodeWithTag("puzzle.solution").assertTextEquals("A piano")

            val next = compose.onNodeWithTag("puzzle.next-stop")
            next.assertTextContains("Another puzzle", substring = true)
            next.performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasTestTag("puzzle.header") and hasText("STOP 2"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.waitFor("puzzle.activation-code", 15_000)
        }
    }

    @Test
    fun aQuestResetWhileAPuzzleIsOpenIsNoLongerAvailableAsItHappens() {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, _) = TestServer.makeQuest(player, admin)
        TestServer.start(questName, admin)

        launchApp().use {
            openCurrentStop(questName, player)
            TestServer.reset(questName, admin)
            compose.waitFor("puzzle.not-available", 15_000)
            // The quest screen beneath hears of it too.
            compose.onNode(hasText("Back to the quest")).performClick()
            compose.waitFor("quest.not-available", 15_000)
        }
    }

    @Test
    fun pausingAndSolvingElsewhereShowAsTheyHappenThroughToTheFinish() {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, _) = TestServer.makeQuest(player, admin)
        TestServer.start(questName, admin)
        val token = TestServer.token(player)

        launchApp().use {
            openCurrentStop(questName, player)
            TestServer.pause(questName, admin)
            compose.waitFor("puzzle.paused", 15_000)
            compose.onNodeWithTag("puzzle.activation-code").assertIsNotEnabled()
            TestServer.resume(questName, admin)
            compose.waitUntilDoesNotExist(hasTestTag("puzzle.paused"), 15_000)

            // Solved elsewhere, as by a teammate on another phone.
            TestServer.solveCurrentStop(questName, token, admin)
            compose.waitFor("puzzle.finished", 15_000)
            compose.onNodeWithTag("puzzle.finished").assertTextContains("End of the line")
            compose.onNodeWithTag("puzzle.solution").assertTextEquals("A piano")
            compose.onNodeWithTag("puzzle.back-to-quest").performScrollTo().performClick()
            compose.waitFor("quest.finished", 15_000)
        }
    }
}
