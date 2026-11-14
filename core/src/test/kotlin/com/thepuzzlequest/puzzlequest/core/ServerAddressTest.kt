package com.thepuzzlequest.puzzlequest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The refusal that [read] throws. */
private fun refusal(read: () -> Unit): ServerAddressRefusal =
    assertThrows(ServerAddressRefused::class.java, read).reason

class ServerOriginTest {
    @Test
    fun `addresses are reduced to their origin`() {
        mapOf(
            "https://thepuzzlequest.com" to "https://thepuzzlequest.com",
            "https://thepuzzlequest.com/" to "https://thepuzzlequest.com",
            "  HTTPS://ThePuzzleQuest.COM  " to "https://thepuzzlequest.com",
            "thepuzzlequest.com" to "https://thepuzzlequest.com",
            "staging.thepuzzlequest.com" to "https://staging.thepuzzlequest.com",
            "https://thepuzzlequest.com:443" to "https://thepuzzlequest.com",
            "http://localhost:80/" to "http://localhost",
            "http://localhost:3000" to "http://localhost:3000",
            "http://192.168.1.20:3000" to "http://192.168.1.20:3000",
            "http://[::1]:3000" to "http://[::1]:3000",
        ).forEach { (typed, origin) -> assertEquals(typed, origin, ServerOrigin.parse(typed).url) }
    }

    @Test
    fun `Production and This computer, typed, are those servers`() {
        assertEquals(ServerOrigin.PRODUCTION, ServerOrigin.parse("https://thepuzzlequest.com/"))
        assertEquals(ServerOrigin.THIS_COMPUTER, ServerOrigin.parse("http://localhost:3000"))
    }

    @Test
    fun `anything but an origin is refused`() {
        mapOf(
            "" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "   " to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "not an address" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://evil%2ethepuzzlequest.com" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://.thepuzzlequest.com" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://thepuzzlequest..com" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://-x.thepuzzlequest.com" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://thepuzzlequest.com:99999" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://the_puzzlequest.com" to ServerAddressRefusal.NOT_AN_ADDRESS,
            "ftp://thepuzzlequest.com" to ServerAddressRefusal.NOT_A_WEB_ADDRESS,
            "javascript://thepuzzlequest.com" to ServerAddressRefusal.NOT_A_WEB_ADDRESS,
            "https://user@thepuzzlequest.com" to ServerAddressRefusal.HAS_USER_INFO,
            "https://user:secret@thepuzzlequest.com" to ServerAddressRefusal.HAS_USER_INFO,
            "https://thepuzzlequest.com@evil.example" to ServerAddressRefusal.HAS_USER_INFO,
            "https://thepuzzlequest.com/api/v1" to ServerAddressRefusal.NOT_JUST_THE_SERVER,
            "https://thepuzzlequest.com?x=1" to ServerAddressRefusal.NOT_JUST_THE_SERVER,
            "https://thepuzzlequest.com/#top" to ServerAddressRefusal.NOT_JUST_THE_SERVER,
        ).forEach { (typed, expected) -> assertEquals(typed, expected, refusal { ServerOrigin.parse(typed) }) }
    }

    @Test
    fun `it's named by its host, and its port if it has one`() {
        mapOf(
            "https://thepuzzlequest.com" to "thepuzzlequest.com",
            "https://staging.thepuzzlequest.com/" to "staging.thepuzzlequest.com",
            "http://localhost:3000" to "localhost:3000",
        ).forEach { (typed, name) -> assertEquals(typed, name, ServerOrigin.parse(typed).displayName) }
    }

    @Test
    fun `its privacy policy is on its website`() {
        assertEquals("https://thepuzzlequest.com/policies/privacy.html", ServerOrigin.PRODUCTION.privacyPolicy)
        assertEquals(
            "http://localhost:3000/policies/privacy.html",
            ServerOrigin.parse("http://localhost:3000").privacyPolicy,
        )
    }
}

class ServerAddressPolicyTest {
    @Test
    fun `release builds allow PuzzleQuest's own servers over HTTPS`() {
        listOf(
            "https://thepuzzlequest.com",
            "thepuzzlequest.com",
            "https://staging.thepuzzlequest.com",
            "https://a.b.thepuzzlequest.com",
            "https://THEPUZZLEQUEST.COM:443/",
        ).forEach { typed -> assertEquals(typed, "https", ServerAddressPolicy.PUZZLE_QUEST_DOMAIN.check(typed).scheme) }
    }

    @Test
    fun `release builds refuse anything else`() {
        mapOf(
            "http://thepuzzlequest.com" to ServerAddressRefusal.NOT_SECURE,
            "http://staging.thepuzzlequest.com" to ServerAddressRefusal.NOT_SECURE,
            "https://thepuzzlequest.com:8443" to ServerAddressRefusal.HAS_PORT,
            "https://staging.thepuzzlequest.com:3000" to ServerAddressRefusal.HAS_PORT,
            "https://thepuzzlequest.com.evil.example" to ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER,
            "https://evilthepuzzlequest.com" to ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER,
            "https://thepuzzlequest.co" to ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER,
            "https://thepuzzlequest.com." to ServerAddressRefusal.NOT_AN_ADDRESS,
            "https://example.com" to ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER,
            "https://localhost" to ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER,
            "http://localhost:3000" to ServerAddressRefusal.NOT_SECURE,
            "https://203.0.113.7" to ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER,
            "https://[2001:db8::1]" to ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER,
            "https://user@thepuzzlequest.com" to ServerAddressRefusal.HAS_USER_INFO,
            "https://thepuzzlequest.com?next=evil.example" to ServerAddressRefusal.NOT_JUST_THE_SERVER,
            "https://thepuzzlequest.com#evil.example" to ServerAddressRefusal.NOT_JUST_THE_SERVER,
        ).forEach { (typed, expected) ->
            assertEquals(typed, expected, refusal { ServerAddressPolicy.PUZZLE_QUEST_DOMAIN.check(typed) })
        }
    }

    @Test
    fun `debug builds allow any http or https server`() {
        listOf(
            "http://localhost:3000",
            "http://192.168.1.20:3000",
            "https://example.com",
            "http://thepuzzlequest.com",
            "https://thepuzzlequest.com:8443",
            "http://[::1]:3000",
        ).forEach { ServerAddressPolicy.ANY_ADDRESS.check(it) }
    }

    @Test
    fun `debug builds still refuse what isn't a server's address`() {
        mapOf(
            "ftp://example.com" to ServerAddressRefusal.NOT_A_WEB_ADDRESS,
            "http://user:pw@localhost:3000" to ServerAddressRefusal.HAS_USER_INFO,
            "http://localhost:3000/api" to ServerAddressRefusal.NOT_JUST_THE_SERVER,
            "" to ServerAddressRefusal.NOT_AN_ADDRESS,
        ).forEach { (typed, expected) ->
            assertEquals(typed, expected, refusal { ServerAddressPolicy.ANY_ADDRESS.check(typed) })
        }
    }

    @Test
    fun `both builds allow Production`() {
        ServerAddressPolicy.PUZZLE_QUEST_DOMAIN.admit(ServerOrigin.PRODUCTION)
        ServerAddressPolicy.ANY_ADDRESS.admit(ServerOrigin.PRODUCTION)
        assertEquals(
            ServerAddressRefusal.NOT_SECURE,
            refusal { ServerAddressPolicy.PUZZLE_QUEST_DOMAIN.admit(ServerOrigin.THIS_COMPUTER) },
        )
    }
}
