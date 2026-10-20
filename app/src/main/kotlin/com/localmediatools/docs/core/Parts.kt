package com.localmediatools.docs.core

import com.localmediatools.i18n.tr
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * The parts of a ZIP-based file (Office Open XML, OpenDocument, EPUB), by name. Names are matched
 * without a leading slash, with %-escapes decoded and, as a fallback, ignoring case (some writers
 * aren't consistent). Every read is capped, so a small file that unpacks to gigabytes ("zip bomb")
 * stops with a message instead of filling the memory.
 */
class Parts(file: File, private val maxPartBytes: Long = 256L shl 20, private val maxTotalBytes: Long = 1L shl 30) : Closeable {
    /** A rebuilt copy, when the file had to be recovered (deleted on close). */
    private var rebuilt: File? = null
    private val zip: ZipFile = try { ZipFile(file) } catch (e: Exception) { recover(file) }

    /**
     * A ZIP whose directory at the end is missing (a cut-off download, a streaming writer) still has
     * each part's own header: the parts are read in order from those and copied into a proper ZIP,
     * as Office and LibreOffice do. Capped like every read.
     */
    private fun recover(file: File): ZipFile {
        val damaged = UnreadableException(tr("The file is damaged: its contents can't be unpacked."))
        val out = try { File.createTempFile("recovered", ".zip") } catch (_: Exception) { throw damaged }
        var parts = 0
        try {
            java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zin ->
                java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zout ->
                    var written = 0L
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val e = try { zin.nextEntry } catch (_: Exception) { null } ?: break
                        if (e.isDirectory) continue
                        // Each part whole or not at all: the one the file was cut in is left out.
                        val part = java.io.ByteArrayOutputStream()
                        try {
                            while (true) {
                                val n = zin.read(buf); if (n < 0) break
                                written += n
                                if (written > maxTotalBytes || part.size() > maxPartBytes) throw UnreadableException(tr("The file unpacks to far more data than it holds, so it wasn't opened."))
                                part.write(buf, 0, n)
                            }
                        } catch (x: UnreadableException) { throw x } catch (_: Exception) { break }
                        zout.putNextEntry(java.util.zip.ZipEntry(e.name)); part.writeTo(zout); zout.closeEntry(); parts++
                    }
                }
            }
            if (parts == 0) throw damaged
            return ZipFile(out).also { rebuilt = out }
        } catch (x: UnreadableException) { out.delete(); throw x } catch (_: Exception) { out.delete(); throw damaged }
    }
    private val exact = HashMap<String, java.util.zip.ZipEntry>()
    private val folded = HashMap<String, java.util.zip.ZipEntry>()
    private var total = 0L

    init {
        for (e in zip.entries()) {
            if (e.isDirectory) continue
            val n = e.name.trimStart('/').replace('\\', '/')
            exact[n] = e
            folded.putIfAbsent(n.lowercase(), e)
        }
    }

    val names: Set<String> get() = exact.keys

    private fun entry(name: String): java.util.zip.ZipEntry? {
        val n = normalise(name)
        return exact[n] ?: folded[n.lowercase()] ?: decodePercent(n).let { d -> exact[d] ?: folded[d.lowercase()] }
    }

    fun has(name: String) = entry(name) != null

    /** The part's bytes, or null when it isn't there. */
    fun bytes(name: String, limit: Long = maxPartBytes): ByteArray? = open(name, limit)?.use { it.readBytes() }

    /** A stream of the part, capped at [limit] bytes; null when it isn't there. */
    fun open(name: String, limit: Long = maxPartBytes): InputStream? {
        val e = entry(name) ?: return null
        val raw = try { zip.getInputStream(e) } catch (x: Exception) { throw UnreadableException(tr("The file is damaged: a part of it can't be unpacked.")) }
        return object : FilterInputStream(raw) {
            private var n = 0L
            private fun count(k: Int): Int {
                if (k > 0) {
                    n += k; total += k
                    if (n > limit || total > maxTotalBytes) throw UnreadableException(tr("The file unpacks to far more data than it holds, so it wasn't opened."))
                }
                return k
            }
            override fun read(): Int { val c = super.read(); if (c >= 0) count(1); return c }
            override fun read(b: ByteArray, off: Int, len: Int): Int = try { count(super.read(b, off, len)) } catch (x: java.util.zip.ZipException) {
                throw UnreadableException(tr("The file is damaged: a part of it can't be unpacked."))
            }
        }
    }

    fun xml(name: String, html: Boolean = false): XmlReader? = open(name)?.let { XmlReader.of(it, html) }

    override fun close() { try { zip.close() } finally { rebuilt?.delete() } }

    companion object {
        fun normalise(name: String): String {
            val parts = ArrayList<String>()
            for (p in name.replace('\\', '/').split('/')) {
                when (p) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(p) }
            }
            return parts.joinToString("/")
        }

        /** "a%20b" → "a b" (UTF-8). */
        fun decodePercent(s: String): String {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (h != null) { out.write(h); i += 3; continue }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString("UTF-8")
        }

        /** The folder part of a part name ("word/document.xml" → "word"). */
        fun folder(name: String) = normalise(name).substringBeforeLast('/', "")

        /** [target] relative to the folder of [base] (absolute when it starts with '/'). */
        fun resolve(base: String, target: String): String =
            if (target.startsWith("/")) normalise(target) else normalise(folder(base).let { if (it.isEmpty()) target else "$it/$target" })
    }
}

/** One relationship of an Office Open XML part. */
class Rel(val id: String, val type: String, val target: String, val external: Boolean)

/** The relationships of [part] (from its _rels/<name>.rels; "" = the package's), by id; targets resolved to part names. */
fun Parts.rels(part: String): Map<String, Rel> {
    val n = Parts.normalise(part)
    val relsName = if (n.isEmpty()) "_rels/.rels" else (Parts.folder(n).let { if (it.isEmpty()) "" else "$it/" }) + "_rels/" + n.substringAfterLast('/') + ".rels"
    val x = xml(relsName) ?: return emptyMap()
    val out = HashMap<String, Rel>()
    while (x.next() != XmlReader.Event.EOF) {
        if (x.event == XmlReader.Event.START && x.local == "Relationship") {
            val id = x.attr("Id") ?: continue
            val target = x.attr("Target") ?: continue
            val external = x.attr("TargetMode").equals("External", ignoreCase = true)
            out[id] = Rel(id, x.attr("Type") ?: "", if (external) target else Parts.resolve(n, Parts.decodePercent(target)), external)
        }
    }
    return out
}

/** The part a relationship type ending in [typeSuffix] points to from [part] ("" = the package). */
fun Parts.relTarget(part: String, typeSuffix: String): String? = rels(part).values.firstOrNull { !it.external && it.type.endsWith(typeSuffix) }?.target

/** The package's main part (the document, workbook or presentation), from _rels/.rels. */
fun Parts.mainPart(): String? = relTarget("", "/officeDocument")

/** Content types by part name, from [Content_Types].xml (overrides, then defaults by extension). */
fun Parts.contentType(part: String): String? {
    val x = xml("[Content_Types].xml") ?: return null
    val n = Parts.normalise(part)
    val ext = n.substringAfterLast('.', "").lowercase()
    var byDefault: String? = null
    while (x.next() != XmlReader.Event.EOF) {
        if (x.event != XmlReader.Event.START) continue
        if (x.local == "Override" && Parts.normalise(x.attr("PartName") ?: "").equals(n, ignoreCase = true)) return x.attr("ContentType")
        if (x.local == "Default" && x.attr("Extension")?.lowercase() == ext) byDefault = x.attr("ContentType")
    }
    return byDefault
}
