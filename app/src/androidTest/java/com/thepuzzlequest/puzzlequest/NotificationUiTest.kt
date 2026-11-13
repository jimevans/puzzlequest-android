package com.thepuzzlequest.puzzlequest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.thepuzzlequest.puzzlequest.api.infrastructure.ClientException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Offering notifications on a quest, and turning them on, against the real
 * test server. Android asks a player once per install, and the emulator
 * remembers the answer between runs, so the offer is checked only while it
 * hasn't been given yet; these run in name order, so the offer is checked
 * before the other test grants it. The phone's token is the test's own
 * (PQ_FAKE_PUSH_TOKEN), since an emulator on CI can't get one from Firebase;
 * that Firebase gives one, and delivers, is checked on a phone.
 */
@OptIn(ExperimentalTestApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NotificationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun needsTheTestServer() = TestServer.require()

    /** A new player, with a quest started for them; returns the player, quest and administrator's token. */
    private fun newPlayerWithQuest(): Triple<String, String, String> {
        val player = TestServer.newPlayer()
        val admin = TestServer.adminToken()
        val (questName, _) = TestServer.makeQuest(player, admin)
        TestServer.start(questName, admin)
        return Triple(player, questName, admin)
    }

    private fun openQuest(questName: String) {
        compose.waitFor("home.quest.$questName")
        compose.onNodeWithTag("home.quest.$questName").performClick()
        compose.waitFor("quest.current-stop")
    }

    private fun allowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @Test
    fun aQuestOffersNotificationsUntilThePlayerSaysNotNow() {
        assumeTrue(
            "Android asks about notifications from Android 13",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
        assumeTrue("this emulator has already allowed notifications", !allowed())
        val (player, questName, _) = newPlayerWithQuest()
        launchApp().use {
            compose.signIn(player, TestServer.password)
            openQuest(questName)
            compose.waitFor("quest.notifications.not-now", 10_000)
            compose.onNodeWithTag("quest.notifications.turn-on").assertExists()
            compose.onNodeWithTag("quest.notifications.not-now").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("quest.notifications"), 10_000)
        }
        // Not offered on this quest again, even after the app starts again.
        launchApp(signedOut = false).use {
            openQuest(questName)
            compose.onNodeWithTag("quest.notifications").assertDoesNotExist()
        }
    }

    @Test
    fun turningNotificationsOnLetsThePuzzleMasterReachAPlayerWhoCantBeTexted() {
        val (player, questName, admin) = newPlayerWithQuest()
        // Before signing in on this phone, a new player, who has no phone
        // number, can't be reached.
        try {
            TestServer.sendMessage(questName, player, "Hello", admin)
            fail("a player with no way to be reached was sent a message")
        } catch (_: ClientException) {
        }

        // Allowed as the player would in Android's question, which the test
        // can't reach: tests can grant a permission, but not tap a dialog of
        // the system's.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        launchApp().use {
            compose.signIn(player, TestServer.password)
            openQuest(questName)
            compose.onNodeWithTag("quest.notifications").assertDoesNotExist()

            // Registered with the session: now the message has somewhere to go.
            var reached = false
            repeat(20) {
                if (reached) return@repeat
                try {
                    TestServer.sendMessage(questName, player, "Hello", admin)
                    reached = true
                } catch (_: ClientException) {
                    Thread.sleep(1_000)
                }
            }
            assertTrue("the phone wasn't registered for the player's notifications", reached)
            // And it pops up, as on a phone.
            compose.waitUntilExactlyOneExists(hasText("Hello"), 15_000)
        }
    }
}
