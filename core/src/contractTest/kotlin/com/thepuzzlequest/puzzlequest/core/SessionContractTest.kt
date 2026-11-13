package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Signing in and out through the generated client, against the real server
 * started by scripts/start-test-server.sh, at the release the app is pinned
 * to. Load .test-server/env into the environment first; without it these
 * are skipped.
 */
class SessionContractTest {
    @Before
    fun needsTheTestServer() {
        assumeTrue("Set PQ_TEST_SERVER_URL (see scripts/start-test-server.sh)", TestSite.url != null)
    }

    private fun api(tokens: TokenStore = InMemoryTokenStore()) = TestSite.api(tokens)

    /** A user name of its own, since failed sign-ins are limited per name. */
    private fun nobody() = "nobody-${UUID.randomUUID().toString().take(8)}"

    private suspend fun refusal(action: suspend () -> Unit): ApiError {
        try {
            action()
        } catch (error: ApiError) {
            return error
        }
        fail("it should have been refused")
        error("unreachable")
    }

    @Test
    fun `sign in, see who is signed in, and sign out`() =
        runTest {
            val tokens = InMemoryTokenStore()
            val api = api(tokens)
            val (token, user) = api.signIn(TestSite.userName, TestSite.password)
            assertFalse(token.isEmpty())
            // Signed up by the test server, and never verified.
            assertEquals(
                SignedInUser(TestSite.userName, TestSite.displayName, Role.Player, emailVerified = false),
                user,
            )

            tokens.save(token)
            assertEquals(user, api.currentUser())

            api.signOut()
            val error = refusal { api.currentUser() } as ApiError.Refused
            assertEquals(Problem.Types.SIGN_IN_REQUIRED, error.problem.type)
            assertEquals(401, error.problem.status)
        }

    @Test
    fun `the session store signs in, restores the session, and signs out`() =
        runTest {
            val tokens = InMemoryTokenStore()
            val session = SessionStore(api(tokens), tokens)
            session.signIn(TestSite.userName, TestSite.password)
            assertNotNull(tokens.read())

            // As at the next launch.
            val next = SessionStore(api(tokens), tokens)
            next.restore()
            val state = next.state.value as SessionStore.State.SignedIn
            assertEquals(TestSite.userName, state.user.userName)

            next.signOut()
            assertNull(tokens.read())
            assertEquals(SessionStore.State.SignedOut, next.state.value)
        }

    @Test
    fun `a session the server has ended is dropped on restoring`() =
        runTest {
            val tokens = InMemoryTokenStore()
            val api = api(tokens)
            val (token, _) = api.signIn(TestSite.userName, TestSite.password)
            tokens.save(token)
            api.signOut()
            // Still kept here, as if signed out elsewhere.
            tokens.save(token)

            val session = SessionStore(api, tokens)
            session.restore()
            assertEquals(SessionStore.State.SignedOut, session.state.value)
            assertNull(tokens.read())
        }

    @Test
    fun `a wrong password is a failed sign-in`() =
        runTest {
            val error = refusal { api().signIn(nobody(), "wrong") }
            assertEquals(401, (error as ApiError.Refused).problem.status)
            assertEquals(Problem.Types.SIGN_IN_FAILED, error.problem.type)
            assertEquals(SignInFailure.WRONG_USER_NAME_OR_PASSWORD, SignInFailure.of(error))
        }

    @Test
    fun `an empty user name is an invalid request`() =
        runTest {
            val error = refusal { api().signIn("", "anything") }
            assertEquals(SignInFailure.INVALID_REQUEST, SignInFailure.of(error))
        }

    @Test
    fun `too many failed sign-ins are refused for a while`() =
        runTest {
            val userName = nobody()
            val failures =
                // The server allows 10 failed attempts per user name in 15 minutes.
                (1..11).map {
                    SignInFailure.of(refusal { api().signIn(userName, "wrong") })
                }
            assertEquals(SignInFailure.WRONG_USER_NAME_OR_PASSWORD, failures.first())
            assertEquals(SignInFailure.TOO_MANY_ATTEMPTS, failures.last())
        }

    @Test
    fun `the server serves the version of the API the app is pinned to`() {
        val request = Request.Builder().url("${TestSite.url}/api/v1/openapi.json").build()
        val served =
            PuzzleQuestApi.defaultHttpClient.newCall(request).execute().use {
                Json.decodeFromString<JsonObject>(it.body.string())
            }
        // The pinned release's description (api/openapi.json). Its API version
        // can be older than the release's tag (api/VERSION): a release that
        // changes only the server, such as 1.1.2, keeps the API's version.
        val pinned = Json.decodeFromString<JsonObject>(File(System.getProperty("pq.pinnedDescription")).readText())

        fun version(description: JsonObject) =
            description["info"]
                ?.jsonObject
                ?.get("version")
                ?.jsonPrimitive
                ?.content
        assertNotNull(version(served))
        assertEquals(version(pinned), version(served))
    }
}
