package com.thepuzzlequest.puzzlequest.core

/** Where the session's token is kept between launches. */
interface TokenStore {
    fun read(): String?

    fun save(token: String)

    fun delete()
}

/**
 * A session's token for each server, kept apart so that one server's token
 * is never sent to another.
 */
interface TokenVault {
    /** Where the token for a server is kept. */
    fun tokens(server: ServerOrigin): TokenStore

    /** Forgets every server's token. */
    fun forgetAll()
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

/** Each server's token kept only in memory, for tests and previews. */
class InMemoryTokenVault(
    tokens: Map<ServerOrigin, String> = emptyMap(),
) : TokenVault {
    private val stores = tokens.mapValuesTo(mutableMapOf()) { InMemoryTokenStore(it.value) }

    @Synchronized
    override fun tokens(server: ServerOrigin): TokenStore = stores.getOrPut(server) { InMemoryTokenStore() }

    @Synchronized
    override fun forgetAll() = stores.values.forEach { it.delete() }
}
