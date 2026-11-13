package com.thepuzzlequest.puzzlequest

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * The sign-in journey in the app, against the real test server. Controls are
 * found by their test tags (such as `sign-in.user-name`), which stay put when
 * wording changes.
 */
@OptIn(ExperimentalTestApi::class)
class SignInUiTest {
    // An empty rule: each test launches the app itself, as a person would.
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun needsTheTestServer() = TestServer.require()

    /** The app, using the test server, starting signed out unless told otherwise. */
    private fun launch(signedOut: Boolean = true): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra(ServerAddress.EXTRA_SERVER_URL, TestServer.url)
                .putExtra(MainActivity.EXTRA_SIGNED_OUT, signedOut)
        return ActivityScenario.launch(intent)
    }

    private fun waitFor(tag: String) = compose.waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 30_000)

    private fun signIn(
        userName: String,
        password: String,
    ) {
        waitFor("sign-in.user-name")
        compose.onNodeWithTag("sign-in.user-name").performTextInput(userName)
        compose.onNodeWithTag("sign-in.password").performTextInput(password)
        compose.onNodeWithTag("sign-in.submit").performClick()
    }

    @Test
    fun signInStaySignedInAndSignOut() {
        launch().use {
            signIn(TestServer.userName, TestServer.password)
            waitFor("signed-in.name")
            compose.onNodeWithTag("signed-in.name").assertTextEquals("Signed in as ${TestServer.displayName}")
        }

        // The next launch picks up the session kept in the Keystore.
        launch(signedOut = false).use {
            waitFor("signed-in.name")
            compose.onNodeWithTag("signed-in.sign-out").performClick()
            waitFor("sign-in.user-name")
        }

        // And stays signed out.
        launch(signedOut = false).use {
            waitFor("sign-in.user-name")
        }
    }

    @Test
    fun wrongPasswordIsExplained() {
        launch().use {
            // A user name of its own, since failed sign-ins are limited per name.
            signIn("nobody-${UUID.randomUUID().toString().take(8)}", "wrong")
            waitFor("sign-in.error")
            compose
                .onNodeWithTag("sign-in.error", useUnmergedTree = false)
                .assertTextEquals("That user name and password don't match an account. Check them and try again.")
        }
    }
}
