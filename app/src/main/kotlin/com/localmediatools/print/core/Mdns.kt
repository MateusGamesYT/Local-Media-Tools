package com.localmediatools.print.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** A DNS resource record from an mDNS answer. */
sealed class DnsRecord(val name: String, val ttl: Int) {
    class Ptr(name: String, ttl: Int, val target: String) : DnsRecord(name, ttl)
    class Srv(name: String, ttl: Int, val priority: Int, val weight: Int, val port: Int, val target: String) : DnsRecord(name, ttl)
    class Txt(name: String, ttl: Int, val entries: Map<String, String>) : DnsRecord(name, ttl)
    class Addr(name: String, ttl: Int, val address: InetAddress) : DnsRecord(name, ttl)
    class Other(name: String, ttl: Int, val type: Int) : DnsRecord(name, ttl)
}

/** A printer announced on the local network (DNS-SD). */
data class FoundPrinter(
    /** Service instance name, e.g. "EPSON L3250 Series". */
    val name: String,
    val address: PrinterAddress,
    /** TXT record: ty (model), rp (resource path), pdl (formats), UUID, Color, Duplex, note… */
    val txt: Map<String, String>,
    /** The same printer's unencrypted (ipp) service, if it announces both: used when TLS fails. */
    val fallback: PrinterAddress? = null,
) {
    val model: String get() = txt["ty"] ?: txt["product"]?.trim('(', ')') ?: name
    val uuid: String? get() = txt["UUID"]?.lowercase()?.removePrefix("urn:uuid:")
    /** One printer announces ipp and ipps under the same instance name. */
    val identity: String get() = name.lowercase()
}

/**
 * Multicast DNS (RFC 6762) and DNS-SD (RFC 6763), just enough to find IPP printers on the local
 * link. Queries go to 224.0.0.251:5353 (local link only, never routed) from an ordinary port, so
 * printers answer directly ("legacy unicast"); answers multicast to the group are heard too when
 * port 5353 can be shared.
 */
object Mdns {
    const val PORT = 5353
    val GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
    const val IPP = "_ipp._tcp.local"
    const val IPPS = "_ipps._tcp.local"

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    private const val CLASS_IN = 1
    /** In questions: please answer by unicast. */
    private const val QU = 0x8000

    /** A query for [names] with record [type]. */
    fun query(names: List<String>, type: Int = TYPE_PTR, id: Int = 0): ByteArray {
        val b = ByteArrayOutputStream(); val o = DataOutputStream(b)
        o.writeShort(id); o.writeShort(0); o.writeShort(names.size); o.writeShort(0); o.writeShort(0); o.writeShort(0)
        for (n in names) { writeName(o, n); o.writeShort(type); o.writeShort(CLASS_IN or QU) }
        return b.toByteArray()
    }

    private fun writeName(o: DataOutputStream, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            o.writeByte(bytes.size); o.write(bytes)
        }
        o.writeByte(0)
    }

    /** All records of a response packet (answers, authority and additional sections). */
    fun parse(packet: ByteArray, length: Int = packet.size): List<DnsRecord> {
        val b = ByteBuffer.wrap(packet, 0, length)
        if (length < 12) return emptyList()
        b.short; val flags = b.short.toInt() and 0xFFFF
        if (flags and 0x8000 == 0) return emptyList() // a query, not a response
        val qd = b.short.toInt() and 0xFFFF; val an = b.short.toInt() and 0xFFFF
        val ns = b.short.toInt() and 0xFFFF; val ar = b.short.toInt() and 0xFFFF
        val out = ArrayList<DnsRecord>()
        try {
            repeat(qd) { readName(packet, b, length); b.position(b.position() + 4) }
            repeat(an + ns + ar) {
                val name = readName(packet, b, length)
                val type = b.short.toInt() and 0xFFFF
                b.short // class (with cache-flush bit)
                val ttl = b.int
                val len = b.short.toInt() and 0xFFFF
                val start = b.position()
                if (start + len > length) return out
                out.add(when (type) {
                    TYPE_PTR -> DnsRecord.Ptr(name, ttl, readName(packet, b, length))
                    TYPE_SRV -> DnsRecord.Srv(name, ttl, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, readName(packet, b, length))
                    TYPE_TXT -> DnsRecord.Txt(name, ttl, readTxt(packet, start, len))
                    TYPE_A -> if (len == 4) DnsRecord.Addr(name, ttl, InetAddress.getByAddress(packet.copyOfRange(start, start + 4))) else DnsRecord.Other(name, ttl, type)
                    TYPE_AAAA -> if (len == 16) DnsRecord.Addr(name, ttl, InetAddress.getByAddress(packet.copyOfRange(start, start + 16))) else DnsRecord.Other(name, ttl, type)
                    else -> DnsRecord.Other(name, ttl, type)
                })
                b.position(start + len)
            }
        } catch (_: RuntimeException) {
            // A malformed or truncated packet: keep what was read.
        }
        return out
    }

    private fun readTxt(p: ByteArray, start: Int, len: Int): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        var i = start
        while (i < start + len) {
            val n = p[i].toInt() and 0xFF; i++
            if (n == 0 || i + n > start + len) { i += n; continue }
            val s = String(p, i, n, Charsets.UTF_8); i += n
            val k = s.substringBefore('=')
            if (k.isNotEmpty() && k !in m) m[k] = s.substringAfter('=', "")
        }
        return m
    }

    /** Reads a (possibly compressed) domain name at the buffer's position. */
    private fun readName(p: ByteArray, b: ByteBuffer, limit: Int): String {
        val labels = ArrayList<String>()
        var pos = b.position()
        var jumped = false
        var hops = 0
        while (true) {
            if (pos >= limit) throw IndexOutOfBoundsException()
            val len = p[pos].toInt() and 0xFF
            when {
                len == 0 -> { if (!jumped) b.position(pos + 1); break }
                len and 0xC0 == 0xC0 -> {
                    val ptr = ((len and 0x3F) shl 8) or (p[pos + 1].toInt() and 0xFF)
                    if (!jumped) b.position(pos + 2)
                    jumped = true
                    if (++hops > 32 || ptr >= limit) throw IndexOutOfBoundsException()
                    pos = ptr
                }
                else -> {
                    if (pos + 1 + len > limit) throw IndexOutOfBoundsException()
                    labels.add(String(p, pos + 1, len, Charsets.UTF_8))
                    pos += 1 + len
                }
            }
        }
        return labels.joinToString(".")
    }

    /**
     * Turns the records heard so far into printers: PTR (service type → instance), SRV (instance →
     * host, port), TXT and A/AAAA (host → addresses). Only local addresses are kept; IPv4 preferred.
     */
    fun printers(records: List<DnsRecord>): List<FoundPrinter> {
        val instances = LinkedHashMap<String, Boolean>() // instance → tls
        for (r in records) if (r is DnsRecord.Ptr) {
            val type = r.name.lowercase()
            if (type == IPP || type.endsWith("._sub.$IPP")) instances.putIfAbsent(r.target, false)
            if (type == IPPS || type.endsWith("._sub.$IPPS")) instances[r.target] = true
        }
        val srv = records.filterIsInstance<DnsRecord.Srv>().associateBy { it.name.lowercase() }
        val txt = records.filterIsInstance<DnsRecord.Txt>().associateBy { it.name.lowercase() }
        val addrs = records.filterIsInstance<DnsRecord.Addr>().groupBy { it.name.lowercase() }
        val out = ArrayList<FoundPrinter>()
        for ((inst, tls) in instances) {
            val s = srv[inst.lowercase()] ?: continue
            if (s.port == 0) continue
            val host = addrs[s.target.lowercase()].orEmpty().map { it.address }.filter { LocalNetwork.isLocal(it) }
                .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }.firstOrNull() ?: continue
            val t = txt[inst.lowercase()]?.entries.orEmpty()
            val rp = t["rp"]?.trim('/')?.ifEmpty { null } ?: "ipp/print"
            val label = inst.substringBefore("._ipp").replace("\\032", " ").replace("\\ ", " ").replace("\\.", ".")
            out.add(FoundPrinter(label, PrinterAddress(tls, host, s.port, "/$rp"), t))
        }
        return out
    }

    /**
     * Asks the local network for IPP printers for [timeoutMs], calling [found] with the up-to-date
     * list whenever it grows. A printer announcing both ipp and ipps is listed once, as ipps.
     */
    fun discover(timeoutMs: Long = 4000, cancelled: () -> Boolean = { false }, found: (List<FoundPrinter>) -> Unit): List<FoundPrinter> {
        val records = ArrayList<DnsRecord>()
        val sockets = ArrayList<DatagramSocket>()
        // Ordinary port: printers answer it directly (legacy unicast).
        sockets.add(DatagramSocket(0).apply { soTimeout = 150 })
        // The shared mDNS port, if the system lets us join (some printers only answer by multicast).
        try {
            sockets.add(MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)); joinGroup(InetSocketAddress(GROUP, PORT), null); soTimeout = 150 })
        } catch (_: Exception) { }
        var last = emptyList<FoundPrinter>()
        try {
            val q = query(listOf(IPP, IPPS))
            val start = System.currentTimeMillis()
            var nextSend = 0L
            var sends = 0
            val buf = ByteArray(9000)
            while (System.currentTimeMillis() - start < timeoutMs && !cancelled()) {
                val now = System.currentTimeMillis() - start
                if (now >= nextSend && sends < 4) {
                    for (s in sockets.take(1)) try { s.send(DatagramPacket(q, q.size, GROUP, PORT)) } catch (_: IOException) { }
                    sends++; nextSend = now + 250L * (1 shl sends)
                }
                for (s in sockets) {
                    val pk = DatagramPacket(buf, buf.size)
                    try { s.receive(pk) } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { continue }
                    if (!LocalNetwork.isLocal(pk.address)) continue
                    records.addAll(parse(buf.copyOf(pk.length)))
                }
                // Ask for missing addresses or service records of instances heard about.
                val current = merge(printers(records))
                if (current != last) { last = current; found(current) }
            }
            // Instances without SRV/A records: ask for them directly once.
            val missing = records.filterIsInstance<DnsRecord.Ptr>().map { it.target }.filter { t -> last.none { p -> t.startsWith(p.name) } }.distinct()
            if (missing.isNotEmpty() && !cancelled()) {
                val q2 = query(missing, TYPE_SRV)
                sockets[0].send(DatagramPacket(q2, q2.size, GROUP, PORT))
                val hosts = records.filterIsInstance<DnsRecord.Srv>().map { it.target }.distinct()
                if (hosts.isNotEmpty()) { val q3 = query(hosts, TYPE_A); sockets[0].send(DatagramPacket(q3, q3.size, GROUP, PORT)) }
                val end = System.currentTimeMillis() + 800
                while (System.currentTimeMillis() < end && !cancelled()) {
                    val pk = DatagramPacket(buf, buf.size)
                    try { sockets[0].receive(pk) } catch (_: IOException) { continue }
                    if (LocalNetwork.isLocal(pk.address)) records.addAll(parse(buf.copyOf(pk.length)))
                }
                val current = merge(printers(records))
                if (current != last) { last = current; found(current) }
            }
        } finally {
            for (s in sockets) s.close()
        }
        return last
    }

    /** One entry per printer, preferring its secure (ipps) service. */
    fun merge(list: List<FoundPrinter>): List<FoundPrinter> =
        list.groupBy { it.identity }.values.map { same ->
            val secure = same.firstOrNull { it.address.tls }
            val plain = same.firstOrNull { !it.address.tls }
            if (secure != null) secure.copy(fallback = plain?.address, txt = plain?.txt.orEmpty() + secure.txt) else plain!!
        }.sortedBy { it.name.lowercase() }
}
