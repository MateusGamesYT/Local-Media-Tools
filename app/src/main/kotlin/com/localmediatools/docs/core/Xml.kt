package com.localmediatools.docs.core

import java.io.InputStream
import java.io.Reader
import java.nio.charset.Charset

/**
 * A small pull parser for the XML inside office files (Office Open XML, OpenDocument, the XHTML of
 * e-books) and, leniently, for HTML.
 *
 * It reads elements, attributes, text, character references and the five XML entities, CDATA,
 * comments and processing instructions. Namespaces are resolved: [ns] is the element's namespace URI
 * and [local] its name without prefix. ISO "strict" Office URIs are reported as their usual
 * ("transitional") equivalents, so readers compare against one set of names.
 *
 * DTDs are skipped and declared entities are never expanded, so a hostile file can't make it build
 * huge strings. In [html] mode it also takes unquoted attribute values, attributes without values and
 * the common HTML named entities (&nbsp;, &mdash;…). Mismatched end tags are tolerated: an end tag
 * closes the nearest open element with its name, or is ignored.
 */
class XmlReader private constructor(private val reader: Reader, private val html: Boolean) {
    enum class Event { START, END, TEXT, EOF }

    companion object {
        /** Reads XML from [input], decoding it as its byte order mark or declaration says (UTF-8 by default). */
        fun of(input: InputStream, html: Boolean = false): XmlReader {
            val buf = java.io.BufferedInputStream(input, 1 shl 16)
            return XmlReader(java.io.InputStreamReader(buf, detectCharset(buf)), html)
        }

        fun of(text: String, html: Boolean = false): XmlReader = XmlReader(java.io.StringReader(text), html)

        fun of(bytes: ByteArray, html: Boolean = false): XmlReader = of(bytes.inputStream(), html)

        /** The encoding from the first bytes: byte order mark, then an encoding="…" declaration or HTML meta charset. */
        private fun detectCharset(buf: java.io.BufferedInputStream): Charset {
            buf.mark(1024)
            val head = ByteArray(1024)
            var n = 0
            while (n < head.size) { val k = buf.read(head, n, head.size - n); if (k <= 0) break; n += k }
            buf.reset()
            if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) { buf.skip(3); return Charsets.UTF_8 }
            if (n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) { buf.skip(2); return Charsets.UTF_16LE }
            if (n >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) { buf.skip(2); return Charsets.UTF_16BE }
            if (n >= 4 && head[0] == '<'.code.toByte() && head[1] == 0.toByte()) return Charsets.UTF_16LE
            if (n >= 4 && head[0] == 0.toByte() && head[1] == '<'.code.toByte()) return Charsets.UTF_16BE
            val ascii = String(head, 0, n, Charsets.ISO_8859_1)
            val m = Regex("""(?:encoding|charset)\s*=\s*["']?([A-Za-z0-9_\-:.]+)""", RegexOption.IGNORE_CASE).find(ascii)
            val name = m?.groupValues?.get(1) ?: return Charsets.UTF_8
            return try { Charset.forName(name).let { if (it.name().startsWith("UTF-16")) Charsets.UTF_8 else it } } catch (_: Exception) { Charsets.UTF_8 }
        }

        /** ISO strict Office namespaces → the transitional ones the readers use. */
        private val STRICT = mapOf(
            "http://purl.oclc.org/ooxml/wordprocessingml/main" to Ns.W,
            "http://purl.oclc.org/ooxml/spreadsheetml/main" to Ns.S,
            "http://purl.oclc.org/ooxml/presentationml/main" to Ns.P,
            "http://purl.oclc.org/ooxml/drawingml/main" to Ns.A,
            "http://purl.oclc.org/ooxml/officeDocument/relationships" to Ns.R,
            "http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing" to Ns.WP,
            "http://purl.oclc.org/ooxml/drawingml/picture" to Ns.PIC,
            "http://purl.oclc.org/ooxml/officeDocument/math" to Ns.M,
        )

        val HTML_ENTITIES: Map<String, Int> = mapOf(
            "nbsp" to 0xA0, "copy" to 0xA9, "reg" to 0xAE, "trade" to 0x2122, "mdash" to 0x2014, "ndash" to 0x2013,
            "hellip" to 0x2026, "lsquo" to 0x2018, "rsquo" to 0x2019, "ldquo" to 0x201C, "rdquo" to 0x201D, "sbquo" to 0x201A,
            "bdquo" to 0x201E, "laquo" to 0xAB, "raquo" to 0xBB, "lsaquo" to 0x2039, "rsaquo" to 0x203A, "bull" to 0x2022,
            "middot" to 0xB7, "deg" to 0xB0, "plusmn" to 0xB1, "times" to 0xD7, "divide" to 0xF7, "frac12" to 0xBD,
            "frac14" to 0xBC, "frac34" to 0xBE, "sup1" to 0xB9, "sup2" to 0xB2, "sup3" to 0xB3, "para" to 0xB6, "sect" to 0xA7,
            "euro" to 0x20AC, "pound" to 0xA3, "yen" to 0xA5, "cent" to 0xA2, "curren" to 0xA4, "iexcl" to 0xA1, "iquest" to 0xBF,
            "shy" to 0xAD, "zwnj" to 0x200C, "zwj" to 0x200D, "lrm" to 0x200E, "rlm" to 0x200F, "ensp" to 0x2002, "emsp" to 0x2003,
            "thinsp" to 0x2009, "dagger" to 0x2020, "Dagger" to 0x2021, "permil" to 0x2030, "prime" to 0x2032, "Prime" to 0x2033,
            "larr" to 0x2190, "rarr" to 0x2192, "uarr" to 0x2191, "darr" to 0x2193, "harr" to 0x2194, "rArr" to 0x21D2, "hArr" to 0x21D4,
            "le" to 0x2264, "ge" to 0x2265, "ne" to 0x2260, "asymp" to 0x2248, "infin" to 0x221E, "minus" to 0x2212, "micro" to 0xB5,
            "alpha" to 0x3B1, "beta" to 0x3B2, "gamma" to 0x3B3, "delta" to 0x3B4, "pi" to 0x3C0, "sigma" to 0x3C3, "omega" to 0x3C9,
            "Agrave" to 0xC0, "Aacute" to 0xC1, "Acirc" to 0xC2, "Atilde" to 0xC3, "Auml" to 0xC4, "Aring" to 0xC5, "AElig" to 0xC6,
            "Ccedil" to 0xC7, "Egrave" to 0xC8, "Eacute" to 0xC9, "Ecirc" to 0xCA, "Euml" to 0xCB, "Igrave" to 0xCC, "Iacute" to 0xCD,
            "Icirc" to 0xCE, "Iuml" to 0xCF, "Ntilde" to 0xD1, "Ograve" to 0xD2, "Oacute" to 0xD3, "Ocirc" to 0xD4, "Otilde" to 0xD5,
            "Ouml" to 0xD6, "Oslash" to 0xD8, "Ugrave" to 0xD9, "Uacute" to 0xDA, "Ucirc" to 0xDB, "Uuml" to 0xDC, "Yacute" to 0xDD,
            "szlig" to 0xDF, "agrave" to 0xE0, "aacute" to 0xE1, "acirc" to 0xE2, "atilde" to 0xE3, "auml" to 0xE4, "aring" to 0xE5,
            "aelig" to 0xE6, "ccedil" to 0xE7, "egrave" to 0xE8, "eacute" to 0xE9, "ecirc" to 0xEA, "euml" to 0xEB, "igrave" to 0xEC,
            "iacute" to 0xED, "icirc" to 0xEE, "iuml" to 0xEF, "ntilde" to 0xF1, "ograve" to 0xF2, "oacute" to 0xF3, "ocirc" to 0xF4,
            "otilde" to 0xF5, "ouml" to 0xF6, "oslash" to 0xF8, "ugrave" to 0xF9, "uacute" to 0xFA, "ucirc" to 0xFB, "uuml" to 0xFC,
            "yacute" to 0xFD, "yuml" to 0xFF, "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160, "scaron" to 0x161,
        )
    }

    // ------------------------------------------------------------------ the current event
    var event: Event = Event.EOF; private set
    /** Namespace URI of the current element ("" when none). */
    var ns: String = ""; private set
    /** Name without prefix of the current element. */
    var local: String = ""; private set
    /** Text of a TEXT event. */
    var text: String = ""; private set
    /** Number of open elements (after a START it includes the new one). */
    var depth: Int = 0; private set
    /** True for a START that was written as <x/> (its END follows at once). */
    var selfClosing = false; private set

    private val attrNs = ArrayList<String>()
    private val attrLocal = ArrayList<String>()
    private val attrValue = ArrayList<String>()
    val attributeCount: Int get() = attrLocal.size
    fun attributeLocal(i: Int) = attrLocal[i]
    fun attributeNs(i: Int) = attrNs[i]
    fun attributeValue(i: Int) = attrValue[i]

    /** The attribute [local] in namespace [ns] (null: no namespace, or any namespace when [anyNs]). */
    fun attr(ns: String?, local: String): String? {
        for (i in attrLocal.indices) if (attrLocal[i] == local && (ns == null && attrNs[i].isEmpty() || attrNs[i] == ns)) return attrValue[i]
        return null
    }

    /** The attribute [local] in any namespace. */
    fun attr(local: String): String? {
        for (i in attrLocal.indices) if (attrLocal[i] == local) return attrValue[i]
        return null
    }

    fun isStart(ns: String, local: String) = event == Event.START && this.local == local && this.ns == ns
    fun isEnd(ns: String, local: String) = event == Event.END && this.local == local && this.ns == ns

    // ------------------------------------------------------------------ input
    private val buf = CharArray(1 shl 15)
    private var pos = 0
    private var lim = 0
    private var eof = false

    private fun fill(): Boolean {
        if (eof) return false
        val n = reader.read(buf, 0, buf.size)
        if (n <= 0) { eof = true; return false }
        pos = 0; lim = n
        return true
    }

    private fun peek(): Int = if (pos < lim || fill()) buf[pos].code else -1
    private fun read(): Int = if (pos < lim || fill()) buf[pos++].code else -1

    // ------------------------------------------------------------------ open elements and namespaces
    private class Open(val qname: String, val ns: String, val local: String, val scope: Map<String, String>?)
    private val stack = ArrayList<Open>()
    private var scope: Map<String, String> = mapOf("xml" to "http://www.w3.org/XML/1998/namespace")
    private var pendingEnd: Open? = null
    private var pendingEnds = 0

    private fun resolve(prefix: String, element: Boolean): String {
        val uri = if (prefix.isEmpty()) (if (element) scope[""] ?: "" else "") else scope[prefix] ?: ""
        return STRICT[uri] ?: uri
    }

    /** Moves to the next event. Text between tags comes as one TEXT event; comments and PIs are skipped. */
    fun next(): Event {
        if (pendingEnds > 0) return popEnd()
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0) {
                if (sb.isNotEmpty()) return text(sb)
                if (stack.isNotEmpty()) { pendingEnds = stack.size; return popEnd() }
                event = Event.EOF; return event
            }
            if (c == '<'.code) {
                if (sb.isNotEmpty()) return text(sb)
                pos++
                val d = peek()
                when {
                    d == '/'.code -> { pos++; val name = readName(); skipTo('>'); if (closeTo(name)) return popEnd() }
                    d == '!'.code -> { pos++; if (bang(sb)) return text(sb) }
                    d == '?'.code -> skipPast("?>")
                    d >= 0 && (Character.isLetter(d) || d == '_'.code || d == ':'.code) -> return startTag()
                    else -> sb.append('<')
                }
            } else if (c == '&'.code) {
                pos++; entity(sb)
            } else {
                // Fast path through plain text.
                val start = pos
                while (pos < lim) { val ch = buf[pos]; if (ch == '<' || ch == '&') break; pos++ }
                sb.append(buf, start, pos - start)
            }
        }
    }

    private fun text(sb: StringBuilder): Event { text = sb.toString(); event = Event.TEXT; return event }

    private fun popEnd(): Event {
        val o = stack.removeAt(stack.size - 1)
        pendingEnds--
        o.scope?.let { scope = it }
        ns = o.ns; local = o.local; depth = stack.size
        attrNs.clear(); attrLocal.clear(); attrValue.clear()
        event = Event.END
        return event
    }

    /** An end tag for [name]: closes it and everything opened after it (counted in pendingEnds). */
    private fun closeTo(name: String): Boolean {
        for (i in stack.indices.reversed()) {
            if (stack[i].qname == name || (html && stack[i].qname.equals(name, ignoreCase = true))) {
                pendingEnds = stack.size - i
                return true
            }
        }
        return false
    }

    private fun bang(sb: StringBuilder): Boolean {
        if (lookingAt("--")) { skipPast("-->"); return false }
        if (lookingAt("[CDATA[")) {
            while (true) {
                val c = read()
                if (c < 0) break
                if (c == ']'.code && lookingAt("]>")) break
                sb.append(c.toChar())
            }
            return false
        }
        // DOCTYPE and friends: skip, including an internal subset in [...].
        var bracket = 0
        while (true) {
            val c = read()
            if (c < 0) break
            if (c == '['.code) bracket++
            if (c == ']'.code) bracket--
            if (c == '>'.code && bracket <= 0) break
        }
        return false
    }

    private fun lookingAt(s: String): Boolean {
        for (k in s.indices) {
            if (pos + k >= lim) {
                // Rare: the match runs past the buffer; shift what's left to the front and read more.
                val rest = lim - pos
                System.arraycopy(buf, pos, buf, 0, rest)
                pos = 0; lim = rest
                if (!eof) { val n = reader.read(buf, lim, buf.size - lim); if (n > 0) lim += n else eof = true }
                if (pos + k >= lim) return false
            }
            if (buf[pos + k] != s[k]) return false
        }
        pos += s.length
        return true
    }

    private fun skipPast(s: String) { while (peek() >= 0) { if (lookingAt(s)) return; pos++ } }
    private fun skipTo(ch: Char) { while (true) { val c = read(); if (c < 0 || c == ch.code) return } }

    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c == '>'.code || c == '/'.code || c == '='.code || Character.isWhitespace(c)) break
            sb.append(c.toChar()); pos++
        }
        while (peek() >= 0 && Character.isWhitespace(peek())) pos++
        return sb.toString()
    }

    private fun startTag(): Event {
        val qname = readName()
        val names = ArrayList<String>(); val values = ArrayList<String>()
        var self = false
        while (true) {
            while (peek() >= 0 && Character.isWhitespace(peek())) pos++
            val c = peek()
            if (c < 0) break
            if (c == '>'.code) { pos++; break }
            if (c == '/'.code) { pos++; if (peek() == '>'.code) { pos++; self = true; break }; continue }
            val an = readName()
            if (an.isEmpty()) { pos++; continue }
            var value = ""
            if (peek() == '='.code) {
                pos++
                while (peek() >= 0 && Character.isWhitespace(peek())) pos++
                value = attrValueText()
            } else if (!html) value = an
            names.add(an); values.add(value)
        }
        // Namespace declarations first, then the names they define.
        var newScope: MutableMap<String, String>? = null
        for (i in names.indices) {
            val n = names[i]
            if (n == "xmlns" || n.startsWith("xmlns:")) {
                if (newScope == null) newScope = HashMap(scope)
                newScope[if (n == "xmlns") "" else n.substring(6)] = values[i]
            }
        }
        val saved = scope
        if (newScope != null) scope = newScope
        attrNs.clear(); attrLocal.clear(); attrValue.clear()
        for (i in names.indices) {
            val n = names[i]
            if (n == "xmlns" || n.startsWith("xmlns:")) continue
            val k = n.indexOf(':')
            attrNs.add(if (k > 0) resolve(n.substring(0, k), false) else "")
            attrLocal.add(if (k > 0) n.substring(k + 1) else if (html) n.lowercase() else n)
            attrValue.add(values[i])
        }
        val k = qname.indexOf(':')
        ns = resolve(if (k > 0) qname.substring(0, k) else "", true)
        local = if (k > 0) qname.substring(k + 1) else if (html) qname.lowercase() else qname
        val open = Open(qname, ns, local, if (newScope != null) saved else null)
        if (html && local in VOID) self = true
        if (html && !self && (local == "script" || local == "style")) { skipRaw(local); self = true }
        stack.add(open)
        depth = stack.size
        selfClosing = self
        if (self) pendingEnds = 1
        event = Event.START
        return event
    }

    /** Skips raw text (a script or a style sheet) up to its end tag. */
    private fun skipRaw(name: String) {
        val end = "</$name"
        while (peek() >= 0) {
            if (peek() == '<'.code) {
                var k = 0
                while (k < end.length) {
                    if (pos + k >= lim && !lookingAtRefill(k)) break
                    if (buf[pos + k].lowercaseChar() != end[k]) break
                    k++
                }
                if (k == end.length) { pos += k; skipTo('>'); return }
            }
            pos++
        }
    }

    /** Makes at least [k] + 1 characters available from [pos]; false at the end of input. */
    private fun lookingAtRefill(k: Int): Boolean {
        val rest = lim - pos
        System.arraycopy(buf, pos, buf, 0, rest)
        pos = 0; lim = rest
        if (!eof) { val n = reader.read(buf, lim, buf.size - lim); if (n > 0) lim += n else eof = true }
        return pos + k < lim
    }

    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source", "param", "embed", "track")

    private fun attrValueText(): String {
        val sb = StringBuilder()
        val q = peek()
        if (q == '"'.code || q == '\''.code) {
            pos++
            while (true) {
                val c = read()
                if (c < 0 || c == q) break
                if (c == '&'.code) entity(sb) else sb.append(if (c == '\t'.code || c == '\n'.code || c == '\r'.code) ' ' else c.toChar())
            }
        } else {
            while (true) {
                val c = peek()
                if (c < 0 || c == '>'.code || Character.isWhitespace(c) || (c == '/'.code && !html)) break
                pos++
                if (c == '&'.code) entity(sb) else sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    /** After '&': appends the character it stands for, or the text as written when it isn't a known one. */
    private fun entity(sb: StringBuilder) {
        val name = StringBuilder()
        while (name.length < 32) {
            val c = peek()
            if (c < 0 || c == ';'.code || !(Character.isLetterOrDigit(c) || c == '#'.code)) break
            name.append(c.toChar()); pos++
        }
        val terminated = peek() == ';'.code
        if (terminated) pos++
        val n = name.toString()
        val cp: Int? = when {
            n.startsWith("#x") || n.startsWith("#X") -> n.substring(2).toIntOrNull(16)
            n.startsWith("#") -> n.substring(1).toIntOrNull()
            n == "lt" -> '<'.code; n == "gt" -> '>'.code; n == "amp" -> '&'.code; n == "quot" -> '"'.code; n == "apos" -> '\''.code
            else -> if (html) HTML_ENTITIES[n] else null
        }
        if (cp != null && cp in 1..0x10FFFF) sb.appendCodePoint(cp)
        else sb.append('&').append(n).append(if (terminated) ";" else "")
    }

    // ------------------------------------------------------------------ helpers for readers
    /** Skips the rest of the element whose START is current (does nothing on other events). */
    fun skip() {
        if (event != Event.START) return
        val d = depth
        while (true) {
            val e = next()
            if (e == Event.EOF || (e == Event.END && depth < d)) return
        }
    }

    /** All the text inside the element whose START is current, up to its END. */
    fun readText(): String {
        if (event != Event.START) return ""
        val d = depth
        val sb = StringBuilder()
        while (true) {
            val e = next()
            if (e == Event.EOF || (e == Event.END && depth < d)) break
            if (e == Event.TEXT) sb.append(text)
        }
        return sb.toString()
    }

    /** Calls [body] for each event inside the element whose START is current, up to (not including) its END. */
    inline fun children(body: (XmlReader) -> Unit) {
        if (event != Event.START || selfClosing) { if (selfClosing) next(); return }
        val d = depth
        while (true) {
            val e = next()
            if (e == Event.EOF || (e == Event.END && depth < d)) return
            body(this)
        }
    }
}

/** Namespace URIs the readers look for. */
object Ns {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    const val V = "urn:schemas-microsoft-com:vml"
    const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    const val WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"

    const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    const val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    const val XLINK = "http://www.w3.org/1999/xlink"
    const val PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    const val DC = "http://purl.org/dc/elements/1.1/"

    const val XHTML = "http://www.w3.org/1999/xhtml"
    const val OPF = "http://www.idpf.org/2007/opf"
    const val CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
}
