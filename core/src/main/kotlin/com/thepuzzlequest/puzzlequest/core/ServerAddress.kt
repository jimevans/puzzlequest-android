package com.thepuzzlequest.puzzlequest.core

import java.net.URI
import java.net.URISyntaxException

/**
 * A PuzzleQuest server, by its origin: a scheme, a host, and a port only when
 * it isn't the scheme's usual one, such as https://thepuzzlequest.com. The
 * API is under its `/api/v1`, and its website at the origin itself.
 */
class ServerOrigin private constructor(
    val scheme: String,
    /** The host, in lower case; an IPv6 address keeps its brackets. */
    val host: String,
    /** The port, or null for the scheme's usual one. */
    val port: Int?,
) {
    /** The origin as an address, with nothing after the host and port (not even a `/`). */
    val url: String = "$scheme://$displayName"

    /**
     * How the server is named to people: its host, with its port when it has
     * one, such as staging.thepuzzlequest.com or localhost:3000.
     */
    val displayName: String get() = if (port == null) host else "$host:$port"

    /** The server's privacy policy, on its website. */
    val privacyPolicy: String get() = "$url/policies/privacy.html"

    // Two origins are the same server when their addresses are.
    override fun equals(other: Any?) = other is ServerOrigin && other.url == url

    override fun hashCode() = url.hashCode()

    override fun toString() = url

    companion object {
        /** PuzzleQuest's own site, the server every build starts with. */
        val PRODUCTION = ServerOrigin("https", "thepuzzlequest.com", null)

        /**
         * A server running on the computer the phone is connected to, as the
         * server repository's `npm run dev` starts one, reached through
         * `adb reverse tcp:3000 tcp:3000`; for debug builds.
         */
        val THIS_COMPUTER = ServerOrigin("http", "localhost", 3000)

        /**
         * Reads a server's address as a person types it: a missing scheme is
         * taken to be `https://`, a final `/` and the scheme's usual port are
         * dropped, and the scheme and host are put in lower case. Anything but
         * the origin (a path, a query, a fragment) or a user name and password
         * is refused, as is anything that isn't an http or https address. This
         * says nothing about whether the server may be used: that's a
         * [ServerAddressPolicy]'s to decide.
         *
         * @throws ServerAddressRefused
         */
        fun parse(text: String): ServerOrigin {
            var address = text.trim()
            if (address.isEmpty()) throw ServerAddressRefused(ServerAddressRefusal.NOT_AN_ADDRESS)
            if (!address.contains("://")) address = "https://$address"
            val uri =
                try {
                    URI(address)
                } catch (_: URISyntaxException) {
                    throw ServerAddressRefused(ServerAddressRefusal.NOT_AN_ADDRESS)
                }
            val scheme = uri.scheme?.lowercase() ?: throw ServerAddressRefused(ServerAddressRefusal.NOT_AN_ADDRESS)
            if (scheme != "https" &&
                scheme != "http"
            ) {
                throw ServerAddressRefused(ServerAddressRefusal.NOT_A_WEB_ADDRESS)
            }
            if (uri.rawUserInfo != null) throw ServerAddressRefused(ServerAddressRefusal.HAS_USER_INFO)
            // URI leaves the host null when the part after `//` isn't a host
            // and port, such as "evil%2ethepuzzlequest.com".
            val host = uri.host?.lowercase()
            if (host.isNullOrEmpty()) throw ServerAddressRefused(ServerAddressRefusal.NOT_AN_ADDRESS)
            val path = uri.rawPath.orEmpty()
            if ((path.isNotEmpty() && path != "/") || uri.rawQuery != null || uri.rawFragment != null) {
                throw ServerAddressRefused(ServerAddressRefusal.NOT_JUST_THE_SERVER)
            }
            if (!isPlainHost(host)) throw ServerAddressRefused(ServerAddressRefusal.NOT_AN_ADDRESS)
            val usualPort = if (scheme == "https") 443 else 80
            val port = uri.port.takeIf { it != -1 && it != usualPort }
            if (port != null && port !in 1..65535) throw ServerAddressRefused(ServerAddressRefusal.NOT_AN_ADDRESS)
            return ServerOrigin(scheme, host, port)
        }

        /**
         * Whether a host is a name of dot-separated labels (letters, digits,
         * and hyphens), an IPv4 address (which is one of those), or a
         * bracketed IPv6 address. Percent-escapes and other characters, which
         * could disguise one host as another, aren't allowed.
         */
        private fun isPlainHost(host: String): Boolean {
            if (host.startsWith("[") && host.endsWith("]")) {
                return host.drop(1).dropLast(1).all { it.isAsciiHexDigit() || it == ':' || it == '.' }
            }
            return host.split(".").all { label ->
                label.isNotEmpty() &&
                    label.first() != '-' &&
                    label.last() != '-' &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }
        }

        private fun Char.isAsciiHexDigit() = this in '0'..'9' || this in 'a'..'f'
    }
}

/** Which servers a build of the app may use. */
enum class ServerAddressPolicy {
    /**
     * Release builds: only PuzzleQuest's own servers, over HTTPS on the usual
     * port: thepuzzlequest.com and its subdomains.
     */
    PUZZLE_QUEST_DOMAIN,

    /** Debug builds: any http or https address, such as a server on this computer. */
    ANY_ADDRESS,
    ;

    /**
     * Reads an address as a person typed it, and checks the policy allows it.
     *
     * @throws ServerAddressRefused
     */
    fun check(text: String): ServerOrigin = ServerOrigin.parse(text).also(::admit)

    /**
     * Checks the policy allows a server.
     *
     * Today the release policy is an allowlist. If servers outside
     * PuzzleQuest's domain are wanted later, one could instead present a
     * signed statement of its origin (as the iOS app's decisions describe):
     * that would be a second, asynchronous way past
     * [ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER], checked by the caller
     * when this throws it, alongside the allowlist rather than in place of
     * it. The other refusals (HTTPS, the port) would still apply.
     *
     * @throws ServerAddressRefused
     */
    fun admit(origin: ServerOrigin) {
        when (this) {
            ANY_ADDRESS -> {
                return
            }

            PUZZLE_QUEST_DOMAIN -> {
                if (origin.scheme != "https") throw ServerAddressRefused(ServerAddressRefusal.NOT_SECURE)
                if (origin.port != null) throw ServerAddressRefused(ServerAddressRefusal.HAS_PORT)
                if (origin.host != DOMAIN && !origin.host.endsWith(".$DOMAIN")) {
                    throw ServerAddressRefused(ServerAddressRefusal.NOT_PUZZLE_QUEST_SERVER)
                }
            }
        }
    }

    companion object {
        /** PuzzleQuest's domain; its subdomains are its too. */
        const val DOMAIN = "thepuzzlequest.com"
    }
}

/** Why an address can't be used as the server. */
enum class ServerAddressRefusal {
    /** It isn't a web address at all. */
    NOT_AN_ADDRESS,

    /** It's an address, but not an http or https one. */
    NOT_A_WEB_ADDRESS,

    /** It has a user name or password in it. */
    HAS_USER_INFO,

    /** It has more than the server: a path, a query, or a fragment. */
    NOT_JUST_THE_SERVER,

    /** Release builds: it isn't https. */
    NOT_SECURE,

    /** Release builds: it names a port other than HTTPS's usual one. */
    HAS_PORT,

    /** Release builds: it isn't thepuzzlequest.com or one of its subdomains. */
    NOT_PUZZLE_QUEST_SERVER,
}

/** An address that can't be used as the server, and why. */
class ServerAddressRefused(
    val reason: ServerAddressRefusal,
) : Exception(reason.name)
