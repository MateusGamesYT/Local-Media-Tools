package com.localmediatools.print.core

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Thrown when something would leave the local network. */
class NotLocalException(message: String) : IOException(message)

/**
 * The app's network rule: it only ever talks to devices on the local network (printers), never to
 * servers on the internet. Every connection goes through [check]; names are never looked up through
 * DNS (which could reach outside servers): printers are addressed by IP address, found with
 * multicast DNS on the local link.
 */
object LocalNetwork {
    /** True for addresses that can only be on this device or the local network. */
    fun isLocal(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> {
            val b = a.address.map { it.toInt() and 0xFF }
            b[0] == 10 ||                                  // 10.0.0.0/8
                (b[0] == 172 && b[1] in 16..31) ||         // 172.16.0.0/12
                (b[0] == 192 && b[1] == 168) ||            // 192.168.0.0/16
                (b[0] == 169 && b[1] == 254) ||            // link-local
                b[0] == 127                                // this device
        }
        is Inet6Address -> {
            val b = a.address.map { it.toInt() and 0xFF }
            a.isLoopbackAddress ||
                (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) ||   // fe80::/10 link-local
                (b[0] and 0xFE) == 0xFC ||                     // fc00::/7 unique local
                (a.isIPv4CompatibleAddress && isLocal(InetAddress.getByAddress(b.takeLast(4).map { it.toByte() }.toByteArray())))
        }
        else -> false
    }

    /** Throws unless [a] is local. */
    fun check(a: InetAddress): InetAddress {
        if (!isLocal(a)) throw NotLocalException("${a.hostAddress} isn't on your local network; this app only talks to devices on it")
        return a
    }

    /**
     * An IP address typed by the user or taken from a printer address, without any name lookup.
     * Null when [text] isn't an IPv4 or IPv6 address literal.
     */
    fun parseLiteral(text: String): InetAddress? {
        val t = text.trim().removePrefix("[").removeSuffix("]")
        if (t.isEmpty()) return null
        if (IPV4.matches(t)) {
            val parts = t.split('.').map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(parts.map { it.toByte() }.toByteArray())
        }
        // IPv6 literal (with an optional %zone): only hex digits, colons, dots; Java parses literals without lookups.
        val core = t.substringBefore('%')
        if (core.contains(':') && core.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return try { InetAddress.getByName(t) } catch (_: Exception) { null }
        }
        return null
    }

    private val IPV4 = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
}
