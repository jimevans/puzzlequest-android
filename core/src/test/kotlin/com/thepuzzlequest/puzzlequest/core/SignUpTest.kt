package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A refusal naming the fields it's about, as the API sends one. */
fun fieldProblemJson(
    status: Int,
    type: String,
    vararg errors: Pair<String, String>,
): String {
    val list = errors.joinToString(",") { (path, message) -> """{"path":"$path","message":"$message"}""" }
    return """{"type":"$type","title":"Refused","status":$status,"errors":[$list]}"""
}

private fun problemJson(
    status: Int,
    type: String,
) = """{"type":"$type","title":"Refused","status":$status}"""

class SignUpTest {
    @get:Rule val server = FakeServer()

    private val tokens = InMemoryTokenStore()
    private val session by lazy { SessionStore(PuzzleQuestApi(server.url, tokens), tokens) }

    /** A sign-up form filled in rightly. */
    private fun filled(model: SignUpModel) =
        model.edit {
            it.copy(
                userName = " newbie ",
                password = "a long enough password",
                confirmPassword = "a long enough password",
                displayName = "New Player",
                email = "newbie@example.com",
            )
        }

    private fun body(name: String): JsonObject =
        Json.decodeFromString<JsonObject>(server.bodies.first { it.first == name }.second)

    @Test
    fun `it signs up with what's typed, then signs in as the new player`() =
        runTest {
            server.answer { request ->
                if (request == "POST /api/v1/users") {
                    201 to ALICE_JSON
                } else {
                    201 to """{"user":$ALICE_JSON,"token":"new-token"}"""
                }
            }
            val model = SignUpModel(session)
            filled(model)
            model.edit { it.copy(phone = " (312) 555-0142 ", sms = true) }
            assertTrue(model.signUp())

            assertEquals(listOf("POST /api/v1/users", "POST /api/v1/sessions"), server.bodies.map { it.first })
            val sent = body("POST /api/v1/users")
            assertEquals("newbie", sent["userName"]?.jsonPrimitive?.content)
            assertEquals("a long enough password", sent["password"]?.jsonPrimitive?.content)
            assertEquals("New Player", sent["displayName"]?.jsonPrimitive?.content)
            assertEquals("newbie@example.com", sent["email"]?.jsonPrimitive?.content)
            assertEquals("(312) 555-0142", sent["phone"]?.jsonPrimitive?.content)
            assertEquals(true, sent["sms"]?.jsonPrimitive?.booleanOrNull)
            // An app may not choose its role; nothing else is sent.
            assertFalse("role" in sent)
            assertFalse("confirmPassword" in sent)
            assertEquals(SessionStore.State.SignedIn(alice), session.state.value)
            assertEquals("new-token", tokens.read())
            assertNull(model.state.value.failure)
            assertFalse(model.state.value.isSigningUp)
        }

    @Test
    fun `without a phone number, none is sent`() =
        runTest {
            server.answer { request ->
                if (request ==
                    "POST /api/v1/users"
                ) {
                    201 to ALICE_JSON
                } else {
                    201 to """{"user":$ALICE_JSON,"token":"t"}"""
                }
            }
            val model = SignUpModel(session)
            filled(model)
            model.edit { it.copy(phone = "  ") }
            model.signUp()
            val sent = body("POST /api/v1/users")
            assertFalse("phone" in sent)
            assertEquals(false, sent["sms"]?.jsonPrimitive?.booleanOrNull)
        }

    @Test
    fun `what's missing or wrong is found before anything is sent`() =
        runTest {
            val model = SignUpModel(session)
            model.edit { it.copy(password = "short", confirmPassword = "shorter", email = "   ") }
            assertFalse(model.signUp())
            assertEquals(
                mapOf(
                    SignUpField.USER_NAME to FieldProblem.Required,
                    SignUpField.PASSWORD to FieldProblem.PasswordTooShort,
                    SignUpField.CONFIRM_PASSWORD to FieldProblem.PasswordsDiffer,
                    SignUpField.DISPLAY_NAME to FieldProblem.Required,
                    SignUpField.EMAIL to FieldProblem.Required,
                ),
                model.state.value.fieldProblems,
            )
            assertEquals(0, server.requestCount)
            assertEquals(SessionStore.State.Restoring, session.state.value)

            // Editing a field clears what was said about it, and only it.
            model.edit { it.copy(email = "e@example.com") }
            assertNull(model.state.value.fieldProblems[SignUpField.EMAIL])
            assertEquals(FieldProblem.Required, model.state.value.fieldProblems[SignUpField.USER_NAME])
        }

    @Test
    fun `a user name or email address already in use is explained in the server's words`() =
        runTest {
            server.respond(
                409,
                """{"type":"/problems/already-exists","title":"Already exists","status":409,""" +
                    """"detail":"That user name is already in use"}""",
                "application/problem+json",
            )
            val model = SignUpModel(session)
            filled(model)
            assertFalse(model.signUp())
            assertEquals(SignUpFailure.AlreadyInUse("That user name is already in use"), model.state.value.failure)
            assertTrue(
                model.state.value.fieldProblems
                    .isEmpty(),
            )
            assertEquals(SessionStore.State.Restoring, session.state.value)
        }

    @Test
    fun `a refusal that names its field is shown at the field`() =
        runTest {
            server.respond(
                409,
                fieldProblemJson(
                    409,
                    "/problems/already-exists",
                    "body.userName" to "That user name is already in use",
                ),
                "application/problem+json",
            )
            val model = SignUpModel(session)
            filled(model)
            model.signUp()
            assertEquals(
                mapOf(SignUpField.USER_NAME to FieldProblem.Server("That user name is already in use")),
                model.state.value.fieldProblems,
            )
            assertNull(model.state.value.failure)
            assertFalse(model.state.value.isSigningUp)
        }

    @Test
    fun `a password the server won't take is shown at the password`() =
        runTest {
            server.respond(422, problemJson(422, "/problems/password-not-allowed"), "application/problem+json")
            val model = SignUpModel(session)
            filled(model)
            model.signUp()
            assertEquals(
                mapOf(SignUpField.PASSWORD to FieldProblem.Server("Refused")),
                model.state.value.fieldProblems,
            )
        }

    @Test
    fun `other refusals are explained above the form`() =
        runTest {
            for ((status, type, expected) in listOf(
                Triple(429, "/problems/too-many-requests", SignUpFailure.TooManyAttempts),
                Triple(500, "/problems/unexpected", SignUpFailure.Unexpected),
            )) {
                server.respond(status, problemJson(status, type), "application/problem+json")
                val model = SignUpModel(session)
                filled(model)
                model.signUp()
                assertEquals(expected, model.state.value.failure)
                assertTrue(
                    model.state.value.fieldProblems
                        .isEmpty(),
                )
            }
            val unreachable = InMemoryTokenStore()
            val model = SignUpModel(SessionStore(PuzzleQuestApi(nowhere(), unreachable), unreachable))
            filled(model)
            model.signUp()
            assertEquals(SignUpFailure.Unreachable, model.state.value.failure)
        }

    @Test
    fun `if signing in fails once the account is made, the player is told to sign in`() =
        runTest {
            server.answer { request ->
                if (request == "POST /api/v1/users") {
                    201 to ALICE_JSON
                } else {
                    500 to problemJson(500, "/problems/unexpected")
                }
            }
            val model = SignUpModel(session)
            filled(model)
            assertFalse(model.signUp())
            assertEquals(SignUpFailure.SignedUpButNotSignedIn, model.state.value.failure)
            assertEquals(SessionStore.State.Restoring, session.state.value)
        }
}

class DeleteAccountTest {
    @get:Rule val server = FakeServer()

    @Test
    fun `it deletes the player's own account with their password, and signs out`() =
        runTest {
            server.respond(200, """{"user":$ALICE_JSON}""")
            server.respond(204, "")
            val tokens = InMemoryTokenStore("kept")
            val session = SessionStore(PuzzleQuestApi(server.url, tokens), tokens)
            session.restore()
            server.sent()
            val model = DeleteAccountModel(session)
            assertFalse(model.canDelete(""))
            assertTrue(model.delete("secret"))

            val sent = server.sent()
            assertEquals("DELETE /api/v1/users/alice", "${sent.method} ${sent.url.encodedPath}")
            val body = Json.decodeFromString<JsonObject>(sent.body!!.utf8())
            assertEquals("secret", body["password"]?.jsonPrimitive?.content)
            assertEquals(SessionStore.State.SignedOut, session.state.value)
            assertNull(tokens.read())
            assertNull(model.state.value.failure)
        }

    @Test
    fun `refusals are explained, and the player stays signed in`() =
        runTest {
            for ((status, type, expected) in listOf(
                Triple(422, "/problems/incorrect-password", DeleteAccountFailure.INCORRECT_PASSWORD),
                Triple(429, "/problems/too-many-requests", DeleteAccountFailure.TOO_MANY_ATTEMPTS),
                Triple(409, "/problems/wrong-state", DeleteAccountFailure.NOT_POSSIBLE),
                Triple(500, "/problems/unexpected", DeleteAccountFailure.UNEXPECTED),
            )) {
                server.respond(200, """{"user":$ALICE_JSON}""")
                server.respond(status, problemJson(status, type), "application/problem+json")
                val tokens = InMemoryTokenStore("kept")
                val session = SessionStore(PuzzleQuestApi(server.url, tokens), tokens)
                session.restore()
                val model = DeleteAccountModel(session)
                assertFalse(model.delete("wrong"))
                assertEquals(expected, model.state.value.failure)
                assertEquals(SessionStore.State.SignedIn(alice), session.state.value)
                assertEquals("kept", tokens.read())
                assertFalse(model.state.value.isDeleting)
            }
        }
}

class FieldErrorTest {
    @Test
    fun `a problem's errors are read, by where they are`() {
        val problem =
            PuzzleQuestApi.problem(
                400,
                fieldProblemJson(
                    400,
                    "/problems/invalid-request",
                    "body.email" to "Not an email address",
                    "body.phone" to "Not a phone number",
                ),
            )
        assertEquals(
            listOf(
                FieldError("body.email", "Not an email address"),
                FieldError("body.phone", "Not a phone number"),
            ),
            problem.errors,
        )
    }
}
