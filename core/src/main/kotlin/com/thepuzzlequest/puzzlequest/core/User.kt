package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.models.User

/** Who is signed in. */
data class SignedInUser(
    val userName: String,
    val displayName: String,
    val role: Role,
    /** Whether they've followed the link in the email sent to their address. */
    val emailVerified: Boolean,
) {
    /** What the app shows them once signed in. */
    val homeScreen: HomeScreen get() = HomeScreen.of(role)
}

/**
 * What a user may do. Version 1 may add roles, which the app shows as
 * [Other] rather than failing.
 */
sealed interface Role {
    data object Player : Role

    data object Staff : Role

    data object Admin : Role

    data object Other : Role
}

/**
 * What the app shows someone once they're signed in. The app is for players;
 * staff and administrators have their tools on the website.
 */
enum class HomeScreen {
    /** Their quests. */
    QUESTS,

    /** A note that their tools are on the website. */
    WEBSITE,
    ;

    companion object {
        fun of(role: Role): HomeScreen =
            when (role) {
                Role.Player -> QUESTS

                // A role added later is more likely one with tools of its own
                // (players are everyone's starting role), and the website is
                // where those are.
                Role.Staff, Role.Admin, Role.Other -> WEBSITE
            }
    }
}

internal fun SignedInUser(user: User) =
    SignedInUser(
        userName = user.userName,
        displayName = user.displayName,
        role =
            when (user.role) {
                com.thepuzzlequest.puzzlequest.api.models.Role.player -> Role.Player
                com.thepuzzlequest.puzzlequest.api.models.Role.staff -> Role.Staff
                com.thepuzzlequest.puzzlequest.api.models.Role.admin -> Role.Admin
                com.thepuzzlequest.puzzlequest.api.models.Role.unknown_default_open_api -> Role.Other
            },
        emailVerified = user.emailVerified,
    )
