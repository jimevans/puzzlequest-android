package com.thepuzzlequest.puzzlequest

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.thepuzzlequest.puzzlequest.api.apis.PlayingApi
import com.thepuzzlequest.puzzlequest.api.apis.PuzzlesApi
import com.thepuzzlequest.puzzlequest.api.apis.QuestsApi
import com.thepuzzlequest.puzzlequest.api.apis.RunningQuestsApi
import com.thepuzzlequest.puzzlequest.api.apis.SessionsApi
import com.thepuzzlequest.puzzlequest.api.apis.UsersApi
import com.thepuzzlequest.puzzlequest.api.models.ActivatePuzzleRequest
import com.thepuzzlequest.puzzlequest.api.models.AttemptSolutionRequest
import com.thepuzzlequest.puzzlequest.api.models.CreatePuzzleRequest
import com.thepuzzlequest.puzzlequest.api.models.CreatePuzzleRequestHintsInner
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequest
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequestEntrantsInner
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequestPuzzlesInner
import com.thepuzzlequest.puzzlequest.api.models.CreateUserRequest
import com.thepuzzlequest.puzzlequest.api.models.EntrantKind
import com.thepuzzlequest.puzzlequest.api.models.PushTarget
import com.thepuzzlequest.puzzlequest.api.models.SendQuestTextMessageRequest
import com.thepuzzlequest.puzzlequest.api.models.SignInRequest
import com.thepuzzlequest.puzzlequest.api.models.StopStatus
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
        stops: Int = 1,
    ): Pair<String, String> = makeQuest(listOf(player), admin, stops)

    /**
     * As the administrator, makes a quest, each player playing it on their
     * own (a race, for more than one), and returns its name and display name.
     * It isn't started. Its first stop is "A puzzle", the others "Another
     * puzzle"; each asks "What has *keys*?", is solved by "piano", and has two
     * hints, the second giving the solution away.
     */
    fun makeQuest(
        players: List<String>,
        admin: String,
        stops: Int = 1,
    ): Pair<String, String> {
        val puzzles = PuzzlesApi(baseUrl, http).apply { accessTokenProvider = { admin } }
        val puzzleNames =
            (1..stops).map { stop ->
                unique("puzzle").also { puzzleName ->
                    puzzles.createPuzzle(
                        CreatePuzzleRequest(
                            puzzleName,
                            "piano",
                            "A piano",
                            displayName = if (stop == 1) "A puzzle" else "Another puzzle",
                            text = "What has *keys*?",
                            hints =
                                listOf(
                                    CreatePuzzleRequestHintsInner("It makes music", timePenaltySeconds = 60),
                                    CreatePuzzleRequestHintsInner(
                                        "A piano",
                                        solutionWarning = true,
                                        timePenaltySeconds = 300,
                                    ),
                                ),
                        ),
                    )
                }
            }
        val questName = unique("quest")
        val displayName = "Quest $questName"
        QuestsApi(baseUrl, http).apply { accessTokenProvider = { admin } }.createQuest(
            CreateQuestRequest(
                questName,
                displayName,
                players.map { CreateQuestRequestEntrantsInner(EntrantKind.player, it) },
                puzzles = puzzleNames.map { CreateQuestRequestPuzzlesInner(it) },
            ),
        )
        return questName to displayName
    }

    /** As the administrator, starts a quest for its players. */
    fun start(
        questName: String,
        admin: String,
    ) {
        running(admin).startQuest(questName)
    }

    fun pause(
        questName: String,
        admin: String,
    ) {
        running(admin).pauseQuest(questName)
    }

    fun resume(
        questName: String,
        admin: String,
    ) {
        running(admin).resumeQuest(questName)
    }

    fun reset(
        questName: String,
        admin: String,
    ) {
        running(admin).resetQuest(questName)
    }

    private fun running(admin: String) = RunningQuestsApi(baseUrl, http).apply { accessTokenProvider = { admin } }

    /** Signs in as a player made by [newPlayer]. */
    fun token(player: String): String =
        SessionsApi(baseUrl, http)
            .signIn(SignInRequest(player, password, SignInRequest.Client.native))
            .token!!

    /** As the puzzle master, sends the player a message; refused unless they can be reached. */
    fun sendMessage(
        questName: String,
        player: String,
        message: String,
        admin: String,
    ) {
        RunningQuestsApi(baseUrl, http)
            .apply { accessTokenProvider = { admin } }
            .sendPlayerTextMessage(questName, player, SendQuestTextMessageRequest(message))
    }

    /**
     * As the puzzle master, sends the player a challenge whose answer is
     * "lighthouse", and whose confirmation is "Show this to the bartender".
     * It first registers a phone for the player on another session of
     * theirs, since only players with the app or a phone number to text can
     * be sent one; the test server sends nothing to it.
     */
    fun sendChallenge(
        questName: String,
        player: String,
        message: String,
        admin: String,
    ) {
        SessionsApi(baseUrl, http).apply { accessTokenProvider = { token(player) } }.setPushTarget(
            PushTarget(PushTarget.Platform.android, unique("phone"), PushTarget.Environment.development),
        )
        RunningQuestsApi(baseUrl, http).apply { accessTokenProvider = { admin } }.sendPlayerTextMessage(
            questName,
            player,
            SendQuestTextMessageRequest(
                message,
                expectedResponse = "lighthouse",
                responseConfirmation = "Show this to the bartender",
            ),
        )
    }

    /** A stop's activation code, as the administrator sees it. */
    fun activationCode(
        questName: String,
        puzzleName: String,
        admin: String,
    ): String =
        QuestsApi(baseUrl, http)
            .apply { accessTokenProvider = { admin } }
            .getQuest(questName)
            .propertyEntries
            .flatMap { it.puzzles }
            .first { it.puzzleName == puzzleName }
            .activationCode!!

    /** The puzzle of the stop the player is on. */
    fun currentStop(
        questName: String,
        playerToken: String,
    ): String =
        QuestsApi(baseUrl, http)
            .apply { accessTokenProvider = { playerToken } }
            .getQuest(questName)
            .propertyEntries
            .single()
            .puzzles
            .last { it.status != StopStatus.completed }
            .puzzleName

    /**
     * As the player, finds the quest's current stop (with the code the
     * administrator sees) and solves it.
     */
    fun solveCurrentStop(
        questName: String,
        playerToken: String,
        admin: String,
    ) {
        val stop = currentStop(questName, playerToken)
        val code = activationCode(questName, stop, admin)
        val playing = PlayingApi(baseUrl, http).apply { accessTokenProvider = { playerToken } }
        playing.activatePuzzle(questName, stop, ActivatePuzzleRequest(activationCode = code))
        playing.attemptSolution(questName, stop, AttemptSolutionRequest("piano"))
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
            // A phone's token for push notifications, in place of
            // Firebase's, which an emulator on CI can't get; one for each
            // launch. The test server sends nothing to it.
            .putExtra(Push.EXTRA_FAKE_TOKEN, TestServer.unique("token"))
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

/**
 * Waits for one node with the tag inside something TalkBack reads as one
 * item (merged, as a card is). The merged tree, which tests search by
 * default, keeps only the item's own tag, so this searches the unmerged one.
 */
fun ComposeTestRule.waitForInside(
    tag: String,
    timeoutMillis: Long = 30_000,
) = waitUntil(timeoutMillis) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }

/** Waits until a screen's live updates are connected (its marker, shown with PQ_UI_TESTING, says so). */
fun ComposeTestRule.waitForLiveUpdates(tag: String) =
    waitUntil(30_000) {
        onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.StateDescription) == "connected"
    }

/**
 * Types into a field (replacing what's there), then taps a button. The
 * keyboard is closed between, and the button scrolled into view: while the
 * keyboard slides up, the screen moves, and a tap can land where the button
 * no longer is (as on CI's slow emulator), or on the keyboard itself.
 */
fun ComposeTestRule.typeThenTap(
    field: String,
    text: String,
    button: String,
) {
    onNodeWithTag(field).performScrollTo().performTextReplacement(text)
    waitForIdle()
    Espresso.closeSoftKeyboard()
    waitForIdle()
    onNodeWithTag(button).performScrollTo().performClick()
}

fun ComposeTestRule.signIn(
    userName: String,
    password: String,
) {
    waitFor("sign-in.user-name")
    onNodeWithTag("sign-in.user-name").performTextInput(userName)
    onNodeWithTag("sign-in.password").performTextInput(password)
    waitForIdle()
    Espresso.closeSoftKeyboard()
    waitForIdle()
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
