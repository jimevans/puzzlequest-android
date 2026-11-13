package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.models.User

/** Who is signed in. */
data class SignedInUser(
    val userName: String,
    val displayName: String,
    val role: Role,
)

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
    )
