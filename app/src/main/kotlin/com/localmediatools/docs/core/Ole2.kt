package com.localmediatools.docs.core

import com.localmediatools.i18n.tr
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * The "compound file" container of Office 97–2003 files (.doc, .xls, .ppt): a small file system of
 * named streams in sectors. Streams are read whole, with every sector number checked, so a damaged
 * file stops with a message instead of looping or reading past its end.
 */
class Ole2(file: File) : Closeable {
    companion object {
        val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private const val FREE = -1
        private const val END = -2

        /** Which Office 97–2003 file [file] is; a password-protected newer Office file says so. */
        fun kind(file: File): DocFormat? = Ole2(file).use { o ->
            when {
                o.has("EncryptedPackage") || o.has("EncryptionInfo") -> throw UnreadableException(tr("This file is protected with a password, which this app can't open."))
                o.has("WordDocument") -> DocFormat.DOC
                o.has("Workbook") || o.has("Book") -> DocFormat.XLS
                o.has("PowerPoint Document") -> DocFormat.PPT
                o.has("PP40") || o.has("PP97_DUALSTORAGE") && !o.has("PowerPoint Document") -> throw UnreadableException(tr("This file was made by a very old PowerPoint version, which this app can't read."))
                else -> null
            }
        }
    }

    private val raf = RandomAccessFile(file, "r")
    private val size = raf.length()
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private class Entry(val name: String, val type: Int, val start: Int, val size: Long, val left: Int, val right: Int, val child: Int)
    private val entries = ArrayList<Entry>()
    private var miniStream: ByteArray = ByteArray(0)
    private val top = ArrayList<Entry>()

    init {
        try {
            val h = ByteArray(512)
            raf.seek(0); raf.readFully(h)
            if (!h.copyOf(8).contentEquals(SIGNATURE)) throw UnreadableException(tr("The file is damaged: its contents can't be unpacked."))
            val shift = le16(h, 30)
            sectorSize = 1 shl shift.coerceIn(7, 16)
            miniSectorSize = 1 shl le16(h, 32).coerceIn(4, 12)
            val fatSectors = le32(h, 44)
            val firstDir = le32(h, 48)
            miniCutoff = le32(h, 56).toLong().let { if (it <= 0) 4096 else it }
            val firstMiniFat = le32(h, 60)
            val miniFatSectors = le32(h, 64)
            var difatSector = le32(h, 68)
            val difatCount = le32(h, 72)
            val maxSectors = ((size - 512) / sectorSize).toInt() + 1
            if (fatSectors < 0 || fatSectors > maxSectors) throw UnreadableException(tr("The file is damaged: its contents can't be unpacked."))
            // DIFAT: the first 109 FAT sector numbers in the header, the rest in a chain of sectors.
            val fatList = ArrayList<Int>()
            for (i in 0 until 109) { val s = le32(h, 76 + i * 4); if (s >= 0 && fatList.size < fatSectors) fatList.add(s) }
            var guard = 0
            while (difatSector >= 0 && fatList.size < fatSectors && guard++ <= difatCount + 1) {
                val d = sector(difatSector)
                val per = sectorSize / 4 - 1
                for (i in 0 until per) { val s = le32(d, i * 4); if (s >= 0 && fatList.size < fatSectors) fatList.add(s) }
                difatSector = le32(d, per * 4)
            }
            val per = sectorSize / 4
            fat = IntArray(fatList.size * per)
            for ((k, s) in fatList.withIndex()) { val d = sector(s); for (i in 0 until per) fat[k * per + i] = le32(d, i * 4) }
            // Directory.
            val dir = chain(firstDir, fat, Long.MAX_VALUE, big = true)
            var i = 0
            while (i + 128 <= dir.size) {
                val nameLen = le16(dir, i + 64).coerceIn(0, 64)
                val name = String(dir, i, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                val type = dir[i + 66].toInt()
                val start = le32(dir, i + 116)
                val sz = (le32(dir, i + 120).toLong() and 0xFFFFFFFFL)
                entries.add(Entry(name, type, start, sz, le32(dir, i + 68), le32(dir, i + 72), le32(dir, i + 76)))
                i += 128
            }
            miniFat = if (firstMiniFat >= 0 && miniFatSectors > 0) chain(firstMiniFat, fat, Long.MAX_VALUE, big = true).let { b -> IntArray(b.size / 4) { le32(b, it * 4) } } else IntArray(0)
            entries.firstOrNull { it.type == 5 }?.let { root -> if (root.start >= 0 && root.size > 0) miniStream = chain(root.start, fat, root.size, big = true) }
            // The root's own children (a tree of siblings): streams inside embedded objects don't count as the file's.
            entries.firstOrNull { it.type == 5 }?.let { root ->
                val todo = ArrayDeque<Int>(); if (root.child >= 0) todo.add(root.child)
                val seen = HashSet<Int>()
                while (todo.isNotEmpty()) {
                    val k = todo.removeLast()
                    if (k !in entries.indices || !seen.add(k)) continue
                    val e = entries[k]; top.add(e)
                    if (e.left >= 0) todo.add(e.left); if (e.right >= 0) todo.add(e.right)
                }
            }
        } catch (e: UnreadableException) { raf.close(); throw e } catch (e: Exception) { raf.close(); throw UnreadableException(tr("The file is damaged: its contents can't be unpacked.")) }
    }

    private fun sector(n: Int): ByteArray {
        val off = (n.toLong() + 1) * sectorSize
        if (n < 0 || off + sectorSize > size + sectorSize) throw UnreadableException(tr("The file is damaged: its contents can't be unpacked."))
        val b = ByteArray(sectorSize)
        raf.seek(off)
        val avail = (size - off).coerceIn(0, sectorSize.toLong()).toInt()
        raf.readFully(b, 0, avail)
        return b
    }

    private fun chain(start: Int, table: IntArray, limit: Long, big: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        val unit = if (big) sectorSize else miniSectorSize
        var steps = 0
        val maxSteps = table.size + 1
        while (s >= 0 && out.size() < limit) {
            if (steps++ > maxSteps || s >= table.size && big && table.isNotEmpty() && s > table.size) throw UnreadableException(tr("The file is damaged: its contents can't be unpacked."))
            if (big) out.write(sector(s)) else {
                val off = s * unit
                if (off + unit > miniStream.size) break
                out.write(miniStream, off, unit)
            }
            s = if (s < table.size) table[s] else END
        }
        val bytes = out.toByteArray()
        return if (limit < bytes.size) bytes.copyOf(limit.toInt()) else bytes
    }

    private fun find(name: String): Entry? = (if (top.isNotEmpty()) top else entries).firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    /** True when the file itself (not an object embedded in it) has the stream [name]. */
    fun has(name: String) = find(name) != null

    /** The file's stream [name], or null. */
    fun stream(name: String): ByteArray? {
        val e = find(name) ?: return null
        if (e.size > Int.MAX_VALUE / 2 || e.size > size * 2 + 65536) throw UnreadableException(tr("The file is damaged: its contents can't be unpacked."))
        return if (e.size < miniCutoff) chain(e.start, miniFat, e.size, big = false) else chain(e.start, fat, e.size, big = true)
    }

    override fun close() = raf.close()
}

internal fun le16(b: ByteArray, o: Int): Int = if (o + 1 < b.size && o >= 0) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else 0
internal fun le32(b: ByteArray, o: Int): Int = if (o + 3 < b.size && o >= 0) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24) else 0
internal fun le64d(b: ByteArray, o: Int): Double {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (if (o + i < b.size) (b[o + i].toLong() and 0xFF) else 0)
    return java.lang.Double.longBitsToDouble(v)
}
