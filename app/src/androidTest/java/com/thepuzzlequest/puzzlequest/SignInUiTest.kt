package com.thepuzzlequest.puzzlequest

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
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
    fun signUpThenDeleteTheAccount() {
        launchApp().use {
            compose.waitFor("sign-in.sign-up")
            compose.onNodeWithTag("sign-in.sign-up").assertTextEquals("New to PuzzleQuest? Sign up")
            compose.onNodeWithTag("sign-in.sign-up").performClick()

            // What's missing is said at its field, before anything is sent.
            compose.waitFor("sign-up.submit")
            compose.onNodeWithTag("sign-up.submit").performScrollTo().performClick()
            compose.onNodeWithTag("sign-up.userName").performScrollTo()
            compose.waitUntil(10_000) {
                compose
                    .onAllNodes(
                        hasTestTag("sign-up.userName") and
                            SemanticsMatcher.expectValue(SemanticsProperties.Error, "Required."),
                    ).fetchSemanticsNodes()
                    .size == 1
            }

            // Filled in, it signs up and goes straight to the new player's home.
            val userName = TestServer.unique("signup")
            compose.onNodeWithTag("sign-up.userName").performScrollTo().performTextReplacement(userName)
            compose.onNodeWithTag("sign-up.password").performScrollTo().performTextReplacement(TestServer.password)
            compose
                .onNodeWithTag("sign-up.confirmPassword")
                .performScrollTo()
                .performTextReplacement(TestServer.password)
            compose.onNodeWithTag("sign-up.displayName").performScrollTo().performTextReplacement("Player $userName")
            compose
                .onNodeWithTag("sign-up.sms-consent")
                .performScrollTo()
                .assert(hasText("Checking this box gives The PuzzleQuest Game consent", substring = true))
            compose.typeThenTap("sign-up.email", "$userName@example.com", "sign-up.submit")
            compose.waitFor("home.list")
            compose.waitFor("email-reminder.send")

            // Deleting is in the account menu, and needs the right password.
            compose.onNodeWithTag("home.account").performClick()
            compose.waitFor("home.delete-account", 10_000)
            compose.onNodeWithTag("home.delete-account").performClick()
            compose.waitFor("delete-account.password")
            compose.onNodeWithTag("delete-account.delete").assertIsNotEnabled()
            compose.typeInDialogThenTap("delete-account.password", "not the password", "delete-account.delete")
            compose.waitFor("delete-account.error")
            compose
                .onNodeWithTag("delete-account.error")
                .assertTextEquals("That isn't your password. Check it and try again.")

            compose.typeInDialogThenTap("delete-account.password", TestServer.password, "delete-account.delete")
            compose.waitFor("sign-in.user-name")

            // Gone: it no longer signs in.
            compose.signIn(userName, TestServer.password)
            compose.waitFor("sign-in.error")
            compose
                .onNodeWithTag("sign-in.error")
                .assertTextEquals("That user name and password don't match an account. Check them and try again.")
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

/**
 * Types into a dialog's field (replacing what's there), closes the keyboard,
 * then taps one of the dialog's buttons, which are always in view.
 */
private fun ComposeTestRule.typeInDialogThenTap(
    field: String,
    text: String,
    button: String,
) {
    onNodeWithTag(field).performTextReplacement(text)
    waitForIdle()
    Espresso.closeSoftKeyboard()
    waitForIdle()
    onNodeWithTag(button).performClick()
}
