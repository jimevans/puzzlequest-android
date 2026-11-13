package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.apis.SessionsApi
import com.thepuzzlequest.puzzlequest.api.infrastructure.ClientError
import com.thepuzzlequest.puzzlequest.api.infrastructure.ClientException
import com.thepuzzlequest.puzzlequest.api.infrastructure.ServerError
import com.thepuzzlequest.puzzlequest.api.infrastructure.ServerException
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
    server: String,
    private val tokens: TokenStore,
    http: OkHttpClient = defaultHttpClient,
    /** Where the generated client's blocking calls run. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val baseUrl = server.trimEnd('/') + "/api/v1"

    // The generated classes each cover one tag of the API; they share the
    // token, read afresh for every request.
    private val sessions =
        SessionsApi(baseUrl, http).apply { accessTokenProvider = { tokens.read() } }

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
