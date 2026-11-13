package com.thepuzzlequest.puzzlequest

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The sign-in journey in the app, against the real test server. Controls are
 * found by their test tags (such as `sign-in.user-name`), which stay put when
 * wording changes.
 */
class SignInUiTest {
    // An empty rule: each test launches the app itself, as a person would.
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun needsTheTestServer() = TestServer.require()

    @Test
    fun signInStaySignedInAndSignOut() {
        launchApp().use {
            compose.signIn(TestServer.userName, TestServer.password)
            compose.waitFor("home.list")
            compose.onNodeWithText("Your quests").assertExists()
        }

        // The next launch picks up the session kept in the Keystore.
        launchApp(signedOut = false).use {
            compose.waitFor("home.list")
            // Signing out is in the account menu, which says who is signed in.
            compose.onNodeWithTag("home.account").performClick()
            compose.waitFor("home.signed-in-as", 10_000)
            compose.onNodeWithTag("home.signed-in-as").assertTextEquals("Signed in as ${TestServer.displayName}")
            compose.onNodeWithTag("home.sign-out").performClick()
            compose.waitFor("sign-in.user-name")
        }

        // And stays signed out.
        launchApp(signedOut = false).use {
            compose.waitFor("sign-in.user-name")
        }
    }

    @Test
    fun wrongPasswordIsExplained() {
        launchApp().use {
            // A user name of its own, since failed sign-ins are limited per name.
            compose.signIn(TestServer.unique("nobody"), "wrong")
            compose.waitFor("sign-in.error")
            compose
                .onNodeWithTag("sign-in.error")
                .assertTextEquals("That user name and password don't match an account. Check them and try again.")
        }
    }

    @Test
    fun signingUpIsOnTheWebsite() {
        launchApp().use {
            compose.waitFor("sign-in.sign-up")
            compose.onNodeWithTag("sign-in.sign-up").assertTextEquals("New to PuzzleQuest? Sign up on the website")
        }
    }

    @Test
    fun staffAndAdministratorsAreSentToTheWebsite() {
        launchApp().use {
            compose.signIn(TestServer.adminUserName, TestServer.adminPassword)
            compose.waitFor("website.note")
            compose.onNodeWithTag("website.open").assertExists()
            compose.onNodeWithText("Your quests").assertDoesNotExist()

            compose.onNodeWithTag("website.sign-out").performClick()
            compose.waitFor("sign-in.user-name")
        }
    }
}
