package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionTest {
    @get:Rule val server = FakeServer()

    private fun session(tokens: TokenStore) = SessionStore(PuzzleQuestApi(server.url, tokens), tokens)

    @Test
    fun `with no token kept, restoring asks nothing and is signed out`() =
        runTest {
            val session = session(InMemoryTokenStore())
            session.restore()
            assertEquals(SessionStore.State.SignedOut, session.state.value)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a kept token is checked with the server, sent as a Bearer token`() =
        runTest {
            server.respond(200, """{"user":$ALICE_JSON}""")
            val session = session(InMemoryTokenStore("kept"))
            session.restore()
            assertEquals(SessionStore.State.SignedIn(alice), session.state.value)
            val sent = server.sent()
            assertEquals("GET /api/v1/sessions/current", "${sent.method} ${sent.url.encodedPath}")
            assertEquals("Bearer kept", sent.headers["Authorization"])
        }

    @Test
    fun `a token the server no longer accepts is dropped`() =
        runTest {
            server.respond(
                401,
                """{"type":"/problems/sign-in-required","status":401,"title":"Sign in"}""",
                "application/problem+json",
            )
            val tokens = InMemoryTokenStore("kept")
            val session = session(tokens)
            session.restore()
            assertEquals(SessionStore.State.SignedOut, session.state.value)
            assertNull(tokens.read())
        }

    @Test
    fun `when the server can't be reached, the token is kept to try again`() =
        runTest {
            val tokens = InMemoryTokenStore("kept")
            val session = SessionStore(PuzzleQuestApi(nowhere(), tokens), tokens)
            session.restore()
            assertEquals(SessionStore.State.RestoreFailed, session.state.value)
            assertEquals("kept", tokens.read())
        }

    @Test
    fun `when the server fails, the token is kept to try again`() =
        runTest {
            server.respond(500, """{"type":"/problems/unexpected","status":500,"title":"Oops"}""")
            val tokens = InMemoryTokenStore("kept")
            val session = session(tokens)
            session.restore()
            assertEquals(SessionStore.State.RestoreFailed, session.state.value)
            assertEquals("kept", tokens.read())
        }

    @Test
    fun `signing in asks for a token as an app, and keeps it`() =
        runTest {
            server.respond(201, """{"user":$ALICE_JSON,"token":"new-token"}""")
            val tokens = InMemoryTokenStore()
            val session = session(tokens)
            session.signIn("alice", "secret")
            assertEquals(SessionStore.State.SignedIn(alice), session.state.value)
            assertEquals("new-token", tokens.read())
            val sent = server.sent()
            assertEquals("POST /api/v1/sessions", "${sent.method} ${sent.url.encodedPath}")
            val body = Json.decodeFromString<JsonObject>(sent.body!!.utf8())
            assertEquals(
                mapOf("userName" to "alice", "password" to "secret", "client" to "native"),
                body.mapValues { it.value.jsonPrimitive.content },
            )
        }

    @Test
    fun `signing out ends the session on the server, then forgets the token`() =
        runTest {
            server.respond(204, "")
            val tokens = InMemoryTokenStore("kept")
            val session = session(tokens)
            session.signOut()
            assertEquals(SessionStore.State.SignedOut, session.state.value)
            assertNull(tokens.read())
            val sent = server.sent()
            assertEquals("DELETE /api/v1/sessions/current", "${sent.method} ${sent.url.encodedPath}")
            assertEquals("Bearer kept", sent.headers["Authorization"])
        }

    @Test
    fun `signing out without a connection still forgets the token`() =
        runTest {
            val tokens = InMemoryTokenStore("kept")
            val session = SessionStore(PuzzleQuestApi(nowhere(), tokens), tokens)
            session.signOut()
            assertEquals(SessionStore.State.SignedOut, session.state.value)
            assertNull(tokens.read())
        }

    @Test
    fun `signing in needs a user name and a password`() {
        val model = SignInModel(session(InMemoryTokenStore()))
        assertFalse(model.canSignIn("", "secret"))
        assertFalse(model.canSignIn("  ", "secret"))
        assertFalse(model.canSignIn("alice", ""))
        assertTrue(model.canSignIn("alice", "secret"))
    }

    @Test
    fun `the user name is sent without surrounding spaces`() =
        runTest {
            server.respond(201, """{"user":$ALICE_JSON,"token":"new-token"}""")
            val session = session(InMemoryTokenStore())
            val model = SignInModel(session)
            assertTrue(model.signIn("  alice \n", "secret"))
            assertEquals(SessionStore.State.SignedIn(alice), session.state.value)
            assertEquals(SignInModel.State(), model.state.value)
            val body = Json.decodeFromString<JsonObject>(server.sent().body!!.utf8())
            assertEquals("alice", body["userName"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a failure is kept to show, and cleared by the next attempt`() =
        runTest {
            server.respond(401, """{"type":"/problems/sign-in-failed","status":401,"title":"No"}""")
            server.respond(201, """{"user":$ALICE_JSON,"token":"new-token"}""")
            val session = session(InMemoryTokenStore())
            val model = SignInModel(session)

            assertFalse(model.signIn("alice", "wrong"))
            assertEquals(SignInModel.State(failure = SignInFailure.WRONG_USER_NAME_OR_PASSWORD), model.state.value)
            assertEquals(SessionStore.State.Restoring, session.state.value)

            assertTrue(model.signIn("alice", "secret"))
            assertEquals(SignInModel.State(), model.state.value)
        }
}
