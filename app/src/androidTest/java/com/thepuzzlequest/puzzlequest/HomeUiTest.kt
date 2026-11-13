package com.thepuzzlequest.puzzlequest

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A player's home screen, against the real test server. Each test signs up a
 * new player, so it starts with no quests.
 */
class HomeUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun needsTheTestServer() = TestServer.require()

    @Test
    fun aQuestAppearsAsItStarts() {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, displayName) = TestServer.makeQuest(player, admin)

        launchApp().use {
            compose.signIn(player, TestServer.password)
            compose.waitFor("home.empty")
            compose.onNodeWithTag("home.empty").assertTextEquals("You have no quests yet.")

            // Once live updates are connected (and the list fetched since),
            // start the quest. It can then only appear through the live update
            // (questsChanged): nothing else fetches the list.
            compose.waitUntil(30_000) {
                compose
                    .onNodeWithTag("home.live-updates")
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.StateDescription) == "connected"
            }
            TestServer.start(questName, admin)
            compose.waitFor("home.quest.$questName", 5_000)
            val row = compose.onNodeWithTag("home.quest.$questName")
            row.assertTextContains(displayName)
            row.assertTextContains("In progress")
            compose.onNodeWithTag("home.empty").assertDoesNotExist()

            row.performClick()
            // It opens to the quest's own screen.
            compose.waitFor("quest.current-stop", 30_000)
            compose.onNodeWithTag("quest.title").assertTextEquals(displayName)
            compose.onNodeWithTag("quest.back").performClick()

            compose.waitFor("home.list", 10_000)
            compose.signOutFromHome()
            compose.waitFor("sign-in.user-name")
        }
    }

    @Test
    fun theVerificationEmailCanBeSentAgain() {
        val player = TestServer.newPlayer()
        launchApp().use {
            compose.signIn(player, TestServer.password)
            compose.waitFor("email-reminder.send")
            compose.onNodeWithTag("email-reminder.send").performClick()
            compose.waitFor("email-reminder.result")
            compose.onNodeWithTag("email-reminder.result").assertTextEquals("Sent. Check your inbox.")
            compose.onNodeWithTag("email-reminder.send").assertIsNotEnabled()
        }
    }
}
