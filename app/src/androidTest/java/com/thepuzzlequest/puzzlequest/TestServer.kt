package com.thepuzzlequest.puzzlequest

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.thepuzzlequest.puzzlequest.api.apis.PuzzlesApi
import com.thepuzzlequest.puzzlequest.api.apis.QuestsApi
import com.thepuzzlequest.puzzlequest.api.apis.RunningQuestsApi
import com.thepuzzlequest.puzzlequest.api.apis.SessionsApi
import com.thepuzzlequest.puzzlequest.api.apis.UsersApi
import com.thepuzzlequest.puzzlequest.api.models.CreatePuzzleRequest
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequest
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequestEntrantsInner
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequestPuzzlesInner
import com.thepuzzlequest.puzzlequest.api.models.CreateUserRequest
import com.thepuzzlequest.puzzlequest.api.models.EntrantKind
import com.thepuzzlequest.puzzlequest.api.models.SignInRequest
import com.thepuzzlequest.puzzlequest.core.ApiJson
import com.thepuzzlequest.puzzlequest.core.PuzzleQuestApi
import org.junit.Assume.assumeTrue
import java.util.UUID

/**
 * The real server started by scripts/start-test-server.sh. The build passes
 * its details from the environment (.test-server/env) as instrumentation
 * arguments (app/build.gradle.kts); without them, tests that need it are
 * skipped.
 */
object TestServer {
    private val arguments get() = InstrumentationRegistry.getArguments()

    /** Skips the test unless the test server is named. */
    fun require() {
        assumeTrue(
            "Load .test-server/env into the environment (see scripts/start-test-server.sh)",
            arguments.getString("PQ_TEST_SERVER_URL") != null,
        )
    }

    val url: String get() = arguments.getString("PQ_TEST_SERVER_URL")!!
    val userName: String get() = arguments.getString("PQ_TEST_USER_NAME")!!
    val password: String get() = arguments.getString("PQ_TEST_PASSWORD")!!
    val displayName: String get() = arguments.getString("PQ_TEST_DISPLAY_NAME") ?: userName
    val adminUserName: String get() = arguments.getString("PQ_TEST_ADMIN_USER_NAME")!!
    val adminPassword: String get() = arguments.getString("PQ_TEST_ADMIN_PASSWORD")!!

    // Setting up through the API, with the generated client (its calls block,
    // which is fine on the tests' own thread).

    private val baseUrl get() = "$url/api/v1"
    private val http get() = PuzzleQuestApi.defaultHttpClient

    init {
        ApiJson.setUp()
    }

    /** A name no one has used, for a user, puzzle, or quest. */
    fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    /**
     * Signs up a new player, who has no quests, and returns their user name.
     * They sign in with the test player's password.
     */
    fun newPlayer(): String {
        val userName = unique("player")
        UsersApi(baseUrl, http).createUser(
            CreateUserRequest(userName, "$userName@example.com", password, displayName = "Player $userName"),
        )
        return userName
    }

    /** Signs in as the administrator. */
    fun adminToken(): String =
        SessionsApi(baseUrl, http)
            .signIn(SignInRequest(adminUserName, adminPassword, SignInRequest.Client.native))
            .token!!

    /**
     * As the administrator, makes a quest of one stop for a player to play on
     * their own, and returns its name and display name. It isn't started.
     */
    fun makeQuest(
        player: String,
        admin: String,
    ): Pair<String, String> {
        val puzzleName = unique("puzzle")
        PuzzlesApi(baseUrl, http).apply { accessTokenProvider = { admin } }.createPuzzle(
            CreatePuzzleRequest(puzzleName, "piano", "A piano", displayName = "A puzzle", text = "What has keys?"),
        )
        val questName = unique("quest")
        val displayName = "Quest $questName"
        QuestsApi(baseUrl, http).apply { accessTokenProvider = { admin } }.createQuest(
            CreateQuestRequest(
                questName,
                displayName,
                listOf(CreateQuestRequestEntrantsInner(EntrantKind.player, player)),
                puzzles = listOf(CreateQuestRequestPuzzlesInner(puzzleName)),
            ),
        )
        return questName to displayName
    }

    /** As the administrator, starts a quest for its players. */
    fun start(
        questName: String,
        admin: String,
    ) {
        RunningQuestsApi(baseUrl, http).apply { accessTokenProvider = { admin } }.startQuest(questName)
    }
}

/**
 * The app, using the test server, starting signed out unless told otherwise.
 *
 * @param fixedServer whether to set the server for the run (PQ_SERVER_URL);
 *   without it, the app uses the server Options says, which starts as a new
 *   install's (Production).
 */
fun launchApp(
    signedOut: Boolean = true,
    fixedServer: Boolean = true,
): ActivityScenario<MainActivity> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent =
        Intent(context, MainActivity::class.java)
            .putExtra(AppServer.EXTRA_SIGNED_OUT, signedOut)
            // Shows the home screen's live-update state to the tests.
            .putExtra(MainActivity.EXTRA_UI_TESTING, true)
    if (fixedServer) {
        intent.putExtra(AppServer.EXTRA_SERVER_URL, TestServer.url)
    } else {
        intent.putExtra(AppServer.EXTRA_RESET_SERVER, true)
    }
    return ActivityScenario.launch(intent)
}

/** Waits up to 30 seconds (a screen fetching from the server) for one node with the tag. */
@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.waitFor(
    tag: String,
    timeoutMillis: Long = 30_000,
) = waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis)

fun ComposeTestRule.signIn(
    userName: String,
    password: String,
) {
    waitFor("sign-in.user-name")
    onNodeWithTag("sign-in.user-name").performTextInput(userName)
    onNodeWithTag("sign-in.password").performTextInput(password)
    // On a small screen, the keyboard covers the button: scroll it into view
    // above the keyboard first, as a person would, or the tap lands on the
    // keyboard.
    onNodeWithTag("sign-in.submit").performScrollTo().performClick()
}

/** Signs out through the home screen's account menu. */
fun ComposeTestRule.signOutFromHome() {
    onNodeWithTag("home.account").performClick()
    waitFor("home.sign-out", 10_000)
    onNodeWithTag("home.sign-out").performClick()
}
