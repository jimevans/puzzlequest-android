package com.thepuzzlequest.puzzlequest.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a new player gives to sign up, as the website's form asks. */
data class NewAccount(
    val userName: String,
    val password: String,
    val displayName: String,
    val email: String,
    /** Optional: US and Canadian numbers only, for now. */
    val phone: String? = null,
    /** Whether the player agrees to text messages, with the consent shown. */
    val sms: Boolean = false,
)

/** The sign-up form's fields, by the names the API gives them. */
enum class SignUpField(
    val apiName: String,
) {
    USER_NAME("userName"),
    PASSWORD("password"),
    CONFIRM_PASSWORD("confirmPassword"),
    DISPLAY_NAME("displayName"),
    EMAIL("email"),
    PHONE("phone"),
    ;

    companion object {
        /** The field a server's error is about, by where it is, such as `body.email`. */
        fun at(path: String): SignUpField? = entries.firstOrNull { "body.${it.apiName}" == path }
    }
}

/** What's wrong with a field: found here before sending, or said by the server. */
sealed interface FieldProblem {
    data object Required : FieldProblem

    /** The password is shorter than the server allows. */
    data object PasswordTooShort : FieldProblem

    /** The two passwords typed differ. */
    data object PasswordsDiffer : FieldProblem

    /** The server's own words. */
    data class Server(
        val message: String,
    ) : FieldProblem
}

/**
 * The sign-up screen's state: what's typed, what's wrong with it, and whether
 * it's being sent. Signing up signs the new player straight in.
 */
class SignUpModel(
    private val session: SessionStore,
) {
    data class Form(
        val userName: String = "",
        val password: String = "",
        val confirmPassword: String = "",
        val displayName: String = "",
        val email: String = "",
        val phone: String = "",
        val sms: Boolean = false,
    )

    data class State(
        val form: Form = Form(),
        val isSigningUp: Boolean = false,
        /** What's wrong with each field, from the last try. */
        val fieldProblems: Map<SignUpField, FieldProblem> = emptyMap(),
        /** Why the last try failed, when it isn't about a field. */
        val failure: SignUpFailure? = null,
        /**
         * How many tries have ended with something wrong with a field: the
         * screen moves the keyboard to the first such field when this
         * changes, and only then, not as fields are edited (which clears
         * what's wrong with them).
         */
        val fieldProblemsShown: Int = 0,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Changes what's typed; what was said about the fields changed is
     * cleared.
     */
    fun edit(change: (Form) -> Form) {
        mutableState.update { state ->
            val form = change(state.form)
            val changed = changedFields(state.form, form)
            state.copy(form = form, fieldProblems = state.fieldProblems - changed)
        }
    }

    private fun changedFields(
        before: Form,
        after: Form,
    ): Set<SignUpField> =
        buildSet {
            if (before.userName != after.userName) add(SignUpField.USER_NAME)
            if (before.password != after.password) add(SignUpField.PASSWORD)
            if (before.confirmPassword != after.confirmPassword) add(SignUpField.CONFIRM_PASSWORD)
            if (before.displayName != after.displayName) add(SignUpField.DISPLAY_NAME)
            if (before.email != after.email) add(SignUpField.EMAIL)
            if (before.phone != after.phone) add(SignUpField.PHONE)
        }

    /**
     * Checks the fields as the website does before sending: what's required,
     * the password's length, and that the two passwords match.
     */
    private fun check(form: Form): Map<SignUpField, FieldProblem> =
        buildMap {
            if (form.userName.isBlank()) put(SignUpField.USER_NAME, FieldProblem.Required)
            if (form.password.length < MINIMUM_PASSWORD_LENGTH) {
                put(SignUpField.PASSWORD, FieldProblem.PasswordTooShort)
            }
            if (form.confirmPassword != form.password) {
                put(SignUpField.CONFIRM_PASSWORD, FieldProblem.PasswordsDiffer)
            }
            if (form.displayName.isBlank()) put(SignUpField.DISPLAY_NAME, FieldProblem.Required)
            if (form.email.isBlank()) put(SignUpField.EMAIL, FieldProblem.Required)
        }

    /**
     * Signs up, then signs in as the new player. A refusal about a field is
     * shown at the field; anything else, above the form.
     *
     * @return whether it signed in.
     */
    suspend fun signUp(): Boolean {
        val current = state.value
        if (current.isSigningUp) return false
        val form = current.form
        val problems = check(form)
        mutableState.value = current.copy(fieldProblems = problems, failure = null)
        if (problems.isNotEmpty()) {
            mutableState.update { it.copy(fieldProblemsShown = it.fieldProblemsShown + 1) }
            return false
        }

        mutableState.update { it.copy(isSigningUp = true) }
        val phone = form.phone.trim()
        val account =
            NewAccount(
                userName = form.userName.trim(),
                password = form.password,
                displayName = form.displayName.trim(),
                email = form.email.trim(),
                phone = phone.ifEmpty { null },
                sms = form.sms,
            )
        try {
            session.api.signUp(account)
        } catch (error: ApiError) {
            val fields = (error as? ApiError.Refused)?.let { fieldProblems(it.problem) }.orEmpty()
            mutableState.update {
                if (fields.isNotEmpty()) {
                    it.copy(isSigningUp = false, fieldProblems = fields, fieldProblemsShown = it.fieldProblemsShown + 1)
                } else {
                    it.copy(isSigningUp = false, failure = SignUpFailure.of(error))
                }
            }
            return false
        }
        // The account is made; if signing in fails now, they can sign in.
        return try {
            session.signIn(account.userName, account.password)
            mutableState.update { it.copy(isSigningUp = false) }
            true
        } catch (_: ApiError) {
            mutableState.update { it.copy(isSigningUp = false, failure = SignUpFailure.SignedUpButNotSignedIn) }
            false
        }
    }

    companion object {
        /** The server's rule is the one that counts; this just answers sooner. */
        const val MINIMUM_PASSWORD_LENGTH = 8

        /** What the server said about each field. */
        internal fun fieldProblems(problem: Problem): Map<SignUpField, FieldProblem> {
            val problems =
                problem.errors
                    .mapNotNull { error ->
                        SignUpField.at(error.path)?.let { it to FieldProblem.Server(error.message) }
                    }.toMap()
            // A password the server won't take, without saying where.
            if (problems.isEmpty() && problem.type == Problem.Types.PASSWORD_NOT_ALLOWED) {
                return mapOf(SignUpField.PASSWORD to FieldProblem.Server(problem.detail ?: problem.title))
            }
            return problems
        }
    }
}

/** Why signing up failed, when it isn't about a field. */
sealed interface SignUpFailure {
    /**
     * The user name or email address is already in use; the server's words
     * say which, as the website shows them.
     */
    data class AlreadyInUse(
        val message: String,
    ) : SignUpFailure

    /** Too many tries in a short time. */
    data object TooManyAttempts : SignUpFailure

    data object Unreachable : SignUpFailure

    /** The account was made, but signing in then failed: sign in with it. */
    data object SignedUpButNotSignedIn : SignUpFailure

    data object Unexpected : SignUpFailure

    companion object {
        fun of(error: ApiError): SignUpFailure =
            when (error) {
                is ApiError.Refused -> {
                    when (error.problem.type) {
                        Problem.Types.ALREADY_EXISTS -> AlreadyInUse(error.problem.detail ?: error.problem.title)
                        Problem.Types.TOO_MANY_REQUESTS -> TooManyAttempts
                        else -> Unexpected
                    }
                }

                ApiError.Unreachable -> {
                    Unreachable
                }

                is ApiError.UnexpectedResponse -> {
                    Unexpected
                }
            }
    }
}

/**
 * Deleting the signed-in player's own account, once they've confirmed it with
 * their password. The screen keeps the password, and passes it in.
 */
class DeleteAccountModel(
    private val session: SessionStore,
) {
    data class State(
        val isDeleting: Boolean = false,
        val failure: DeleteAccountFailure? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun canDelete(password: String): Boolean = password.isNotEmpty() && !state.value.isDeleting

    /**
     * Deletes the account. On success, the session is gone and the app is
     * signed out.
     *
     * @return whether it was deleted.
     */
    suspend fun delete(password: String): Boolean {
        if (!canDelete(password)) return false
        mutableState.value = State(isDeleting = true)
        return try {
            session.deleteAccount(password)
            mutableState.value = State()
            true
        } catch (error: ApiError) {
            mutableState.value = State(failure = DeleteAccountFailure.of(error))
            false
        }
    }
}

/** Why deleting an account failed. */
enum class DeleteAccountFailure {
    INCORRECT_PASSWORD,

    /** Too many wrong passwords in a short time. */
    TOO_MANY_ATTEMPTS,

    /** It can't be deleted now, as the only administrator's can't. */
    NOT_POSSIBLE,
    UNREACHABLE,
    UNEXPECTED,
    ;

    companion object {
        fun of(error: ApiError): DeleteAccountFailure =
            when (error) {
                is ApiError.Refused -> {
                    when (error.problem.type) {
                        Problem.Types.INCORRECT_PASSWORD -> INCORRECT_PASSWORD
                        Problem.Types.TOO_MANY_REQUESTS -> TOO_MANY_ATTEMPTS
                        Problem.Types.WRONG_STATE -> NOT_POSSIBLE
                        else -> UNEXPECTED
                    }
                }

                ApiError.Unreachable -> {
                    UNREACHABLE
                }

                is ApiError.UnexpectedResponse -> {
                    UNEXPECTED
                }
            }
    }
}
