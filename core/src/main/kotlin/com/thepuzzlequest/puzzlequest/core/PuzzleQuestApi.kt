package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.apis.EmailVerificationApi
import com.thepuzzlequest.puzzlequest.api.apis.PlayingApi
import com.thepuzzlequest.puzzlequest.api.apis.QuestsApi
import com.thepuzzlequest.puzzlequest.api.apis.SessionsApi
import com.thepuzzlequest.puzzlequest.api.apis.SpectatingApi
import com.thepuzzlequest.puzzlequest.api.apis.UsersApi
import com.thepuzzlequest.puzzlequest.api.infrastructure.ClientError
import com.thepuzzlequest.puzzlequest.api.infrastructure.ClientException
import com.thepuzzlequest.puzzlequest.api.infrastructure.ServerError
import com.thepuzzlequest.puzzlequest.api.infrastructure.ServerException
import com.thepuzzlequest.puzzlequest.api.models.ActivatePuzzleRequest
import com.thepuzzlequest.puzzlequest.api.models.AttemptSolutionRequest
import com.thepuzzlequest.puzzlequest.api.models.RevealHintRequest
import com.thepuzzlequest.puzzlequest.api.models.SignInRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The PuzzleQuest API, through the client generated from its pinned
 * description. Every function either returns what the app needs or throws
 * an [ApiError].
 *
 * @param server the site, such as https://thepuzzlequest.com; the API is under
 *   its `/api/v1`.
 * @param tokens where the session's token is kept, sent with every request.
 */
class PuzzleQuestApi(
    /** The site, such as https://thepuzzlequest.com. */
    val server: String,
    private val tokens: TokenStore,
    http: OkHttpClient = defaultHttpClient,
    /** Where the generated client's blocking calls run. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        // Before any call: see ApiJson.
        ApiJson.setUp()
    }

    private val baseUrl = server.trimEnd('/') + "/api/v1"

    /**
     * The live-update websocket's address: `/api/v1/updates` on the site, over
     * `wss:` (or `ws:` for a site on plain HTTP, such as a local one).
     */
    val liveUpdatesUrl: String =
        baseUrl.replaceFirst(Regex("^http"), "ws") + "/updates"

    // The generated classes each cover one tag of the API; they share the
    // token, read afresh for every request.
    private val sessions =
        SessionsApi(baseUrl, http).apply { accessTokenProvider = { tokens.read() } }
    private val users =
        UsersApi(baseUrl, http).apply { accessTokenProvider = { tokens.read() } }
    private val emailVerification =
        EmailVerificationApi(baseUrl, http).apply { accessTokenProvider = { tokens.read() } }
    private val quests =
        QuestsApi(baseUrl, http).apply { accessTokenProvider = { tokens.read() } }
    private val spectating =
        SpectatingApi(baseUrl, http).apply { accessTokenProvider = { tokens.read() } }
    private val playing =
        PlayingApi(baseUrl, http).apply { accessTokenProvider = { tokens.read() } }

    /** Signs in as an app, which is given the session's token to keep. */
    suspend fun signIn(
        userName: String,
        password: String,
    ): Pair<String, SignedInUser> {
        val session =
            call {
                sessions.signIn(
                    SignInRequest(
                        userName = userName,
                        password = password,
                        client = SignInRequest.Client.native,
                    ),
                )
            }
        val token = session.token ?: throw ApiError.UnexpectedResponse("signIn: the session has no token")
        return token to SignedInUser(session.user)
    }

    /** Who the session's token belongs to. */
    suspend fun currentUser(): SignedInUser = SignedInUser(call { sessions.getCurrentSession() }.user)

    /** Ends the session whose token is sent. */
    suspend fun signOut() {
        call { sessions.signOut() }
    }

    /**
     * Every quest the user plays whose entry has started, fetching each page
     * of the list in turn.
     */
    suspend fun userQuests(userName: String): List<QuestSummary> =
        allPages { after ->
            val page = call { users.listUserQuests(userName, after = after) }
            page.items.map(::QuestSummary) to page.next
        }

    /**
     * A ticket for one connection to the live-update websocket to follow the
     * user's list of quests. Each can be used once.
     */
    suspend fun userLiveTicket(userName: String): String = call { users.createUserLiveTicket(userName) }.ticket

    /**
     * A quest as its player sees it, or null if it isn't theirs to see: not
     * found, or with no entry of theirs, as once the puzzle master has reset
     * it.
     */
    suspend fun playerQuest(questName: String): PlayerQuest? {
        val quest =
            try {
                call { quests.getQuest(questName) }
            } catch (error: ApiError.Refused) {
                if (error.problem.status == 404) return null
                throw error
            }
        return playerQuest(quest)
    }

    /** A quest's standings, as anyone who may follow it sees them. */
    suspend fun standings(questName: String): Standings = Standings(call { spectating.getSpectatorQuest(questName) })

    /**
     * A ticket for one connection to the live-update websocket to follow a
     * quest. Each can be used once.
     */
    suspend fun questLiveTicket(questName: String): String = call { spectating.createLiveTicket(questName) }.ticket

    /**
     * A stop's puzzle as its players see it, or null if it isn't theirs to
     * see: not reached, or gone, as once the puzzle master has reset the
     * quest.
     */
    suspend fun playerPuzzle(
        questName: String,
        puzzleName: String,
    ): PlayerPuzzle? {
        val puzzle =
            try {
                call { playing.getPlayerPuzzle(questName, puzzleName) }
            } catch (error: ApiError.Refused) {
                if (error.problem.status == 404) return null
                throw error
            }
        return PlayerPuzzle(puzzle, server)
    }

    /**
     * Starts the puzzle the players have found, with its activation code,
     * typed or read from its QR code.
     *
     * @param connectionId the live-update connection, which then isn't sent
     *   the update about it: the screen shows the result itself.
     */
    suspend fun activatePuzzle(
        questName: String,
        puzzleName: String,
        code: String,
        connectionId: String?,
    ) {
        call { playing.activatePuzzle(questName, puzzleName, ActivatePuzzleRequest(code, connectionId)) }
    }

    /** Guesses a puzzle's solution. A wrong guess is refused, as `/problems/wrong-answer`. */
    suspend fun attemptSolution(
        questName: String,
        puzzleName: String,
        guess: String,
        connectionId: String?,
    ) {
        call { playing.attemptSolution(questName, puzzleName, AttemptSolutionRequest(guess, connectionId)) }
    }

    /** Reveals a puzzle's next hint, adding its penalty to the players' time. */
    suspend fun revealHint(
        questName: String,
        puzzleName: String,
        connectionId: String?,
    ) {
        call { playing.revealHint(questName, puzzleName, RevealHintRequest(connectionId)) }
    }

    /** Sends the user another email with the link that verifies their address. */
    suspend fun sendVerificationEmail(userName: String) {
        call { emailVerification.sendVerificationEmail(userName) }
    }

    /**
     * Runs a generated client's call off the main thread, turning whatever it
     * throws into an [ApiError].
     */
    private suspend fun <T> call(operation: () -> T): T =
        withContext(io) {
            try {
                operation()
            } catch (error: ClientException) {
                throw ApiError.Refused(problem(error.statusCode, (error.response as? ClientError<*>)?.body))
            } catch (error: ServerException) {
                throw ApiError.Refused(problem(error.statusCode, (error.response as? ServerError<*>)?.body))
            } catch (error: IOException) {
                throw ApiError.Unreachable
            } catch (error: SerializationException) {
                throw ApiError.UnexpectedResponse(error.message ?: error.toString())
            } catch (error: IllegalArgumentException) {
                // Also what kotlinx.serialization throws for a missing field.
                throw ApiError.UnexpectedResponse(error.message ?: error.toString())
            } catch (error: UnsupportedOperationException) {
                // The generated client's answer to a response that isn't JSON.
                throw ApiError.UnexpectedResponse(error.message ?: error.toString())
            }
        }

    companion object {
        /** OkHttp's client, shared, since each one has its own threads and connections. */
        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Problem details are small; anything larger isn't one. */
        private const val LARGEST_PROBLEM = 64 * 1024

        private val lenient = Json { ignoreUnknownKeys = true }

        /**
         * A refusal's problem details. A response without readable details
         * becomes an `about:blank` problem with its status, as RFC 9457 says a
         * plain HTTP error is.
         */
        internal fun problem(
            status: Int,
            body: Any?,
        ): Problem {
            val plain = Problem(type = "about:blank", status = status, title = reasonPhrase(status))
            val text = (body as? String)?.takeIf { it.length <= LARGEST_PROBLEM } ?: return plain
            val details =
                try {
                    lenient.decodeFromString<Details>(text)
                } catch (_: IllegalArgumentException) {
                    return plain
                }
            return Problem(
                type = details.type ?: plain.type,
                status = details.status ?: plain.status,
                title = details.title ?: plain.title,
                detail = details.detail,
            )
        }

        private fun reasonPhrase(status: Int) =
            when (status) {
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                429 -> "Too Many Requests"
                500 -> "Internal Server Error"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                504 -> "Gateway Timeout"
                else -> "HTTP $status"
            }
    }

    /**
     * Problem details, read leniently: any member may be missing, since this
     * also reads the errors of servers in front of the API.
     */
    @Serializable
    private data class Details(
        val type: String? = null,
        val status: Int? = null,
        val title: String? = null,
        val detail: String? = null,
    )
}

/**
 * Gets everything in one of the API's paged lists: each page names the cursor
 * (`next`) to send as `after` for the page that follows, and null on the last.
 *
 * @param fetchPage fetches the page after a cursor, or the first page for
 *   null, returning its items and the next cursor.
 */
internal suspend fun <T> allPages(fetchPage: suspend (after: String?) -> Pair<List<T>, String?>): List<T> {
    val items = mutableListOf<T>()
    var after: String? = null
    do {
        val (page, next) = fetchPage(after)
        items += page
        after = next
    } while (after != null)
    return items
}
