package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks that a server someone has typed is there, and is a PuzzleQuest
 * server this app can use, before the app switches to it: it fetches the
 * API's description, `/api/v1/openapi.json`, and reads its title and version.
 */
class ServerCheck(
    private val http: OkHttpClient = checkingClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Fetches the server's API description and checks it's PuzzleQuest's,
     * version 1.
     *
     * @throws ServerCheckFailed
     */
    suspend fun check(server: ServerOrigin) {
        val request =
            Request
                .Builder()
                .url("${server.url}/api/v1/openapi.json")
                .header("Accept", "application/json")
                .build()
        val text =
            withContext(io) {
                val response =
                    try {
                        http.newCall(request).execute()
                    } catch (_: IOException) {
                        throw ServerCheckFailed(ServerCheckFailure.UNREACHABLE)
                    } catch (_: IllegalArgumentException) {
                        // OkHttp's refusal of an address it can't use.
                        throw ServerCheckFailed(ServerCheckFailure.UNREACHABLE)
                    }
                response.use(::readDescription)
            }
        val description =
            try {
                lenient.decodeFromString<Description>(text)
            } catch (_: IllegalArgumentException) {
                // Not JSON, or not shaped like a description (kotlinx.serialization's
                // SerializationException is one of these).
                throw ServerCheckFailed(ServerCheckFailure.NOT_PUZZLE_QUEST)
            }
        if (description.info.title != API_TITLE) throw ServerCheckFailed(ServerCheckFailure.NOT_PUZZLE_QUEST)
        // Version 1 only grows, so any 1.x will do; a later version would be
        // served under /api/v2, so this shouldn't change.
        if (description.info.version.substringBefore('.') != "1") {
            throw ServerCheckFailed(ServerCheckFailure.UNSUPPORTED_VERSION)
        }
    }

    /** The description's text: something that isn't one, or is too large or cut off, isn't the API. */
    private fun readDescription(response: Response): String {
        if (response.code != 200) throw ServerCheckFailed(ServerCheckFailure.NOT_PUZZLE_QUEST)
        return try {
            val source = response.body.source()
            // Whether more than the largest description has arrived.
            if (source.request(LARGEST_DESCRIPTION + 1)) throw ServerCheckFailed(ServerCheckFailure.NOT_PUZZLE_QUEST)
            source.buffer.readUtf8()
        } catch (_: IOException) {
            throw ServerCheckFailed(ServerCheckFailure.NOT_PUZZLE_QUEST)
        }
    }

    /** As much of an OpenAPI description as the check reads. */
    @Serializable
    private data class Description(
        val info: Info,
    ) {
        @Serializable
        data class Info(
            val title: String,
            val version: String,
        )
    }

    companion object {
        /** The title PuzzleQuest's API gives itself. */
        const val API_TITLE = "PuzzleQuest API"

        /** The description is a few hundred kilobytes; anything much larger isn't one. */
        private const val LARGEST_DESCRIPTION = 8L * 1024 * 1024

        private val lenient = Json { ignoreUnknownKeys = true }

        /**
         * Not as patient as the API's client: someone is waiting, and a
         * mistyped address should be explained soon. Built from the shared
         * client, it shares its threads and connections.
         */
        private val checkingClient: OkHttpClient by lazy {
            PuzzleQuestApi.defaultHttpClient
                .newBuilder()
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Why the app won't switch to a server. */
enum class ServerCheckFailure {
    /** Nothing answered: the address is wrong, or the server or the network is down. */
    UNREACHABLE,

    /** Something answered, but it isn't a PuzzleQuest server. */
    NOT_PUZZLE_QUEST,

    /** It's a PuzzleQuest server, but its API isn't version 1. */
    UNSUPPORTED_VERSION,
}

/** The app won't switch to a server, and why. */
class ServerCheckFailed(
    val reason: ServerCheckFailure,
) : Exception(reason.name)
