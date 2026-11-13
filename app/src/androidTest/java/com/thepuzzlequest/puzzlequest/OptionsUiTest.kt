package com.thepuzzlequest.puzzlequest

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.URI

/**
 * Choosing the server in Options. These launch the app without
 * PQ_SERVER_URL, so it uses the server Options says (Production, until a
 * test chooses the test server). They never sign in to Production, or ask
 * it anything: a new install has no session there to check.
 */
@OptIn(ExperimentalTestApi::class)
class OptionsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before
    fun needsTheTestServer() = TestServer.require()

    /** How the app names the test server, such as localhost:3970. */
    private val serverName get() = URI(TestServer.url).let { "${it.host}:${it.port}" }

    @Test
    fun optionsStartsWithProduction() {
        launchApp(fixedServer = false).use {
            compose.waitFor("sign-in.user-name")
            // Production isn't named on the sign-in screen.
            compose.onNodeWithTag("sign-in.server").assertDoesNotExist()

            compose.onNodeWithTag("sign-in.options").performClick()
            compose.waitFor("options.current", 10_000)
            compose.onNodeWithTag("options.current").assertTextContains("thepuzzlequest.com")
            compose.onNodeWithTag("options.server.production").assertIsSelected()
            compose.onNodeWithTag("options.server.custom").assertIsNotSelected()
            // A debug build offers the computer's server.
            compose.onNodeWithTag("options.server.this-computer").assertExists()

            compose.onNodeWithTag("options.close").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("options.current"), 10_000)
            compose.onNodeWithTag("sign-in.user-name").assertExists()
        }
    }

    @Test
    fun aCustomServerIsCheckedNamedAndSignedInTo() {
        launchApp(fixedServer = false).use {
            chooseTestServer()
            compose.onNodeWithTag("sign-in.server").assertTextContains("Signing in to $serverName")

            compose.signIn(TestServer.userName, TestServer.password)
            compose.waitFor("home.list")
        }
    }

    @Test
    fun switchingServersAsksThenSignsOut() {
        launchApp(fixedServer = false).use {
            chooseTestServer()
            compose.signIn(TestServer.userName, TestServer.password)
            compose.waitFor("home.list")

            openOptionsFromHome()
            compose.onNodeWithTag("options.server.production").performClick()
            compose.waitFor("options.cancel-switch", 10_000)
            // Staying changes nothing.
            compose.onNodeWithTag("options.cancel-switch").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("options.cancel-switch"), 10_000)
            compose.onNodeWithTag("options.server.custom").assertIsSelected()
            compose.onNodeWithTag("options.close").performClick()
            compose.waitFor("home.list", 10_000)

            openOptionsFromHome()
            compose.onNodeWithTag("options.server.production").performClick()
            compose.waitFor("options.confirm-switch", 10_000)
            compose.onNodeWithTag("options.confirm-switch").performClick()
            compose.waitFor("sign-in.user-name")
            compose.onNodeWithTag("sign-in.server").assertDoesNotExist()
            compose.onNodeWithTag("options.current").assertDoesNotExist()
        }
    }

    /**
     * From the sign-in screen, chooses Custom in Options and types the test
     * server's address, which the app checks before switching to it.
     */
    private fun chooseTestServer() {
        compose.waitFor("sign-in.user-name")
        compose.onNodeWithTag("sign-in.options").performClick()
        compose.waitFor("options.server.custom", 10_000)
        compose.onNodeWithTag("options.server.custom").performClick()
        compose.waitFor("options.custom-address", 10_000)
        compose.onNodeWithTag("options.custom-address").performTextInput(TestServer.url)
        // Scrolled into view above the keyboard, which covers it on a small screen.
        compose.onNodeWithTag("options.use-custom").performScrollTo().performClick()
        // Switching rebuilds the screens for the new server, closing Options.
        compose.waitUntilDoesNotExist(hasTestTag("options.server.custom"), 30_000)
        // Options can close a frame before the old server's screens are
        // replaced; only the new server's sign-in screen names it, so wait
        // for that before typing anything.
        compose.waitFor("sign-in.server")
    }

    /** Opens Options from the home screen's account menu. */
    private fun openOptionsFromHome() {
        compose.onNodeWithTag("home.account").performClick()
        compose.waitFor("home.options", 10_000)
        compose.onNodeWithTag("home.options").performClick()
        compose.waitFor("options.current", 10_000)
    }
}
