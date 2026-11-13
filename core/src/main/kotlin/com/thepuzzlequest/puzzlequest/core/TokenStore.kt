package com.thepuzzlequest.puzzlequest.core

/** Where the session's token is kept between launches. */
interface TokenStore {
    fun read(): String?

    fun save(token: String)

    fun delete()
}

/** A token kept only in memory, for tests and previews. */
class InMemoryTokenStore(
    @Volatile private var token: String? = null,
) : TokenStore {
    override fun read(): String? = token

    override fun save(token: String) {
        this.token = token
    }

    override fun delete() {
        token = null
    }
}
