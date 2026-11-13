package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class ErrorMappingTest {
    @get:Rule val server = FakeServer()

    private val api get() = PuzzleQuestApi(server.url, InMemoryTokenStore())

    private suspend fun signInError(api: PuzzleQuestApi = this.api): ApiError {
        try {
            api.signIn("alice", "secret")
        } catch (error: ApiError) {
            return error
        }
        fail("signing in should have failed")
        error("unreachable")
    }

    @Test
    fun `each refusal is read from its problem details and explained`() =
        runTest {
            val cases =
                listOf(
                    Triple(Problem.Types.SIGN_IN_FAILED, 401, SignInFailure.WRONG_USER_NAME_OR_PASSWORD),
                    Triple(Problem.Types.TOO_MANY_REQUESTS, 429, SignInFailure.TOO_MANY_ATTEMPTS),
                    Triple(Problem.Types.INVALID_REQUEST, 400, SignInFailure.INVALID_REQUEST),
                    Triple(Problem.Types.UNEXPECTED, 500, SignInFailure.UNEXPECTED),
                    // Version 1 may add problem types.
                    Triple("/problems/something-new", 403, SignInFailure.UNEXPECTED),
                )
            for ((type, status, failure) in cases) {
                server.respond(
                    status,
                    """{"type":"$type","status":$status,"title":"Refused","detail":"Refused.","extra":1}""",
                    "application/problem+json",
                )
                val error = signInError()
                assertEquals(ApiError.Refused(Problem(type, status, "Refused", "Refused.")), error)
                assertEquals(failure, SignInFailure.of(error))
            }
        }

    @Test
    fun `no connection means the server can't be reached`() =
        runTest {
            val error = signInError(PuzzleQuestApi(nowhere(), InMemoryTokenStore()))
            assertEquals(ApiError.Unreachable, error)
            assertEquals(SignInFailure.UNREACHABLE, SignInFailure.of(error))
        }

    @Test
    fun `an error without problem details is a plain HTTP problem`() =
        runTest {
            server.respond(502, "<html>Bad gateway</html>", "text/html")
            assertEquals(ApiError.Refused(Problem("about:blank", 502, "Bad Gateway")), signInError())
        }

    @Test
    fun `a success the app can't read is an unexpected response`() =
        runTest {
            server.respond(201, """{"user":{"userName":"alice"}}""")
            val error = signInError()
            assertTrue(error.toString(), error is ApiError.UnexpectedResponse)
            assertEquals(SignInFailure.UNEXPECTED, SignInFailure.of(error))
        }

    @Test
    fun `a success that isn't JSON is an unexpected response`() =
        runTest {
            server.respond(201, "<html>Signed in?</html>", "text/html")
            assertTrue(signInError() is ApiError.UnexpectedResponse)
        }
}
