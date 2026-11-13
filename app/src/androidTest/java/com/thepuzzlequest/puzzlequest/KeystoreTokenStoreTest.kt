package com.thepuzzlequest.puzzlequest

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeystoreTokenStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "com.thepuzzlequest.puzzlequest.test-session"

    @After
    fun forget() = KeystoreTokenStore(context, name).delete()

    @Test
    fun keepsReplacesAndForgetsTheToken() {
        val store = KeystoreTokenStore(context, name)
        assertNull(store.read())
        store.save("first")
        assertEquals("first", store.read())
        store.save("second")
        // Read back by another instance, as at the next launch.
        assertEquals("second", KeystoreTokenStore(context, name).read())
        store.delete()
        assertNull(store.read())
    }

    @Test
    fun keepsTheTokenEncrypted() {
        KeystoreTokenStore(context, name).save("a-secret-token")
        val stored = context.getSharedPreferences(name, 0).getString("token", null)!!
        assertNotEquals("a-secret-token", stored)
        assert(!stored.contains("a-secret-token"))
    }
}
