package com.localmediatools.print.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** A printer's IPP address: ipp://192.168.1.20:631/ipp/print (or ipps://). */
data class PrinterAddress(val tls: Boolean, val host: InetAddress, val port: Int, val path: String) {
    /** As written in requests ("printer-uri"). */
    val uri: String get() = (if (tls) "ipps" else "ipp") + "://" + hostText + ":" + port + path

    val hostText: String get() = host.hostAddress!!.let { if (it.contains(':')) "[" + it.substringBefore('%') + "]" else it }

    companion object {
        /** Parses ipp:// or ipps:// URIs and plain IP addresses; names (other than IP literals) are rejected. */
        fun parse(text: String): PrinterAddress {
            val t = text.trim()
            val scheme = t.substringBefore("://", "").lowercase()
            val rest = if (scheme.isEmpty()) t else t.substringAfter("://")
            val tls = scheme == "ipps" || scheme == "https"
            if (scheme.isNotEmpty() && scheme !in setOf("ipp", "ipps", "http", "https")) throw IllegalArgumentException("Not a printer address: $text")
            val authority = rest.substringBefore('/')
            val path = "/" + rest.substringAfter('/', "ipp/print").ifEmpty { "ipp/print" }
            val (hostPart, portPart) = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') to authority.substringAfter("]:", "")
                else if (authority.count { it == ':' } == 1) authority.substringBefore(':') to authority.substringAfter(':') else authority to ""
            val host = LocalNetwork.parseLiteral(hostPart) ?: throw IllegalArgumentException("Use the printer's IP address (like 192.168.1.20), not a name: \"$hostPart\"")
            val port = portPart.toIntOrNull() ?: 631
            return PrinterAddress(tls, host, port, path)
        }
    }
}

/** A document sent after the IPP request: with a known [length] or streamed (chunked) when null. */
interface IppDocument {
    val length: Long?
    fun writeTo(out: OutputStream)
}

class IppException(message: String, val status: Int = -1, cause: Throwable? = null, val lostWhileSending: Boolean = false) : IOException(message, cause)

/**
 * Trusts a printer's own (usually self-signed) certificate the first time it is seen and only that
 * one afterwards. [pin] is the SHA-256 of the certificate, kept with the printer.
 */
class PinnedTrust(@Volatile var pin: String?) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The printer sent no certificate")
        val fp = fingerprint(leaf)
        val p = pin
        if (p == null) pin = fp
        else if (!p.equals(fp, ignoreCase = true)) throw CertificateException("The printer's identity changed since it was added. If it was reset or replaced, remove it and add it again.")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun fingerprint(c: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }
    }
}

/**
 * IPP over HTTP/1.1 on a plain socket (ipp://) or TLS (ipps://), one connection per request. Only
 * local-network addresses are accepted ([LocalNetwork]).
 */
class IppClient(
    val address: PrinterAddress,
    val trust: PinnedTrust = PinnedTrust(null),
    private val connectTimeoutMs: Int = 6000,
    private val readTimeoutMs: Int = 60_000,
) {
    private val requestIds = AtomicInteger(1)
    fun nextId() = requestIds.getAndIncrement()

    /** The socket of the request in flight (so another thread can abort it). */
    @Volatile private var live: Socket? = null

    fun abort() { try { live?.close() } catch (_: IOException) { } }

    /**
     * Sends [request] (and [document]) and returns the printer's response. [sent] reports document
     * bytes written. Errors carry a readable message.
     */
    fun send(request: IppMessage, document: IppDocument? = null, sent: ((Long) -> Unit)? = null, readTimeoutMs: Int = this.readTimeoutMs): IppMessage {
        LocalNetwork.check(address.host)
        val socket = try { open(readTimeoutMs) } catch (e: IppException) { throw e } catch (e: javax.net.ssl.SSLException) {
            throw IppException("Couldn't set up a secure connection to the printer: ${e.message}", cause = e)
        } catch (e: IOException) {
            throw IppException(if (address.tls) "Couldn't set up a secure connection to the printer" else "Can't reach the printer at ${address.hostText}", cause = e)
        }
        live = socket
        try {
            val out = socket.getOutputStream().buffered(64 * 1024)
            val ipp = request.encode()
            val chunked = document != null && document.length == null
            val head = StringBuilder()
                .append("POST ").append(address.path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(address.hostText).append(':').append(address.port).append("\r\n")
                .append("Content-Type: application/ipp\r\n")
                .append("User-Agent: LocalMediaTools\r\n")
                .append(if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${ipp.size + (document?.length ?: 0L)}\r\n")
                .append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            var writeError: IOException? = null
            try {
                if (chunked) {
                    val body = ChunkedOutputStream(out)
                    body.write(ipp)
                    Counting(body, sent).also { document.writeTo(it) }.close()
                    body.finish()
                } else {
                    out.write(ipp)
                    if (document != null) Counting(out, sent).also { document.writeTo(it) }.close()
                }
                out.flush()
            } catch (e: IOException) {
                // The printer may have answered (e.g. refusing the job) and closed: read its answer.
                writeError = e
            }
            val response = try { readResponse(BufferedInputStream(socket.getInputStream())) } catch (e: IOException) {
                throw writeError?.let { IppException("The connection to the printer was lost while sending", cause = it, lostWhileSending = true) } ?: e
            }
            return response
        } catch (e: java.net.SocketTimeoutException) {
            throw IppException("The printer stopped answering", cause = e)
        } catch (e: javax.net.ssl.SSLException) {
            throw IppException("Couldn't set up a secure connection to the printer: ${e.message}", cause = e)
        } catch (e: IOException) {
            if (e is IppException) throw e
            throw IppException("The connection to the printer was lost", cause = e)
        } finally {
            live = null
            try { socket.close() } catch (_: IOException) { }
        }
    }

    private fun open(readTimeoutMs: Int): Socket {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(address.host, address.port), connectTimeoutMs)
        } catch (e: IOException) {
            plain.close()
            throw IppException("Can't reach the printer at ${address.hostText} (is it on, and is the phone on the same Wi-Fi?)", cause = e)
        }
        plain.soTimeout = readTimeoutMs
        plain.tcpNoDelay = true
        if (!address.tls) return plain
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val s = ssl.socketFactory.createSocket(plain, address.hostText, address.port, true) as SSLSocket
        // A printer without encryption may just wait: don't wait long for the handshake.
        s.soTimeout = minOf(readTimeoutMs, 10_000)
        try { s.startHandshake() } catch (e: IOException) { s.close(); throw e }
        s.soTimeout = readTimeoutMs
        return s
    }

    private fun readResponse(input: InputStream): IppMessage {
        var status: Int
        var headers: Map<String, String>
        while (true) {
            val line = readLine(input) ?: throw IppException("The printer closed the connection without answering")
            val parts = line.split(' ', limit = 3)
            status = parts.getOrNull(1)?.toIntOrNull() ?: throw IppException("The printer sent an unexpected answer: $line")
            headers = readHeaders(input)
            if (status !in 100..199) break
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> readFully(input, headers["content-length"]!!.trim().toInt())
            else -> input.readBytes()
        }
        if (status != 200) throw IppException(when (status) {
            401, 403 -> "The printer asks for a password; this app can't print to protected printers"
            426 -> "The printer only accepts secure connections"
            404 -> "The printer has no print service at ${address.path}"
            else -> "The printer answered HTTP $status"
        }, status)
        return try { IppMessage.decode(body) } catch (e: IllegalArgumentException) { throw IppException("The printer sent an answer that couldn't be read", cause = e) }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("US-ASCII")
            if (c == '\n'.code) return b.toString("US-ASCII").trimEnd('\r')
            b.write(c)
            if (b.size() > 16 * 1024) throw IppException("The printer sent an overlong header")
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val h = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return h
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = input.read(b, off, n - off); if (r < 0) throw EOFException("Answer cut short"); off += r }
        return b
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = (readLine(input) ?: throw EOFException("Answer cut short")).substringBefore(';').trim().toInt(16)
            if (size == 0) { readHeaders(input); return out.toByteArray() }
            out.write(readFully(input, size))
            readLine(input)
        }
    }

    /** HTTP chunked transfer encoding over [out] (64 KB chunks). */
    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        private val buf = ByteArray(64 * 1024); private var n = 0
        override fun write(b: Int) { buf[n++] = b.toByte(); if (n == buf.size) flushChunk() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var l = len
            while (l > 0) {
                val k = minOf(l, buf.size - n)
                System.arraycopy(b, o, buf, n, k); n += k; o += k; l -= k
                if (n == buf.size) flushChunk()
            }
        }
        private fun flushChunk() {
            if (n == 0) return
            out.write((Integer.toHexString(n) + "\r\n").toByteArray(Charsets.US_ASCII)); out.write(buf, 0, n); out.write("\r\n".toByteArray()); n = 0
        }
        fun finish() { flushChunk(); out.write("0\r\n\r\n".toByteArray()); out.flush() }
        override fun flush() { out.flush() }
    }

    private class Counting(private val out: OutputStream, private val sent: ((Long) -> Unit)?) : OutputStream() {
        private var count = 0L; private var reported = 0L
        override fun write(b: Int) { out.write(b); count++; report() }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len; report() }
        private fun report() { if (sent != null && count - reported >= 64 * 1024) { reported = count; sent(count) } }
        override fun flush() { out.flush() }
        override fun close() { sent?.invoke(count) }
    }
}
