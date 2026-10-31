package com.localmediatools.meta.core

import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PDF: the document information (title, author, the programs that made it, dates), its XMP, page
 * count and protection. Objects are found through the cross-reference tables and streams (PDF 1.5+
 * object streams included) or, in a damaged file, by scanning for them. Files protected only against
 * changes (no password needed to open them) are decrypted, as every PDF viewer does.
 */
object PdfMeta {
    class Ref(val n: Int, val g: Int)
    class Str(val b: ByteArray)
    class Name(val s: String) { override fun toString() = s }
    private class Keyword(val s: String)

    private class Obj(val value: Any?, val dict: Map<String, Any?>?, val streamAt: Long = -1, val num: Int = 0, val gen: Int = 0)

    /** A small parser of PDF values in [b], from [p]. */
    private class Lexer(val b: ByteArray, var p: Int = 0) {
        fun ws() {
            while (p < b.size) {
                val c = b[p].toInt() and 0xFF
                if (c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32) p++
                else if (c == '%'.code) { while (p < b.size && b[p] != '\n'.code.toByte() && b[p] != '\r'.code.toByte()) p++ }
                else return
            }
        }

        private fun delim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code || c == ']'.code ||
            c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
        private fun white(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

        fun token(): String {
            ws()
            val s = p
            while (p < b.size && !white(b[p].toInt() and 0xFF) && !delim(b[p].toInt() and 0xFF)) p++
            return String(b, s, p - s, Charsets.ISO_8859_1)
        }

        fun value(depth: Int = 0): Any? {
            ws()
            if (p >= b.size || depth > 64) return null
            val c = b[p].toInt() and 0xFF
            return when {
                c == '<'.code && p + 1 < b.size && b[p + 1] == '<'.code.toByte() -> {
                    p += 2
                    val m = LinkedHashMap<String, Any?>()
                    while (true) {
                        ws()
                        if (p >= b.size) break
                        if (b[p] == '>'.code.toByte()) { p += 2; break }
                        if (b[p] != '/'.code.toByte()) { p++; continue }
                        val k = (value(depth + 1) as? Name)?.s ?: break
                        m[k] = value(depth + 1)
                    }
                    m
                }
                c == '<'.code -> {
                    p++
                    val o = java.io.ByteArrayOutputStream()
                    var hi = -1
                    while (p < b.size && b[p] != '>'.code.toByte()) {
                        val d = Character.digit(b[p].toInt(), 16)
                        p++
                        if (d < 0) continue
                        if (hi < 0) hi = d else { o.write(hi * 16 + d); hi = -1 }
                    }
                    if (hi >= 0) o.write(hi * 16)
                    p++
                    Str(o.toByteArray())
                }
                c == '('.code -> Str(literal())
                c == '['.code -> {
                    p++
                    val l = ArrayList<Any?>()
                    while (true) {
                        ws()
                        if (p >= b.size) break
                        if (b[p] == ']'.code.toByte()) { p++; break }
                        val start = p
                        l.add(value(depth + 1))
                        if (p == start) p++
                    }
                    l
                }
                c == '/'.code -> {
                    p++
                    val s = p
                    while (p < b.size && !white(b[p].toInt() and 0xFF) && !delim(b[p].toInt() and 0xFF)) p++
                    Name(unescapeName(String(b, s, p - s, Charsets.ISO_8859_1)))
                }
                c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> {
                    val t = token()
                    val n = t.toLongOrNull()
                    if (n != null && n >= 0) {
                        // "12 0 R" is a reference.
                        val save = p
                        val g = token().toIntOrNull()
                        if (g != null && token() == "R") return Ref(n.toInt(), g)
                        p = save
                        n
                    } else n ?: t.toDoubleOrNull()
                }
                else -> {
                    val t = token()
                    if (t.isEmpty()) { p++; null }
                    else when (t) { "true" -> true; "false" -> false; "null" -> null; else -> Keyword(t) }
                }
            }
        }

        private fun literal(): ByteArray {
            p++
            val o = java.io.ByteArrayOutputStream()
            var depth = 1
            while (p < b.size) {
                val c = b[p++].toInt() and 0xFF
                when (c) {
                    '('.code -> { depth++; o.write(c) }
                    ')'.code -> { depth--; if (depth == 0) return o.toByteArray(); o.write(c) }
                    '\\'.code -> {
                        if (p >= b.size) break
                        val e = b[p++].toInt() and 0xFF
                        when (e) {
                            'n'.code -> o.write(10); 'r'.code -> o.write(13); 't'.code -> o.write(9); 'b'.code -> o.write(8); 'f'.code -> o.write(12)
                            13 -> if (p < b.size && b[p] == 10.toByte()) p++
                            10 -> {}
                            in '0'.code..'7'.code -> {
                                var v = e - '0'.code
                                var k = 0
                                while (k < 2 && p < b.size && b[p] in '0'.code.toByte()..'7'.code.toByte()) { v = v * 8 + (b[p++] - '0'.code.toByte()); k++ }
                                o.write(v and 0xFF)
                            }
                            else -> o.write(e)
                        }
                    }
                    else -> o.write(c)
                }
            }
            return o.toByteArray()
        }

        private fun unescapeName(s: String): String {
            if (!s.contains('#')) return s
            val o = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                if (s[i] == '#' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { o.write(v); i += 3; continue }
                }
                o.write(s[i].code); i++
            }
            return String(o.toByteArray(), Charsets.UTF_8)
        }
    }

    private class Entry(val off: Long, val stm: Int = -1, val idx: Int = 0)

    private class Doc(val f: MetaInput) {
        val xref = HashMap<Int, Entry>()
        var trailer: Map<String, Any?>? = null
        val stmCache = HashMap<Int, Pair<ByteArray, IntArray>?>()
        var crypt: Crypt? = null

        fun window(at: Long, len: Int): ByteArray = f.bytes(at, len)

        /** The object at file offset [off] ("n g obj value [stream]"). */
        fun objAt(off: Long, expect: Int = -1): Obj? {
            val w = window(off, 1 shl 16)
            val l = Lexer(w)
            val n = l.token().toIntOrNull() ?: return null
            val g = l.token().toIntOrNull() ?: return null
            if (l.token() != "obj" || (expect >= 0 && n != expect)) return null
            var v = l.value()
            if (v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST") val d = v as Map<String, Any?>
                val save = l.p
                if (l.token() == "stream") {
                    var s = l.p
                    if (s < w.size && w[s] == '\r'.code.toByte()) s++
                    if (s < w.size && w[s] == '\n'.code.toByte()) s++
                    return Obj(d, d, off + s, n, g)
                }
                l.p = save
                return Obj(d, d, -1, n, g)
            }
            if (v is Keyword && v.s == "endobj") v = null
            return Obj(v, null, -1, n, g)
        }

        fun obj(n: Int, depth: Int = 0): Obj? {
            val e = xref[n] ?: return null
            if (e.stm < 0) return objAt(e.off, n)
            if (depth > 4) return null
            val (data, offs) = stmCache.getOrPut(e.stm) { objectStream(e.stm, depth) } ?: return null
            // The objects inside an object stream aren't encrypted one by one (the stream was).
            for (i in 0 until offs.size / 2) if (offs[2 * i] == n) {
                val v = Lexer(data, offs[2 * i + 1]).value()
                @Suppress("UNCHECKED_CAST")
                return Obj(v, v as? Map<String, Any?>, -1, n, 0)
            }
            return null
        }

        private fun objectStream(n: Int, depth: Int): Pair<ByteArray, IntArray>? {
            val o = obj(n, depth + 1) ?: return null
            val d = o.dict ?: return null
            val data = streamData(o) ?: return null
            val count = (resolve(d["N"]) as? Long)?.toInt() ?: return null
            val first = (resolve(d["First"]) as? Long)?.toInt() ?: return null
            val l = Lexer(data)
            val offs = IntArray(count.coerceIn(0, 1 shl 20) * 2)
            for (i in 0 until offs.size / 2) {
                offs[2 * i] = l.token().toIntOrNull() ?: break
                offs[2 * i + 1] = first + (l.token().toIntOrNull() ?: break)
            }
            return data to offs
        }

        fun resolve(v: Any?, depth: Int = 0): Any? = if (v is Ref && depth < 8) resolve(obj(v.n)?.value, depth + 1) else v

        @Suppress("UNCHECKED_CAST")
        fun dict(v: Any?): Map<String, Any?>? = resolve(v) as? Map<String, Any?>

        /** A stream's bytes, decrypted and unpacked (only Flate, the filter metadata uses). */
        fun streamData(o: Obj, limit: Int = 64 shl 20, decrypt: Boolean = true): ByteArray? {
            val d = o.dict ?: return null
            if (o.streamAt < 0) return null
            var len = (resolve(d["Length"]) as? Long)?.toInt() ?: -1
            if (len < 0 || o.streamAt + len > f.size) {
                // A wrong length: up to "endstream".
                val w = window(o.streamAt, minOf(limit.toLong(), f.size - o.streamAt).toInt())
                len = String(w, Charsets.ISO_8859_1).indexOf("endstream").takeIf { it >= 0 } ?: w.size
            }
            if (len > limit) return null
            var data = f.bytes(o.streamAt, len)
            val type = (d["Type"] as? Name)?.s
            if (decrypt && type != "XRef") crypt?.let { c ->
                if (!(type == "Metadata" && !c.encryptMetadata)) data = c.stream(data, o.num, o.gen) ?: return null
            }
            val filters = when (val fl = resolve(d["Filter"])) { is Name -> listOf(fl.s); is List<*> -> fl.mapNotNull { (resolve(it) as? Name)?.s }; else -> emptyList() }
            val parms = when (val p = resolve(d["DecodeParms"])) { is Map<*, *> -> listOf(p); is List<*> -> p.map { resolve(it) as? Map<*, *> }; else -> emptyList() }
            for ((i, fl) in filters.withIndex()) {
                data = when (fl) {
                    "FlateDecode", "Fl" -> inflate(data, limit) ?: return null
                    "Crypt" -> data
                    else -> return null
                }
                val pm = parms.getOrNull(i)
                val pred = (resolve(pm?.get("Predictor")) as? Long)?.toInt() ?: 1
                if (pred >= 10) data = png(data, ((resolve(pm?.get("Columns")) as? Long)?.toInt() ?: 1) * ((resolve(pm?.get("Colors")) as? Long)?.toInt() ?: 1) *
                    ((resolve(pm?.get("BitsPerComponent")) as? Long)?.toInt() ?: 8) / 8)
            }
            return data
        }
    }

    private fun inflate(b: ByteArray, limit: Int): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(b)
            val o = java.io.ByteArrayOutputStream(b.size * 3)
            val buf = ByteArray(1 shl 16)
            while (!inf.finished() && o.size() < limit) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o.write(buf, 0, n)
            }
            o.toByteArray()
        } catch (_: Exception) { null } finally { inf.end() }
    }

    /** Undoes PNG row predictors ([cols] bytes per row). */
    private fun png(b: ByteArray, cols: Int, bpp: Int = 1): ByteArray {
        if (cols <= 0) return b
        val rows = b.size / (cols + 1)
        val out = ByteArray(rows * cols)
        for (r in 0 until rows) {
            val type = b[r * (cols + 1)].toInt()
            for (c in 0 until cols) {
                val x = b[r * (cols + 1) + 1 + c].toInt() and 0xFF
                val a = if (c >= bpp) out[r * cols + c - bpp].toInt() and 0xFF else 0
                val u = if (r > 0) out[(r - 1) * cols + c].toInt() and 0xFF else 0
                val ul = if (r > 0 && c >= bpp) out[(r - 1) * cols + c - bpp].toInt() and 0xFF else 0
                out[r * cols + c] = (when (type) {
                    1 -> x + a; 2 -> x + u; 3 -> x + (a + u) / 2
                    4 -> { val p = a + u - ul; val pa = Math.abs(p - a); val pb = Math.abs(p - u); val pc = Math.abs(p - ul); x + if (pa <= pb && pa <= pc) a else if (pb <= pc) u else ul }
                    else -> x
                }).toByte()
            }
        }
        return out
    }

    // ------------------------------------------------------------------ cross references
    private fun readXref(d: Doc): Boolean {
        val f = d.f
        val tail = f.bytes(maxOf(0, f.size - 4096), 4096)
        val i = String(tail, Charsets.ISO_8859_1).lastIndexOf("startxref")
        if (i < 0) return false
        var off = Lexer(tail, i + 9).token().toLongOrNull() ?: return false
        val seen = HashSet<Long>()
        while (off in 0 until f.size && seen.add(off) && seen.size < 200) {
            val table = Lexer(d.window(off, 32)).token() == "xref"
            val prev = if (table) classic(d, off) else xrefStream(d, off)
            // The newest section must be readable; an older one that isn't just ends the chain.
            if (d.trailer == null) return false
            off = prev ?: break
        }
        return d.trailer != null && d.xref.isNotEmpty()
    }

    private fun classic(d: Doc, off: Long): Long? {
        val w = d.window(off, minOf(64L shl 20, d.f.size - off).toInt())
        val l = Lexer(w)
        l.token() // xref
        while (true) {
            val save = l.p
            val t = l.token()
            if (t == "trailer") break
            val start = t.toIntOrNull() ?: run { l.p = save; return null }
            val count = l.token().toIntOrNull() ?: return null
            for (k in 0 until count) {
                val o = l.token().toLongOrNull() ?: return null
                l.token()
                val type = l.token()
                if (type == "n" && o > 0) d.xref.putIfAbsent(start + k, Entry(o))
            }
        }
        @Suppress("UNCHECKED_CAST") val t = l.value() as? Map<String, Any?> ?: return null
        if (d.trailer == null) d.trailer = t
        (t["XRefStm"] as? Long)?.let { xrefStream(d, it) }
        return t["Prev"] as? Long
    }

    /** A cross-reference stream at [off]; returns the previous one's offset. */
    private fun xrefStream(d: Doc, off: Long): Long? {
        val o = d.objAt(off) ?: return null
        val dict = o.dict ?: return null
        if ((dict["Type"] as? Name)?.s != "XRef") return null
        if (d.trailer == null) d.trailer = dict
        val data = d.streamData(o, decrypt = false) ?: return dict["Prev"] as? Long
        val w = (dict["W"] as? List<*>)?.map { (it as? Long)?.toInt() ?: 0 } ?: return null
        if (w.size < 3 || w.any { it < 0 || it > 8 }) return null
        val size = (dict["Size"] as? Long)?.toInt() ?: 0
        val index = (dict["Index"] as? List<*>)?.map { (it as? Long)?.toInt() ?: 0 } ?: listOf(0, size)
        val rec = w.sum()
        var p = 0
        fun field(n: Int, def: Long): Long { if (n == 0) return def; var v = 0L; repeat(n) { v = (v shl 8) or (data.getOrElse(p++) { 0 }.toLong() and 0xFF) }; return v }
        for (s in 0 until index.size / 2) {
            for (k in 0 until index[2 * s + 1]) {
                if (p + rec > data.size) break
                val type = field(w[0], 1); val a = field(w[1], 0); val b = field(w[2], 0)
                val n = index[2 * s] + k
                when (type) { 1L -> if (a > 0) d.xref.putIfAbsent(n, Entry(a)); 2L -> d.xref.putIfAbsent(n, Entry(0, a.toInt(), b.toInt())) }
            }
        }
        return dict["Prev"] as? Long
    }

    /** A damaged file: every "n g obj" found by scanning (the last one of a number wins), and its trailer. */
    private fun scan(d: Doc) {
        val f = d.f
        val chunk = 4 shl 20
        val re = Regex("""(?<![0-9])(\d{1,8})\s+(\d{1,5})\s+obj\b""")
        var at = 0L
        while (at < f.size && at < (512L shl 20)) {
            val s = String(f.bytes(at, chunk + 64), Charsets.ISO_8859_1)
            for (m in re.findAll(s)) if (m.range.first < chunk) d.xref[m.groupValues[1].toInt()] = Entry(at + m.range.first)
            var t = s.lastIndexOf("trailer")
            while (t >= 0 && t < chunk) {
                @Suppress("UNCHECKED_CAST") val dict = Lexer(s.toByteArray(Charsets.ISO_8859_1), t + 7).value() as? Map<String, Any?>
                if (dict != null && dict["Root"] != null) { d.trailer = dict; break }
                t = s.lastIndexOf("trailer", t - 1)
            }
            at += chunk
        }
        if (d.trailer == null) {
            // Without a trailer: a cross-reference stream's dictionary, or the catalog itself.
            for ((n, e) in d.xref) {
                val o = d.objAt(e.off) ?: continue
                val type = (o.dict?.get("Type") as? Name)?.s
                if (type == "XRef") { d.trailer = o.dict; break }
                if (type == "Catalog") d.trailer = mapOf("Root" to Ref(n, 0))
            }
        }
        // Objects inside object streams.
        for ((n, e) in d.xref.entries.toList()) {
            val o = d.objAt(e.off) ?: continue
            if ((o.dict?.get("Type") as? Name)?.s != "ObjStm") continue
            val data = d.streamData(o) ?: continue
            val count = (o.dict["N"] as? Long)?.toInt() ?: continue
            val l = Lexer(data)
            for (i in 0 until count) {
                val num = l.token().toIntOrNull() ?: break
                l.token()
                d.xref.putIfAbsent(num, Entry(0, n, i))
            }
        }
    }

    // ------------------------------------------------------------------ encryption (standard handler, empty user password)
    private val PAD = intArrayOf(0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A).map { it.toByte() }.toByteArray()

    private class Crypt(val key: ByteArray, val aes: Boolean, val v5: Boolean, val strings: Boolean, val streams: Boolean, val encryptMetadata: Boolean) {
        private fun objKey(n: Int, g: Int): ByteArray {
            if (v5) return key
            val md = MessageDigest.getInstance("MD5")
            md.update(key); md.update(byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), g.toByte(), (g shr 8).toByte()))
            if (aes) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
            return md.digest().copyOf(minOf(key.size + 5, 16))
        }

        private fun run(b: ByteArray, n: Int, g: Int): ByteArray? {
            val k = objKey(n, g)
            if (!aes) return rc4(k, b)
            if (b.size < 32 || b.size % 16 != 0) return if (b.size == 16) ByteArray(0) else null
            return try {
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(b.copyOf(16)))
                c.doFinal(b, 16, b.size - 16)
            } catch (_: Exception) { null }
        }

        fun string(b: ByteArray, n: Int, g: Int): ByteArray = if (strings) run(b, n, g) ?: b else b
        fun stream(b: ByteArray, n: Int, g: Int): ByteArray? = if (streams) run(b, n, g) else b
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) { j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF; val t = s[i]; s[i] = s[j]; s[j] = t }
        var i = 0; j = 0
        return ByteArray(data.size) { k ->
            i = (i + 1) and 0xFF; j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
    }

    /** The key for the empty user password, or null when opening the file needs a password. */
    private fun crypt(d: Doc, enc: Map<String, Any?>, out: Fields): Crypt? {
        val filter = (enc["Filter"] as? Name)?.s
        val v = (d.resolve(enc["V"]) as? Long)?.toInt() ?: 0
        val r = (d.resolve(enc["R"]) as? Long)?.toInt() ?: 0
        val bits = (d.resolve(enc["Length"]) as? Long)?.toInt() ?: 40
        val o = (d.resolve(enc["O"]) as? Str)?.b ?: ByteArray(0)
        val u = (d.resolve(enc["U"]) as? Str)?.b ?: ByteArray(0)
        val p = (d.resolve(enc["P"]) as? Long)?.toInt() ?: 0
        val encMeta = d.resolve(enc["EncryptMetadata"]) != false
        val id = ((d.trailer?.get("ID") as? List<*>)?.firstOrNull() as? Str)?.b ?: ByteArray(0)
        out.add("PDF", "Encryption", "$filter V$v.$r (${if (v >= 5) 256 else if (v >= 2) bits else 40}-bit)")
        out.add("PDF", "UserAccess", permissions(p), "$p")
        if (filter != "Standard") return null
        // Which cipher strings and streams use.
        val cf = d.dict(enc["CF"])
        fun method(name: String?): String? = if (name == null || name == "Identity") null else (d.dict(cf?.get(name))?.get("CFM") as? Name)?.s ?: "V2"
        val strM = if (v >= 4) method((enc["StrF"] as? Name)?.s) else "V2"
        val stmM = if (v >= 4) method((enc["StmF"] as? Name)?.s) else "V2"
        if (v >= 5) {
            if (u.size < 48) return null
            val ue = (d.resolve(enc["UE"]) as? Str)?.b ?: return null
            val salt = u.copyOfRange(32, 40)
            val check = if (r >= 6) hash2B(ByteArray(0), salt, ByteArray(0)) else sha256(salt)
            if (!check.contentEquals(u.copyOf(32))) return null
            val ik = if (r >= 6) hash2B(ByteArray(0), u.copyOfRange(40, 48), ByteArray(0)) else sha256(u.copyOfRange(40, 48))
            val key = try {
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(ik, "AES"), IvParameterSpec(ByteArray(16)))
                c.doFinal(ue.copyOf(32))
            } catch (_: Exception) { return null }
            return Crypt(key, aes = true, v5 = true, strings = strM != null, streams = stmM != null, encryptMetadata = encMeta)
        }
        val n = if (r == 2) 5 else (bits / 8).coerceIn(5, 16)
        val md = MessageDigest.getInstance("MD5")
        md.update(PAD); md.update(o.copyOf(32)); md.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte())); md.update(id)
        if (r >= 4 && !encMeta) md.update(byteArrayOf(-1, -1, -1, -1))
        var h = md.digest()
        if (r >= 3) repeat(50) { h = MessageDigest.getInstance("MD5").digest(h.copyOf(n)) }
        val key = h.copyOf(n)
        // Check the key against U.
        val ok = if (r == 2) rc4(key, PAD).contentEquals(u.copyOf(32)) else {
            val m = MessageDigest.getInstance("MD5"); m.update(PAD); m.update(id)
            var x = rc4(key, m.digest())
            for (i in 1..19) x = rc4(ByteArray(key.size) { (key[it].toInt() xor i).toByte() }, x)
            x.contentEquals(u.copyOf(16))
        }
        if (!ok) return null
        return Crypt(key, aes = strM == "AESV2" || stmM == "AESV2", v5 = false, strings = strM != null, streams = stmM != null, encryptMetadata = encMeta)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").let { m -> parts.forEach { m.update(it) }; m.digest() }

    /** PDF 2.0's password hash (ISO 32000-2, algorithm 2.B). */
    private fun hash2B(pw: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray {
        var k = sha256(pw, salt, udata)
        var round = 0
        while (true) {
            val one = pw + k + udata
            val k1 = ByteArray(one.size * 64).also { for (i in 0 until 64) System.arraycopy(one, 0, it, i * one.size, one.size) }
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
            val e = c.doFinal(k1)
            val mod = (0 until 16).sumOf { e[it].toInt() and 0xFF } % 3
            k = MessageDigest.getInstance(when (mod) { 0 -> "SHA-256"; 1 -> "SHA-384"; else -> "SHA-512" }).digest(e)
            round++
            if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
        }
        return k.copyOf(32)
    }

    private fun permissions(p: Int): String {
        val names = listOf(3 to "Print", 4 to "Modify", 5 to "Copy", 6 to "Annotate", 9 to "Fill forms", 10 to "Extract", 11 to "Assemble", 12 to "Print high-res")
        val allowed = names.filter { (bit, _) -> p and (1 shl (bit - 1)) != 0 }.map { it.second }
        return if (allowed.size == names.size) "Print, Modify, Copy, Annotate, Fill forms, Extract, Assemble, Print high-res" else allowed.joinToString(", ").ifEmpty { "(none)" }
    }

    // ------------------------------------------------------------------ text and dates
    private val DOC_ENC = mapOf(0x18 to '˘', 0x19 to 'ˇ', 0x1A to 'ˆ', 0x1B to '˙', 0x1C to '˝', 0x1D to '˛', 0x1E to '˚', 0x1F to '˜',
        0x80 to '•', 0x81 to '†', 0x82 to '‡', 0x83 to '…', 0x84 to '—', 0x85 to '–', 0x86 to 'ƒ', 0x87 to '⁄', 0x88 to '‹', 0x89 to '›',
        0x8A to '−', 0x8B to '‰', 0x8C to '„', 0x8D to '“', 0x8E to '”', 0x8F to '‘', 0x90 to '’', 0x91 to '‚', 0x92 to '™', 0x93 to 'ﬁ',
        0x94 to 'ﬂ', 0x95 to 'Ł', 0x96 to 'Œ', 0x97 to 'Š', 0x98 to 'Ÿ', 0x99 to 'Ž', 0x9A to 'ı', 0x9B to 'ł', 0x9C to 'œ', 0x9D to 'š',
        0x9E to 'ž', 0xA0 to '€')

    fun text(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
            // UTF-16 text can carry language marks between ESC characters.
            String(b, 2, b.size - 2, Charsets.UTF_16BE).replace(Regex("\u001B[^\u001B]*\u001B"), "")
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> buildString(b.size) { for (x in b) { val c = x.toInt() and 0xFF; append(DOC_ENC[c] ?: c.toChar()) } }
    }.trimEnd('\u0000')

    /** "D:20230105143000+01'00'" → "2023:01:05 14:30:00+01:00". */
    fun date(s: String): String? {
        val m = Regex("""^(?:D:)?\s*(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?\s*([Zz+-])?\s*(\d{2})?'?(\d{2})?'?""").find(s.trim()) ?: return null
        val g = m.groupValues
        val sb = StringBuilder(g[1]).append(':').append(g[2].ifEmpty { "01" }).append(':').append(g[3].ifEmpty { "01" })
        if (g[4].isNotEmpty()) sb.append(' ').append(g[4]).append(':').append(g[5].ifEmpty { "00" }).append(':').append(g[6].ifEmpty { "00" })
        when {
            g[7] == "Z" || g[7] == "z" -> sb.append('Z')
            g[7].isNotEmpty() && g[8].isNotEmpty() -> sb.append(g[7]).append(g[8]).append(':').append(g[9].ifEmpty { "00" })
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ reading
    fun read(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "PDF")
        val head = f.ascii(0, 1024)
        val v = Regex("""%PDF-(\d\.\d)""").find(head)?.groupValues?.get(1)
        v?.let { out.add("PDF", "PDFVersion", it) }
        val d = Doc(f)
        if (!readXref(d)) { d.xref.clear(); d.trailer = null; scan(d) }
        val trailer = d.trailer ?: run { out.note("This PDF is damaged: its document information couldn't be found."); return }
        // A linearized ("fast web view") file says so in its first object.
        if (Regex("""obj\s*<<[^>]*?/Linearized""").containsMatchIn(head)) out.add("PDF", "Linearized", "Yes", "true") else out.add("PDF", "Linearized", "No", "false")
        d.dict(trailer["Encrypt"])?.let { enc ->
            d.crypt = crypt(d, enc, out)
            if (d.crypt == null) { out.note("This PDF needs a password to open, so what's inside it can't be read."); return }
        }
        val root = d.dict(trailer["Root"])
        // The catalog can say the file was updated to a newer version than its header says.
        (root?.get("Version") as? Name)?.s?.let { if (v == null || it > v) { out.list.removeAll { f -> f.group == "PDF" && f.name == "PDFVersion" }; out.add("PDF", "PDFVersion", it) } }
        root?.let { r ->
            val pages = d.dict(r["Pages"])
            (d.resolve(pages?.get("Count")) as? Long)?.let { out.add("PDF", "PageCount", "$it") }
            (d.resolve(r["Lang"]) as? Str)?.let { s -> out.add("PDF", "Language", decrypted(d, s, trailer["Root"])) }
            (d.resolve(r["PageMode"]) as? Name)?.let { out.add("PDF", "PageMode", it.s) }
            (d.resolve(r["PageLayout"]) as? Name)?.let { out.add("PDF", "PageLayout", it.s) }
            if (d.dict(r["MarkInfo"])?.let { d.resolve(it["Marked"]) } == true) out.add("PDF", "TaggedPDF", "Yes", "true")
            d.dict(r["AcroForm"])?.let { form ->
                out.add("PDF", "HasForm", "Yes")
                out.add("PDF", "HasXFA", if (form["XFA"] != null) "Yes" else "No", if (form["XFA"] != null) "true" else "false")
            }
            d.dict(r["Names"])?.let { names -> if (names["EmbeddedFiles"] != null) out.add("PDF", "HasAttachments", "Yes") }
            if (d.dict(r["Perms"])?.get("DocMDP") != null || d.dict(r["AcroForm"])?.get("SigFlags") != null) out.add("PDF", "Signed", "Yes")
        }
        // The document information.
        val infoRef = trailer["Info"]
        d.dict(infoRef)?.let { info ->
            for ((k, value) in info) {
                val raw = d.resolve(value)
                val shown = when (raw) {
                    is Str -> decrypted(d, raw, infoRef)
                    is Name -> raw.s
                    is Long, is Double, is Boolean -> raw.toString()
                    else -> continue
                }
                val name = when (k) { "CreationDate" -> "CreateDate"; "ModDate" -> "ModifyDate"; else -> k.replace(Regex("[^A-Za-z0-9]"), "") }
                val privacy = when (name) { "Creator", "Producer" -> Privacy.SOFTWARE; else -> null }
                if (name == "CreateDate" || name == "ModifyDate") date(shown)?.let { out.add("PDF", name, it, it, Privacy.TIME) } ?: out.add("PDF", name, shown, privacy = Privacy.TIME)
                else out.add("PDF", name, shown, privacy = privacy)
            }
        }
        // XMP in the catalog's Metadata stream.
        val meta = root?.get("Metadata")
        val metaObj = (meta as? Ref)?.let { d.obj(it.n) }
        if (metaObj != null) d.streamData(metaObj, 16 shl 20)?.let { XmpReader.read(it, out) }
    }

    /** A string of the object [owner] (the Info or catalog reference), decrypted when it has to be. */
    private fun decrypted(d: Doc, s: Str, owner: Any?): String {
        val c = d.crypt
        if (c == null || owner !is Ref) return text(s.b)
        // Strings in objects inside object streams were decrypted with the stream.
        if ((d.xref[owner.n]?.stm ?: -1) >= 0) return text(s.b)
        return text(c.string(s.b, owner.n, owner.g))
    }
}
