package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.apis.PuzzlesApi
import com.thepuzzlequest.puzzlequest.api.apis.QuestsApi
import com.thepuzzlequest.puzzlequest.api.apis.RunningQuestsApi
import com.thepuzzlequest.puzzlequest.api.apis.UsersApi
import com.thepuzzlequest.puzzlequest.api.models.CreatePuzzleRequest
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequest
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequestEntrantsInner
import com.thepuzzlequest.puzzlequest.api.models.CreateQuestRequestPuzzlesInner
import com.thepuzzlequest.puzzlequest.api.models.CreateUserRequest
import com.thepuzzlequest.puzzlequest.api.models.EntrantKind
import java.util.UUID

/**
 * The test server (scripts/start-test-server.sh), named by the variables in
 * .test-server/env, and what tests do on it as its administrator and as new
 * players of their own, so that each test starts with a player who has no
 * quests.
 */
object TestSite {
    init {
        // The generated classes below are used directly: see ApiJson.
        ApiJson.setUp()
    }

    val url: String? = System.getenv("PQ_TEST_SERVER_URL")
    val userName: String get() = System.getenv("PQ_TEST_USER_NAME")
    val password: String get() = System.getenv("PQ_TEST_PASSWORD")
    val displayName: String get() = System.getenv("PQ_TEST_DISPLAY_NAME") ?: userName
    private val adminUserName: String get() = System.getenv("PQ_TEST_ADMIN_USER_NAME")
    private val adminPassword: String get() = System.getenv("PQ_TEST_ADMIN_PASSWORD")

    private val baseUrl get() = "$url/api/v1"
    private val http = PuzzleQuestApi.defaultHttpClient

    /** A name no one has used, for a user, puzzle, or quest. */
    fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    fun api(tokens: TokenStore = InMemoryTokenStore()) = PuzzleQuestApi(url!!, tokens)

    /** The API, signed in as that user. */
    suspend fun signedIn(
        userName: String,
        password: String,
    ): PuzzleQuestApi {
        val tokens = InMemoryTokenStore()
        val api = api(tokens)
        tokens.save(api.signIn(userName, password).first)
        return api
    }

    /** Signs up a new player, and signs in as them. */
    suspend fun newPlayer(): Pair<String, PuzzleQuestApi> {
        val userName = unique("player")
        UsersApi(baseUrl, http).createUser(
            CreateUserRequest(
                userName = userName,
                email = "$userName@example.com",
                password = password,
                displayName = "Player $userName",
            ),
        )
        return userName to signedIn(userName, password)
    }

    /** The administrator's token, for the setup calls below. */
    suspend fun adminToken(): String = api().signIn(adminUserName, adminPassword).first

    /** Makes a quest of one stop for a player to play on their own, without starting it. */
    fun makeQuest(
        player: String,
        adminToken: String,
    ): String {
        val puzzleName = unique("puzzle")
        PuzzlesApi(baseUrl, http).apply { accessTokenProvider = { adminToken } }.createPuzzle(
            CreatePuzzleRequest(
                puzzleName = puzzleName,
                solutionKeyword = "piano",
                solutionText = "A piano",
                displayName = "A puzzle",
                text = "What has keys?",
            ),
        )
        val questName = unique("quest")
        QuestsApi(baseUrl, http).apply { accessTokenProvider = { adminToken } }.createQuest(
            CreateQuestRequest(
                questName = questName,
                displayName = "Quest $questName",
                entrants = listOf(CreateQuestRequestEntrantsInner(EntrantKind.player, player)),
                puzzles = listOf(CreateQuestRequestPuzzlesInner(puzzleName)),
            ),
        )
        return questName
    }

    fun start(
        questName: String,
        adminToken: String,
    ) {
        RunningQuestsApi(baseUrl, http).apply { accessTokenProvider = { adminToken } }.startQuest(questName)
    }
}
