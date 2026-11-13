package com.thepuzzlequest.puzzlequest.core

import com.thepuzzlequest.puzzlequest.api.infrastructure.Serializer
import kotlinx.serialization.json.Json

/**
 * The JSON the generated client reads and writes, set up once before its
 * first use (the generated `Serializer` refuses changes after that). Every
 * use goes through here, so that's always so.
 *
 * - An optional field that isn't set is left out of a request, rather than
 *   sent as null: the API takes null for a field to mean "set it to null",
 *   and refuses it where that isn't allowed (such as a user's phone).
 * - Responses ignore fields they don't know (the generator's own setting),
 *   since version 1 of the API may add them.
 */
object ApiJson {
    init {
        Serializer.kotlinxSerializationJsonConfiguration = { explicitNulls = false }
    }

    val json: Json = Serializer.kotlinxSerializationJson

    /** Sets it up, if it isn't already: call before the generated classes are first used. */
    fun setUp() = Unit
}
