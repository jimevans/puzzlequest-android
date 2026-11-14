package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Signing up and deleting an account, against the real server started by
 * scripts/start-test-server.sh. Load .test-server/env into the environment
 * first; without it these are skipped.
 */
class AccountContractTest {
    @Before
    fun needsTheTestServer() {
        assumeTrue("Set PQ_TEST_SERVER_URL (see scripts/start-test-server.sh)", TestSite.url != null)
    }

    /** A session with the test server, signed out, and its model for signing up. */
    private fun signUpModel(): Pair<SignUpModel, SessionStore> {
        val tokens = InMemoryTokenStore()
        val session = SessionStore(TestSite.api(tokens), tokens)
        return SignUpModel(session) to session
    }

    private fun SignUpModel.fill(
        userName: String,
        email: String,
    ) = edit {
        it.copy(
            userName = userName,
            password = TestSite.password,
            confirmPassword = TestSite.password,
            displayName = "Player $userName",
            email = email,
        )
    }

    @Test
    fun `signing up makes the account and signs in as the new player, whose email isn't verified yet`() =
        runTest {
            val (model, session) = signUpModel()
            val userName = TestSite.unique("newbie")
            model.fill(userName, "$userName@example.com")
            assertTrue(model.signUp())
            assertNull(model.state.value.failure)
            assertTrue(
                model.state.value.fieldProblems
                    .isEmpty(),
            )
            val user = (session.state.value as? SessionStore.State.SignedIn)?.user ?: fail("not signed in") as Nothing
            assertEquals(userName, user.userName)
            assertEquals("Player $userName", user.displayName)
            assertEquals(Role.Player, user.role)
            assertFalse(user.emailVerified)
        }

    @Test
    fun `a user name or email address already in use is said to be, in the server's words`() =
        runTest {
            val (first, _) = signUpModel()
            val userName = TestSite.unique("taken")
            first.fill(userName, "$userName@example.com")
            assertTrue(first.signUp())

            val (sameName, _) = signUpModel()
            sameName.fill(userName, "other-$userName@example.com")
            sameName.signUp()
            val nameTaken = sameName.state.value.failure as? SignUpFailure.AlreadyInUse
            assertTrue("the user name wasn't refused: ${sameName.state.value.failure}", nameTaken != null)
            assertTrue(nameTaken!!.message, nameTaken.message.contains("user name", ignoreCase = true))

            val (sameEmail, _) = signUpModel()
            sameEmail.fill(TestSite.unique("other"), "$userName@example.com")
            sameEmail.signUp()
            val emailTaken = sameEmail.state.value.failure as? SignUpFailure.AlreadyInUse
            assertTrue("the email address wasn't refused: ${sameEmail.state.value.failure}", emailTaken != null)
            assertTrue(emailTaken!!.message, emailTaken.message.contains("email", ignoreCase = true))
        }

    @Test
    fun `deleting needs the right password, then signs out for good`() =
        runTest {
            val (signUp, session) = signUpModel()
            val userName = TestSite.unique("leaving")
            signUp.fill(userName, "$userName@example.com")
            assertTrue(signUp.signUp())

            val delete = DeleteAccountModel(session)
            assertFalse(delete.delete("not the password"))
            assertEquals(DeleteAccountFailure.INCORRECT_PASSWORD, delete.state.value.failure)
            assertTrue(session.state.value is SessionStore.State.SignedIn)

            assertTrue(delete.delete(TestSite.password))
            assertNull(delete.state.value.failure)
            assertEquals(SessionStore.State.SignedOut, session.state.value)

            // Gone: its name and password no longer sign in.
            try {
                session.signIn(userName, TestSite.password)
                fail("the deleted account signed in")
            } catch (_: ApiError) {
            }
        }
}
