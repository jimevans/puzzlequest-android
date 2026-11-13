package com.thepuzzlequest.puzzlequest.core

/** Why a call to the API didn't succeed. */
sealed class ApiError(
    message: String,
) : Exception(message) {
    /** The server refused the request and said why, as problem details. */
    data class Refused(
        val problem: Problem,
    ) : ApiError("${problem.status} ${problem.type}: ${problem.title}")

    /** The server couldn't be reached: no network, or the server is down. */
    data object Unreachable : ApiError("the server couldn't be reached") {
        private fun readResolve(): Any = Unreachable
    }

    /** The server answered with something this version of the app can't read. */
    data class UnexpectedResponse(
        val detail: String,
    ) : ApiError(detail)
}

/**
 * A refusal from the API, as RFC 9457 problem details. Act on its [type]; the
 * title and detail are the server's words, for logs rather than people.
 */
data class Problem(
    /**
     * One of the types the API's description lists, such as
     * `/problems/sign-in-failed`, or `about:blank` for a plain HTTP error.
     */
    val type: String,
    val status: Int,
    val title: String,
    val detail: String? = null,
) {
    /**
     * The problem types the app acts on. Version 1 may add others, so code
     * that switches on a type always has an `else`.
     */
    object Types {
        const val INVALID_REQUEST = "/problems/invalid-request"
        const val SIGN_IN_REQUIRED = "/problems/sign-in-required"
        const val SIGN_IN_FAILED = "/problems/sign-in-failed"
        const val TOO_MANY_REQUESTS = "/problems/too-many-requests"
        const val UNEXPECTED = "/problems/unexpected"
        const val WRONG_STATE = "/problems/wrong-state"
        const val QUEST_PAUSED = "/problems/quest-paused"
        const val TRY_AGAIN = "/problems/try-again"
        const val WRONG_ACTIVATION_CODE = "/problems/wrong-activation-code"
        const val WRONG_ANSWER = "/problems/wrong-answer"
        const val NO_MORE_HINTS = "/problems/no-more-hints"
    }
}
