package com.thepuzzlequest.puzzlequest

import androidx.test.platform.app.InstrumentationRegistry
import com.thepuzzlequest.puzzlequest.core.ServerOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreTokenVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "com.thepuzzlequest.puzzlequest.test-session"
    private val staging = ServerOrigin.parse("https://staging.thepuzzlequest.com")

    @After
    fun forget() = KeystoreTokenVault(context, name).forgetAll()

    @Test
    fun keepsReplacesAndForgetsTheToken() {
        val store = KeystoreTokenVault(context, name).tokens(ServerOrigin.PRODUCTION)
        assertNull(store.read())
        store.save("first")
        assertEquals("first", store.read())
        store.save("second")
        // Read back by another vault, as at the next launch.
        assertEquals("second", KeystoreTokenVault(context, name).tokens(ServerOrigin.PRODUCTION).read())
        store.delete()
        assertNull(store.read())
    }

    @Test
    fun keepsEachServersTokenApart() {
        val vault = KeystoreTokenVault(context, name)
        vault.tokens(ServerOrigin.PRODUCTION).save("production's")
        vault.tokens(staging).save("staging's")
        assertEquals("production's", vault.tokens(ServerOrigin.PRODUCTION).read())
        assertEquals("staging's", vault.tokens(staging).read())
        vault.tokens(staging).delete()
        assertEquals("production's", vault.tokens(ServerOrigin.PRODUCTION).read())
        vault.forgetAll()
        assertNull(vault.tokens(ServerOrigin.PRODUCTION).read())
    }

    @Test
    fun keepsTheTokensEncrypted() {
        KeystoreTokenVault(context, name).tokens(staging).save("a-secret-token")
        val stored = context.getSharedPreferences(name, 0).all
        assertEquals(1, stored.size)
        assertTrue(stored.values.none { it.toString().contains("a-secret-token") })
    }
}
