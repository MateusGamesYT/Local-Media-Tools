package com.localmediatools.codec.raw

import java.io.RandomAccessFile

/** A RAW file this app can't read or write: the reason is for logs, the message shown is the caller's. */
open class RawFormatException(message: String) : Exception(message)

/**
 * A RAW file that is fine but uses a variant this app doesn't write (sRAW, lossy DNG, X-Trans…);
 * [shown]: what it is, in the user's language, for the message the user sees (null: no short name).
 */
class RawUnsupportedException(message: String, val shown: String? = null) : RawFormatException(message)

/** Random access to a file's bytes. */
interface ByteSource {
    val size: Long

    /** Reads [len] bytes at [offset] into [dst] from [dstOff]; fails past the end. */
    fun read(offset: Long, dst: ByteArray, dstOff: Int = 0, len: Int = dst.size - dstOff)

    fun bytes(offset: Long, len: Int): ByteArray = ByteArray(len).also { read(offset, it) }
}

class ArraySource(private val a: ByteArray) : ByteSource {
    override val size: Long get() = a.size.toLong()
    override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int) {
        if (offset < 0 || len < 0 || offset + len > a.size) throw RawFormatException("read past the end ($offset+$len of ${a.size})")
        System.arraycopy(a, offset.toInt(), dst, dstOff, len)
    }
}

class FileSource(private val f: RandomAccessFile) : ByteSource {
    override val size: Long = f.length()
    @Synchronized override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int) {
        if (offset < 0 || len < 0 || offset + len > size) throw RawFormatException("read past the end ($offset+$len of $size)")
        f.seek(offset); f.readFully(dst, dstOff, len)
    }
}

/** A file opened through a channel (a content provider's file descriptor), read with positional reads. */
class ChannelSource(private val ch: java.nio.channels.FileChannel) : ByteSource {
    override val size: Long = ch.size()
    override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int) {
        if (offset < 0 || len < 0 || offset + len > size) throw RawFormatException("read past the end ($offset+$len of $size)")
        val bb = java.nio.ByteBuffer.wrap(dst, dstOff, len)
        var at = offset
        while (bb.hasRemaining()) {
            val n = ch.read(bb, at)
            if (n < 0) throw RawFormatException("file ended early")
            at += n
        }
    }
}

/** One directory entry: where it is, and where its value is (inline or elsewhere in the file). */
class TiffEntry(val tag: Int, val type: Int, val count: Long, val entryPos: Long, val dataPos: Long) {
    val byteSize: Long get() = count * Tiff.typeSize(type)
}

class Ifd(val pos: Long, val entries: List<TiffEntry>, val nextPos: Long) {
    operator fun get(tag: Int): TiffEntry? = entries.firstOrNull { it.tag == tag }
    fun has(tag: Int) = entries.any { it.tag == tag }
}

/**
 * Reads TIFF structures (RAW files are TIFF-based): byte order from the header, directories,
 * values of any type. [base] is where offsets count from (a maker note's own header; else 0).
 */
class Tiff(val src: ByteSource, val littleEndian: Boolean, val base: Long = 0) {
    private val small = ByteArray(8)

    fun u8(p: Long): Int { src.read(p, small, 0, 1); return small[0].toInt() and 0xFF }
    fun u16(p: Long): Int { src.read(p, small, 0, 2); return u16(small, 0) }
    fun u32(p: Long): Long { src.read(p, small, 0, 4); return u32(small, 0) }

    fun u16(b: ByteArray, o: Int): Int {
        val x = b[o].toInt() and 0xFF; val y = b[o + 1].toInt() and 0xFF
        return if (littleEndian) x or (y shl 8) else (x shl 8) or y
    }

    fun u32(b: ByteArray, o: Int): Long =
        if (littleEndian) (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))
        else ((u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong())

    fun putU16(b: ByteArray, o: Int, v: Int) {
        if (littleEndian) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() } else { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    }

    fun putU32(b: ByteArray, o: Int, v: Long) {
        if (littleEndian) { putU16(b, o, (v and 0xFFFF).toInt()); putU16(b, o + 2, ((v ushr 16) and 0xFFFF).toInt()) }
        else { putU16(b, o, ((v ushr 16) and 0xFFFF).toInt()); putU16(b, o + 2, (v and 0xFFFF).toInt()) }
    }

    fun firstIfd(): Long = base + u32(base + 4)

    fun ifd(pos: Long): Ifd {
        if (pos < 8 || pos + 2 > src.size) throw RawFormatException("directory outside the file")
        val n = u16(pos)
        if (n == 0 || n > 1000) throw RawFormatException("implausible directory ($n entries)")
        val raw = src.bytes(pos + 2, n * 12 + 4)
        val list = ArrayList<TiffEntry>(n)
        for (i in 0 until n) {
            val o = i * 12
            val tag = u16(raw, o); val type = u16(raw, o + 2); val count = u32(raw, o + 4)
            val size = count * typeSize(type)
            val entryPos = pos + 2 + o
            val dataPos = if (size <= 4) entryPos + 8 else base + u32(raw, o + 8)
            list.add(TiffEntry(tag, type, count, entryPos, dataPos))
        }
        return Ifd(pos, list, u32(raw, n * 12).let { if (it == 0L) 0L else base + it })
    }

    /** Integer values of an entry (BYTE, SHORT, LONG, signed kinds, IFD), at most [max]. */
    fun ints(e: TiffEntry, max: Int = 1 shl 20): LongArray {
        val n = minOf(e.count, max.toLong()).toInt()
        val size = typeSize(e.type)
        if (e.dataPos < 0 || e.dataPos + n.toLong() * size > src.size) throw RawFormatException("tag ${e.tag} value outside the file")
        val b = src.bytes(e.dataPos, n * size)
        return LongArray(n) { i ->
            when (e.type) {
                1, 7 -> (b[i].toLong() and 0xFF)
                6 -> b[i].toLong()
                3 -> u16(b, i * 2).toLong()
                8 -> u16(b, i * 2).toShort().toLong()
                4, 13 -> u32(b, i * 4)
                9 -> u32(b, i * 4).toInt().toLong()
                else -> throw RawFormatException("tag ${e.tag} is not an integer (type ${e.type})")
            }
        }
    }

    fun int(e: TiffEntry): Long = ints(e, 1)[0]

    /** Numeric values of any number type (rationals divided out). */
    fun doubles(e: TiffEntry, max: Int = 4096): DoubleArray = when (e.type) {
        5, 10 -> {
            val n = minOf(e.count, max.toLong()).toInt()
            val b = src.bytes(e.dataPos, n * 8)
            DoubleArray(n) { i ->
                val num = u32(b, i * 8); val den = u32(b, i * 8 + 4)
                if (e.type == 10) num.toInt().toDouble() / den.toInt().toDouble().let { if (it == 0.0) 1.0 else it }
                else num.toDouble() / (if (den == 0L) 1.0 else den.toDouble())
            }
        }
        11 -> { val n = minOf(e.count, max.toLong()).toInt(); val b = src.bytes(e.dataPos, n * 4); DoubleArray(n) { Float.fromBits(u32(b, it * 4).toInt()).toDouble() } }
        12 -> {
            val n = minOf(e.count, max.toLong()).toInt(); val b = src.bytes(e.dataPos, n * 8)
            DoubleArray(n) { val hi = u32(b, it * 8 + if (littleEndian) 4 else 0); val lo = u32(b, it * 8 + if (littleEndian) 0 else 4); Double.fromBits((hi shl 32) or lo) }
        }
        else -> ints(e, max).let { a -> DoubleArray(a.size) { a[it].toDouble() } }
    }

    fun string(e: TiffEntry): String {
        val n = minOf(e.count, 4096L).toInt()
        val b = src.bytes(e.dataPos, n)
        val end = b.indexOf(0.toByte()).let { if (it < 0) n else it }
        return String(b, 0, end, Charsets.ISO_8859_1).trim()
    }

    /** Every directory: the main chain, sub-directories (SubIFDs) and the Exif directory. */
    fun allIfds(): List<Ifd> {
        val out = ArrayList<Ifd>(); val seen = HashSet<Long>()
        fun visit(pos: Long, depth: Int) {
            var p = pos
            var guard = 0
            while (p != 0L && seen.add(p) && guard++ < 64 && depth < 4) {
                val d = try { ifd(p) } catch (e: RawFormatException) { if (out.isEmpty()) throw e else return }
                out.add(d)
                d[TAG_SUB_IFDS]?.let { e -> for (s in ints(e, 64)) visit(base + s, depth + 1) }
                d[TAG_EXIF_IFD]?.let { e -> visit(base + int(e), depth + 1) }
                p = d.nextPos
            }
        }
        visit(firstIfd(), 0)
        return out
    }

    companion object {
        const val TAG_NEW_SUBFILE_TYPE = 0xFE
        const val TAG_WIDTH = 0x100
        const val TAG_HEIGHT = 0x101
        const val TAG_BITS = 0x102
        const val TAG_COMPRESSION = 0x103
        const val TAG_PHOTOMETRIC = 0x106
        const val TAG_MAKE = 0x10F
        const val TAG_MODEL = 0x110
        const val TAG_STRIP_OFFSETS = 0x111
        const val TAG_SAMPLES_PER_PIXEL = 0x115
        const val TAG_ROWS_PER_STRIP = 0x116
        const val TAG_STRIP_BYTE_COUNTS = 0x117
        const val TAG_PLANAR = 0x11C
        const val TAG_TILE_WIDTH = 0x142
        const val TAG_TILE_LENGTH = 0x143
        const val TAG_TILE_OFFSETS = 0x144
        const val TAG_TILE_BYTE_COUNTS = 0x145
        const val TAG_SUB_IFDS = 0x14A
        const val TAG_CFA_REPEAT_DIM = 0x828D
        const val TAG_CFA_PATTERN = 0x828E
        const val TAG_EXIF_IFD = 0x8769
        const val TAG_MAKER_NOTE = 0x927C
        const val TAG_DNG_VERSION = 0xC612
        const val TAG_LINEARIZATION = 0xC618
        const val TAG_BLACK_LEVEL = 0xC61A
        const val TAG_WHITE_LEVEL = 0xC61D
        const val TAG_ACTIVE_AREA = 0xC68D
        const val TAG_CR2_SLICES = 0xC640
        const val TAG_SONY_CURVE = 0x7010

        const val PHOTOMETRIC_CFA = 32803

        fun typeSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> 1
        }

        /** The TIFF at [at] in [src] ("II*\0" or "MM\0*"), or null. [magic] other than 42: ORF, RW2. */
        fun open(src: ByteSource, at: Long = 0, magics: Set<Int> = setOf(42)): Tiff? {
            if (src.size < at + 8) return null
            val h = src.bytes(at, 4)
            val le = when {
                h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> true
                h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> false
                else -> return null
            }
            val t = Tiff(src, le, at)
            return if (t.u16(at + 2) in magics) t else null
        }
    }
}
