package com.localmediatools.codec.raw

import com.localmediatools.i18n.tr

import java.io.OutputStream

/** A RAW file's sensor data: one value per photosite, row by row. */
class Mosaic(val width: Int, val height: Int, val data: ShortArray) {
    init { require(data.size == width * height) }
    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFFFF
}

/**
 * Bytes written over the original file at [offset] ([length] bytes: [bytes], then zeros), or
 * after its end. Everything a RAW file holds besides its sensor data (previews, maker notes,
 * metadata) stays byte for byte as it was.
 */
class Patch(val offset: Long, val bytes: ByteArray, val length: Long = bytes.size.toLong()) {
    init { require(length >= bytes.size) }
}

/** What the app knows about a RAW file's sensor data. */
class RawInfo(
    /** "CR2", "DNG", "NEF", "ARW". */
    val format: String,
    /** How the sensor data is stored, for logs and tests ("lossless JPEG", "uncompressed"…). */
    val variant: String,
    val width: Int,
    val height: Int,
    /** Largest value a photosite can hold in this file. */
    val maxValue: Int,
    /** The picture's photosites (top, left, bottom, right); the rest is masked sensor area, kept as it is. */
    val active: IntArray,
)

/** Reads a RAW file's sensor data and writes it back in the same format. */
abstract class RawCodec internal constructor(protected val src: ByteSource, protected val tiff: Tiff) {
    abstract val info: RawInfo
    abstract fun decode(): Mosaic

    /** Patches that make the file hold [m] (same size as [decode]'s) in place of its sensor data. */
    abstract fun encode(m: Mosaic): List<Patch>

    protected fun checkSize(m: Mosaic) {
        require(m.width == info.width && m.height == info.height) { "mosaic ${m.width}×${m.height}, file ${info.width}×${info.height}" }
        val max = info.maxValue
        for (v in m.data) if ((v.toInt() and 0xFFFF) > max) throw IllegalArgumentException("value over $max")
    }
}

object RawFiles {
    /** File extensions of camera RAW formats (for naming and for tools that keep the format). */
    val EXTENSIONS = setOf("dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "srw", "x3f", "3fr", "iiq", "erf", "kdc", "mef", "mos", "rwl", "gpr", "ori", "fff", "dcr", "mrw")

    /**
     * Opens [src] for reading and rewriting its sensor data. Throws [RawUnsupportedException] for
     * RAW variants this app can't write back exactly, [RawFormatException] for damaged files.
     */
    fun open(src: ByteSource): RawCodec {
        val tiff = Tiff.open(src) ?: throw RawUnsupportedException("not a TIFF-based RAW file")
        val ifds = tiff.allIfds()
        val make = ifds.firstNotNullOfOrNull { it[Tiff.TAG_MAKE] }?.let { tiff.string(it) } ?: ""
        val head = src.bytes(0, minOf(16L, src.size).toInt())
        val cr2 = head.size >= 11 && head[8] == 'C'.code.toByte() && head[9] == 'R'.code.toByte() && head[10].toInt() == 2
        return when {
            ifds.any { it.has(Tiff.TAG_DNG_VERSION) } -> DngCodec(src, tiff, ifds)
            cr2 -> Cr2Codec(src, tiff)
            make.startsWith("NIKON", ignoreCase = true) -> NefCodec(src, tiff, ifds)
            make.startsWith("SONY", ignoreCase = true) -> ArwCodec(src, tiff, ifds)
            else -> throw RawUnsupportedException("RAW files from ${make.ifBlank { "this camera" }}", make.takeIf { it.isNotBlank() }?.let { tr("{0} RAW files", it.trim()) })
        }
    }

    /** Writes [src] with [patches] applied to [out]. */
    fun write(src: ByteSource, patches: List<Patch>, out: OutputStream) {
        val sorted = patches.sortedBy { it.offset }
        val buf = ByteArray(1 shl 16)
        var pos = 0L
        fun copyUntil(x: Long) {
            while (pos < x) {
                val n = minOf(buf.size.toLong(), x - pos).toInt()
                src.read(pos, buf, 0, n); out.write(buf, 0, n); pos += n
            }
        }
        val zeros = ByteArray(1 shl 16)
        for (p in sorted) {
            if (p.offset < pos) throw IllegalStateException("overlapping patches at ${p.offset}")
            if (p.offset >= src.size) {
                copyUntil(src.size)
                if (p.offset != pos) throw IllegalStateException("gap after the end of the file")
            } else {
                if (p.offset + p.length > src.size) throw IllegalStateException("patch past the end of the file")
                copyUntil(p.offset)
            }
            out.write(p.bytes)
            var z = p.length - p.bytes.size
            while (z > 0) { val n = minOf(z, zeros.size.toLong()).toInt(); out.write(zeros, 0, n); z -= n }
            pos = p.offset + p.length
        }
        copyUntil(src.size)
    }
}

/** Collects patches; new data that doesn't fit its old place goes after the end of the file. */
internal class PatchSet(private val tiff: Tiff, fileSize: Long) {
    val list = ArrayList<Patch>()
    private var end = fileSize

    fun add(p: Patch) { list.add(p) }

    /** Puts [data] where [slotLen] bytes at [slotOff] were (the rest zeroed) if it fits, else at the end. Returns where. */
    fun place(slotOff: Long, slotLen: Long, data: ByteArray): Long {
        if (data.size <= slotLen) { list.add(Patch(slotOff, data, slotLen)); return slotOff }
        if (end and 1L != 0L) { list.add(Patch(end, ByteArray(1))); end++ }
        val at = end
        list.add(Patch(at, data)); end += data.size
        return at
    }

    /** Sets value [index] of an integer entry (offsets and byte counts). */
    fun setInt(e: TiffEntry, index: Int, v: Long) {
        val size = Tiff.typeSize(e.type)
        val b = ByteArray(size)
        when (e.type) {
            3 -> { if (v !in 0..0xFFFF) throw RawUnsupportedException("value too large for its tag"); tiff.putU16(b, 0, v.toInt()) }
            4, 13 -> { if (v !in 0..0xFFFFFFFFL) throw RawUnsupportedException("file too large"); tiff.putU32(b, 0, v) }
            else -> throw RawFormatException("tag ${e.tag} is not an integer")
        }
        list.add(Patch(e.dataPos + index.toLong() * size, b))
    }
}

/** The strips or tiles holding an image: where each is and which pixels it covers. */
internal class Blocks(val tiff: Tiff, ifd: Ifd, val width: Int, val height: Int) {
    val tiled = ifd.has(Tiff.TAG_TILE_OFFSETS)
    val offsetsEntry: TiffEntry = ifd[if (tiled) Tiff.TAG_TILE_OFFSETS else Tiff.TAG_STRIP_OFFSETS] ?: throw RawFormatException("no image data")
    val countsEntry: TiffEntry = ifd[if (tiled) Tiff.TAG_TILE_BYTE_COUNTS else Tiff.TAG_STRIP_BYTE_COUNTS] ?: throw RawFormatException("no image data sizes")
    val offsets: LongArray = tiff.ints(offsetsEntry)
    val counts: LongArray = tiff.ints(countsEntry)
    /** Block size in pixels (a strip is as wide as the image). */
    val bw: Int = if (tiled) tiff.int(ifd[Tiff.TAG_TILE_WIDTH]!!).toInt() else width
    val bh: Int = if (tiled) tiff.int(ifd[Tiff.TAG_TILE_LENGTH] ?: throw RawFormatException("no tile length")).toInt()
        else (ifd[Tiff.TAG_ROWS_PER_STRIP]?.let { tiff.int(it).toInt() } ?: height).coerceIn(1, height)
    val across: Int = if (tiled) (width + bw - 1) / bw else 1

    init {
        if (bw <= 0 || bh <= 0) throw RawFormatException("bad block size")
        val down = (height + bh - 1) / bh
        if (offsets.size != counts.size || offsets.size < across * down) throw RawFormatException("missing image blocks")
        for (i in offsets.indices) if (offsets[i] < 0 || offsets[i] + counts[i] > tiff.src.size) throw RawFormatException("image block outside the file")
    }

    val count: Int get() = across * ((height + bh - 1) / bh)
    fun top(i: Int) = (i / across) * bh
    fun left(i: Int) = (i % across) * bw
    /** Rows a block holds (a strip's last may be shorter; tiles are always whole). */
    fun rows(i: Int) = if (tiled) bh else minOf(bh, height - top(i))

    fun read(i: Int): ByteArray {
        if (counts[i] > Int.MAX_VALUE) throw RawFormatException("image block too large")
        return tiff.src.bytes(offsets[i], counts[i].toInt())
    }
}

/** Two-byte samples, one per photosite ([shift]: the value sits that many bits up; the bits below are kept). */
internal class Unpacked(private val bigEndian: Boolean, private val shift: Int = 0) {
    fun get(b: ByteArray, o: Int): Int {
        val x = b[o].toInt() and 0xFF; val y = b[o + 1].toInt() and 0xFF
        return (if (bigEndian) (x shl 8) or y else x or (y shl 8)) ushr shift
    }

    fun put(b: ByteArray, o: Int, v: Int) {
        val x = b[o].toInt() and 0xFF; val y = b[o + 1].toInt() and 0xFF
        val low = (if (bigEndian) (x shl 8) or y else x or (y shl 8)) and ((1 shl shift) - 1)
        val w = (v shl shift) or low
        if (bigEndian) { b[o] = (w ushr 8).toByte(); b[o + 1] = w.toByte() } else { b[o] = w.toByte(); b[o + 1] = (w ushr 8).toByte() }
    }
}

/** Uncompressed two-byte data in strips or tiles. */
internal fun decodeUnpacked(blocks: Blocks, u: Unpacked, m: Mosaic) {
    val w = blocks.width; val h = blocks.height
    for (i in 0 until blocks.count) {
        val b = blocks.read(i); val top = blocks.top(i); val left = blocks.left(i)
        val rows = blocks.rows(i)
        if (b.size.toLong() < rows.toLong() * blocks.bw * 2) throw RawFormatException("image block too short")
        for (r in 0 until rows) {
            val y = top + r; if (y >= h) break
            for (c in 0 until blocks.bw) {
                val x = left + c; if (x >= w) break
                m.data[y * w + x] = u.get(b, (r * blocks.bw + c) * 2).toShort()
            }
        }
    }
}

internal fun encodeUnpacked(blocks: Blocks, u: Unpacked, m: Mosaic, out: PatchSet) {
    val w = blocks.width; val h = blocks.height
    for (i in 0 until blocks.count) {
        val b = blocks.read(i); val top = blocks.top(i); val left = blocks.left(i)
        for (r in 0 until blocks.rows(i)) {
            val y = top + r; if (y >= h) break
            for (c in 0 until blocks.bw) {
                val x = left + c; if (x >= w) break
                u.put(b, (r * blocks.bw + c) * 2, m.data[y * w + x].toInt() and 0xFFFF)
            }
        }
        out.add(Patch(blocks.offsets[i], b))
    }
}

/** Lossless JPEG in strips or tiles (DNG): each block's samples fill it row by row. */
internal fun decodeLj92Blocks(blocks: Blocks, m: Mosaic) {
    val w = blocks.width; val h = blocks.height
    for (i in 0 until blocks.count) {
        val f = Lj92.decode(blocks.read(i))
        val top = blocks.top(i); val left = blocks.left(i); val bw = blocks.bw
        val s = f.samples
        for (k in s.indices) {
            val y = top + k / bw; val x = left + k % bw
            if (y < h && x < w) m.data[y * w + x] = s[k]
        }
    }
}

internal fun encodeLj92Blocks(blocks: Blocks, m: Mosaic, out: PatchSet) {
    val w = blocks.width; val h = blocks.height
    for (i in 0 until blocks.count) {
        val f = Lj92.decode(blocks.read(i))
        val top = blocks.top(i); val left = blocks.left(i); val bw = blocks.bw
        val s = f.samples.copyOf()
        for (k in s.indices) {
            val y = top + k / bw; val x = left + k % bw
            if (y < h && x < w) s[k] = m.data[y * w + x]
        }
        if (s.contentEquals(f.samples)) continue   // unchanged blocks keep their bytes
        val data = Lj92.encode(f, s)
        val at = out.place(blocks.offsets[i], blocks.counts[i], data)
        if (at != blocks.offsets[i]) out.setInt(blocks.offsetsEntry, i, at)
        out.setInt(blocks.countsEntry, i, data.size.toLong())
    }
}

/** The directory with the full-size colour-filter sensor data (DNG, NEF, ARW). */
internal fun cfaIfd(tiff: Tiff, ifds: List<Ifd>): Ifd {
    fun v(d: Ifd, tag: Int) = d[tag]?.let { tiff.int(it) }
    val cand = ifds.filter { v(it, Tiff.TAG_PHOTOMETRIC) == Tiff.PHOTOMETRIC_CFA.toLong() && (v(it, Tiff.TAG_NEW_SUBFILE_TYPE) ?: 0L) == 0L }
    val best = cand.maxByOrNull { (v(it, Tiff.TAG_WIDTH) ?: 0L) * (v(it, Tiff.TAG_HEIGHT) ?: 0L) }
    if (best == null) {
        if (ifds.any { v(it, Tiff.TAG_PHOTOMETRIC) == 34892L }) throw RawUnsupportedException("linear (demosaiced) DNG", tr("a DNG holding a finished picture instead of sensor data"))
        throw RawUnsupportedException("no colour-filter sensor data")
    }
    if ((v(best, Tiff.TAG_SAMPLES_PER_PIXEL) ?: 1L) != 1L) throw RawUnsupportedException("several samples per photosite")
    best[Tiff.TAG_CFA_REPEAT_DIM]?.let { e -> val d = tiff.ints(e); if (d.size >= 2 && (d[0] != 2L || d[1] != 2L)) throw RawUnsupportedException("colour filter of ${d[0]}×${d[1]} (not Bayer)") }
    return best
}

internal fun dims(tiff: Tiff, ifd: Ifd): Pair<Int, Int> {
    val w = tiff.int(ifd[Tiff.TAG_WIDTH] ?: throw RawFormatException("no width")).toInt()
    val h = tiff.int(ifd[Tiff.TAG_HEIGHT] ?: throw RawFormatException("no height")).toInt()
    if (w <= 0 || h <= 0 || w.toLong() * h > 200_000_000L) throw RawFormatException("implausible size $w×$h")
    return w to h
}

// ===================================================================================== DNG
internal class DngCodec(src: ByteSource, tiff: Tiff, ifds: List<Ifd>) : RawCodec(src, tiff) {
    private val ifd = cfaIfd(tiff, ifds)
    private val compression = ifd[Tiff.TAG_COMPRESSION]?.let { tiff.int(it).toInt() } ?: 1
    private val bits = ifd[Tiff.TAG_BITS]?.let { tiff.int(it).toInt() } ?: 16
    private val w: Int
    private val h: Int
    private val blocks: Blocks
    override val info: RawInfo

    init {
        if (ifd.has(Tiff.TAG_LINEARIZATION)) throw RawUnsupportedException("DNG with a linearization table", tr("a DNG with a linearization table"))
        val (ww, hh) = dims(tiff, ifd); w = ww; h = hh
        blocks = Blocks(tiff, ifd, w, h)
        val variant = when (compression) {
            1 -> if (bits == 16) "uncompressed" else throw RawUnsupportedException("$bits-bit packed DNG", tr("a DNG with packed {0}-bit data", bits))
            7 -> "lossless JPEG"
            else -> throw RawUnsupportedException("DNG compression $compression")
        }
        val white = ifd[Tiff.TAG_WHITE_LEVEL]?.let { tiff.int(it).toInt() }
        val max = if (compression == 7) (1 shl Lj92.header(blocks.read(0)).precision) - 1 else 65535
        val area = ifd[Tiff.TAG_ACTIVE_AREA]?.let { e -> tiff.ints(e).map { it.toInt() } }
        val active = if (area != null && area.size == 4 && area[0] in 0 until area[2] && area[1] in 0 until area[3] && area[2] <= h && area[3] <= w)
            area.toIntArray() else intArrayOf(0, 0, h, w)
        info = RawInfo("DNG", variant, w, h, max.coerceAtLeast(white ?: 0).coerceAtMost(65535), active)
    }

    override fun decode(): Mosaic {
        val m = Mosaic(w, h, ShortArray(w * h))
        if (compression == 7) decodeLj92Blocks(blocks, m) else decodeUnpacked(blocks, Unpacked(!tiff.littleEndian), m)
        return m
    }

    override fun encode(m: Mosaic): List<Patch> {
        checkSize(m)
        val out = PatchSet(tiff, src.size)
        if (compression == 7) encodeLj92Blocks(blocks, m, out) else encodeUnpacked(blocks, Unpacked(!tiff.littleEndian), m, out)
        return out.list
    }
}

// ===================================================================================== CR2
internal class Cr2Codec(src: ByteSource, tiff: Tiff) : RawCodec(src, tiff) {
    private val ifd: Ifd
    private val offsetsEntry: TiffEntry
    private val countsEntry: TiffEntry
    private val offset: Long
    private val length: Long
    private val slices: IntArray
    private val frameWidth: Int
    private val frameHeight: Int
    private val comps: Int
    private val w: Int
    private val h: Int
    override val info: RawInfo

    init {
        var p = tiff.firstIfd()
        var d = tiff.ifd(p)
        for (k in 0 until 3) { p = d.nextPos; if (p == 0L) throw RawFormatException("CR2 without its sensor data directory"); d = tiff.ifd(p) }
        ifd = d
        offsetsEntry = ifd[Tiff.TAG_STRIP_OFFSETS] ?: throw RawFormatException("no sensor data")
        countsEntry = ifd[Tiff.TAG_STRIP_BYTE_COUNTS] ?: throw RawFormatException("no sensor data size")
        if (offsetsEntry.count != 1L) throw RawUnsupportedException("CR2 in ${offsetsEntry.count} strips")
        offset = tiff.int(offsetsEntry); length = tiff.int(countsEntry)
        if (offset + length > src.size || length > Int.MAX_VALUE) throw RawFormatException("sensor data outside the file")
        slices = ifd[Tiff.TAG_CR2_SLICES]?.let { e -> tiff.ints(e).map { it.toInt() }.toIntArray() } ?: intArrayOf(0, 0, 0)
        val head = Lj92.header(src.bytes(offset, length.toInt()))
        frameWidth = head.width; frameHeight = head.height; comps = head.components
        if (comps % 2 != 0 && comps != 1) throw RawUnsupportedException("CR2 with $comps components")
        var rw = frameWidth * comps; var rh = frameHeight
        if (rw > 4 * rh && comps % 2 == 0) { rw /= 2; rh *= 2 }
        if (rw == 3984) throw RawUnsupportedException("this camera's CR2 layout")
        if (slices[0] != 0 && (slices.size < 3 || slices[1] <= 0 || slices[2] <= 0 || slices[0] * slices[1] + slices[2] != rw)) throw RawUnsupportedException("CR2 slices ${slices.toList()}")
        w = rw; h = rh
        info = RawInfo("CR2", "lossless JPEG", w, h, (1 shl head.precision) - 1, canonActiveArea(w, h) ?: intArrayOf(0, 0, h, w))
    }

    private fun header(): Lj92Frame = Lj92.decode(src.bytes(offset, length.toInt()))

    /** The sensor's picture area from Canon's maker note (SensorInfo), or null. */
    private fun canonActiveArea(w: Int, h: Int): IntArray? = try {
        val exif = tiff.ifd(tiff.firstIfd())[Tiff.TAG_EXIF_IFD]?.let { tiff.ifd(tiff.int(it)) }
        val note = exif?.get(Tiff.TAG_MAKER_NOTE)
        val mn = note?.let { tiff.ifd(it.dataPos) }
        val si = mn?.get(0xE0)?.let { tiff.ints(it) }
        if (si != null && si.size >= 9) {
            val left = si[5].toInt(); val top = si[6].toInt(); val right = si[7].toInt() + 1; val bottom = si[8].toInt() + 1
            if (top in 0 until bottom && left in 0 until right && bottom <= h && right <= w) intArrayOf(top, left, bottom, right) else null
        } else null
    } catch (_: RawFormatException) { null }

    /** Where stream sample [k] goes in the mosaic (row × width + column), or −1 (LibRaw's lossless_jpeg_load_raw). */
    private inline fun forEachSample(total: Int, action: (k: Int, idx: Int) -> Unit) {
        val s0 = slices[0]; val s1 = slices[1]; val s2 = slices[2]
        for (k in 0 until total) {
            val row: Int; val col: Int
            if (s0 != 0) {
                var i = k / (s1 * h)
                val j = if (i >= s0) 1 else 0
                if (j == 1) i = s0
                val jidx = k - i * (s1 * h)
                val sw = if (j == 1) s2 else s1
                row = jidx / sw; col = jidx % sw + i * s1
            } else { row = k / w; col = k % w }
            action(k, if (row < h && col < w) row * w + col else -1)
        }
    }

    override fun decode(): Mosaic {
        val f = header()
        val m = Mosaic(w, h, ShortArray(w * h))
        forEachSample(f.samples.size) { k, idx -> if (idx >= 0) m.data[idx] = f.samples[k] }
        return m
    }

    override fun encode(m: Mosaic): List<Patch> {
        checkSize(m)
        val f = header()
        val s = f.samples.copyOf()
        forEachSample(s.size) { k, idx -> if (idx >= 0) s[k] = m.data[idx] }
        val out = PatchSet(tiff, src.size)
        if (s.contentEquals(f.samples)) return out.list
        val data = Lj92.encode(f, s)
        val at = out.place(offset, length, data)
        if (at != offset) out.setInt(offsetsEntry, 0, at)
        out.setInt(countsEntry, 0, data.size.toLong())
        return out.list
    }
}

// ===================================================================================== ARW
internal class ArwCodec(src: ByteSource, tiff: Tiff, ifds: List<Ifd>) : RawCodec(src, tiff) {
    private val ifd = cfaIfd(tiff, ifds)
    private val compression = ifd[Tiff.TAG_COMPRESSION]?.let { tiff.int(it).toInt() } ?: 1
    private val w: Int
    private val h: Int
    private val blocks: Blocks
    private val arw2: Boolean
    /** ARW2's tone curve: stored 11-bit code p → value curve[2p] (as LibRaw; dcraw also shifted it down by 2). */
    private val curve = IntArray(0x10000) { it }
    override val info: RawInfo

    init {
        val (ww, hh) = dims(tiff, ifd); w = ww; h = hh
        blocks = Blocks(tiff, ifd, w, h)
        if (blocks.tiled || blocks.count != 1) throw RawUnsupportedException("ARW in ${blocks.count} blocks")
        val bytes = blocks.counts[0]
        arw2 = compression == 32767 && bytes == w.toLong() * h
        val unpacked = (compression == 32767 || compression == 1) && bytes == w.toLong() * h * 2
        if (!arw2 && !unpacked) throw RawUnsupportedException("ARW compression $compression")
        if (arw2) {
            if (w % 32 != 0) throw RawUnsupportedException("ARW width $w")
            val sc = intArrayOf(0, 0, 0, 0, 0, 4095)
            ifds.firstNotNullOfOrNull { it[Tiff.TAG_SONY_CURVE] }?.let { e ->
                val v = tiff.ints(e, 4); for (c in 0 until 4) sc[c + 1] = ((v[c] ushr 2) and 0xFFF).toInt()
            }
            for (i in 0 until 5) for (j in sc[i] + 1..sc[i + 1]) curve[j] = curve[j - 1] + (1 shl i)
        }
        info = RawInfo("ARW", if (arw2) "compressed" else "uncompressed", w, h, if (arw2) curve[0x7FF shl 1] else 16383, intArrayOf(0, 0, h, w))
    }

    override fun decode(): Mosaic {
        val m = Mosaic(w, h, ShortArray(w * h))
        if (!arw2) { decodeUnpacked(blocks, Unpacked(!tiff.littleEndian), m); return m }
        val data = blocks.read(0)
        val pix = IntArray(16)
        for (row in 0 until h) {
            val base = row * w
            var col = 0; var dp = row * w
            while (col < w - 30) {
                arw2Block(data, dp, pix)
                for (i in 0 until 16) { m.data[base + col] = curve[pix[i] shl 1].toShort(); col += 2 }
                col -= if (col and 1 != 0) 1 else 31
                dp += 16
            }
        }
        return m
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or (if (o + 1 < b.size) (b[o + 1].toInt() and 0xFF) shl 8 else 0)

    private fun arw2Block(b: ByteArray, dp: Int, pix: IntArray) {
        val v = le32(b, dp)
        val max = v and 0x7FF; val min = (v ushr 11) and 0x7FF
        val imax = (v ushr 22) and 0x0F; val imin = (v ushr 26) and 0x0F
        var sh = 0
        while (sh < 4 && (0x80 shl sh) <= max - min) sh++
        var bit = 30
        for (i in 0 until 16) {
            pix[i] = when (i) {
                imax -> max
                imin -> min
                else -> {
                    val d = (le16(b, dp + (bit ushr 3)) ushr (bit and 7)) and 0x7F
                    bit += 7
                    minOf((d shl sh) + min, 0x7FF)
                }
            }
        }
    }

    override fun encode(m: Mosaic): List<Patch> {
        checkSize(m)
        val out = PatchSet(tiff, src.size)
        if (!arw2) { encodeUnpacked(blocks, Unpacked(!tiff.littleEndian), m, out); return out.list }
        // Value → the 11-bit code whose value is nearest (the curve rises, so codes rise with values).
        val top = curve[0x7FF shl 1]
        val code = IntArray(top + 1)
        fun c(p: Int) = curve[p shl 1]
        var p = 0
        for (v in 0..top) {
            while (p < 0x7FF && Math.abs(c(p + 1) - v) <= Math.abs(c(p) - v)) p++
            code[v] = p
        }
        val data = blocks.read(0)
        val want = IntArray(16); val pix = IntArray(16); val old = IntArray(16); val got = IntArray(16)
        val block = ByteArray(16)
        for (row in 0 until h) {
            val base = row * w
            var col = 0; var dp = row * w
            while (col < w - 30) {
                for (i in 0 until 16) { want[i] = minOf(m.data[base + col + 2 * i].toInt() and 0xFFFF, top); pix[i] = code[want[i]] }
                // Keep the block as the camera wrote it unless re-encoding comes closer to the new values
                // (the format quantises a block's values in steps that grow with its range).
                arw2Block(data, dp, old)
                var same = true; var errOld = 0L
                for (i in 0 until 16) { val v = curve[old[i] shl 1]; if (v != want[i]) same = false; errOld += (v - want[i]).toLong() * (v - want[i]) }
                if (!same) {
                    writeBlock(block, 0, pix, want, ::c)
                    arw2Block(block, 0, got)
                    var errNew = 0L
                    for (i in 0 until 16) { val v = curve[got[i] shl 1]; errNew += (v - want[i]).toLong() * (v - want[i]) }
                    if (errNew < errOld) System.arraycopy(block, 0, data, dp, 16)
                }
                col += 32
                if (col and 1 == 0) col -= 31 else col -= 1
                dp += 16
            }
        }
        out.add(Patch(blocks.offsets[0], data))
        return out.list
    }

    /** Codes [pix] (for values [want]) as one 16-byte block; each delta rounds to whichever neighbour step is nearer in value. */
    private fun writeBlock(b: ByteArray, dp: Int, pix: IntArray, want: IntArray, value: (Int) -> Int) {
        var imax = 0; var imin = 0
        for (i in 1 until 16) { if (pix[i] > pix[imax]) imax = i; if (pix[i] < pix[imin]) imin = i }
        if (imin == imax) imin = if (imax == 0) 1 else 0
        val max = pix[imax]; val min = pix[imin]
        var sh = 0
        while (sh < 4 && (0x80 shl sh) <= max - min) sh++
        val bits = LongArray(2)   // 128 bits, little-endian
        fun put(at: Int, n: Int, v: Int) { for (k in 0 until n) if ((v ushr k) and 1 != 0) { val q = at + k; bits[q ushr 6] = bits[q ushr 6] or (1L shl (q and 63)) } }
        put(0, 11, max); put(11, 11, min); put(22, 4, imax); put(26, 4, imin)
        var bit = 30
        for (i in 0 until 16) {
            if (i == imax || i == imin) continue
            val lo = ((pix[i] - min) ushr sh).coerceIn(0, 0x7F)
            val hi = minOf(lo + 1, 0x7F)
            fun v(d: Int) = value(minOf((d shl sh) + min, 0x7FF))
            val d = if (Math.abs(v(hi) - want[i]) < Math.abs(v(lo) - want[i])) hi else lo
            put(bit, 7, d); bit += 7
        }
        for (k in 0 until 16) b[dp + k] = (bits[k ushr 3] ushr ((k and 7) * 8)).toByte()
    }
}

// ===================================================================================== NEF
internal class NefCodec(src: ByteSource, tiff: Tiff, ifds: List<Ifd>) : RawCodec(src, tiff) {
    private val ifd = cfaIfd(tiff, ifds)
    private val compression = ifd[Tiff.TAG_COMPRESSION]?.let { tiff.int(it).toInt() } ?: 1
    private val bits = ifd[Tiff.TAG_BITS]?.let { tiff.int(it).toInt() } ?: 12
    private val w: Int
    private val h: Int
    private val blocks: Blocks
    private val unpacked: Unpacked?
    /** Nikon Z and D6 "packed 14 bits": four values in 7 bytes, least significant bits first, rows padded to 16 bytes. */
    private val packed14: Int
    private var vpred = IntArray(4)
    private var tree: HuffTable? = null
    override val info: RawInfo

    init {
        val (ww, hh) = dims(tiff, ifd); w = ww; h = hh
        blocks = Blocks(tiff, ifd, w, h)
        val total = blocks.counts.sum()
        val two = w.toLong() * h * 2
        unpacked = when {
            compression == 34713 && total == two && blocks.count == 1 -> Unpacked(bigEndian = true, shift = 4)
            compression == 1 && (bits == 14 || bits == 16) && total == two -> Unpacked(!tiff.littleEndian)
            else -> null
        }
        val line14 = ((w * 7 / 4 + 15) / 16) * 16
        packed14 = if (unpacked == null && compression == 34713 && bits == 14 && blocks.count == 1 && w % 4 == 0 && total == line14.toLong() * h) line14 else 0
        var variant = if (packed14 > 0) "packed 14-bit" else "uncompressed"
        if (unpacked == null && packed14 == 0) {
            if (compression != 34713) throw RawUnsupportedException("NEF compression $compression, $bits bits")
            if (blocks.count != 1) throw RawUnsupportedException("compressed NEF in ${blocks.count} strips")
            if ((w + 9L) / 10 * 16 * h == total || w.toLong() * h * 3 == total * 2 || w.toLong() * h * 3 == total) throw RawUnsupportedException("packed NEF", tr("packed NEF data"))
            readMeta()
            variant = "lossless compressed"
        }
        info = RawInfo("NEF", variant, w, h, if (unpacked != null && compression == 1 && bits == 16) 65535 else (1 shl bits) - 1, intArrayOf(0, 0, h, w))
    }

    /** Nikon's maker note tag 0x96: version, the four first predictions; only lossless files are written. */
    private fun readMeta() {
        val exif = tiff.allIfds().firstOrNull { it.has(Tiff.TAG_MAKER_NOTE) } ?: throw RawFormatException("no maker note")
        val note = exif[Tiff.TAG_MAKER_NOTE]!!
        val head = src.bytes(note.dataPos, 10)
        if (String(head, 0, 5, Charsets.ISO_8859_1) != "Nikon") throw RawUnsupportedException("old Nikon maker note")
        val mt = Tiff.open(src, note.dataPos + 10) ?: throw RawFormatException("bad Nikon maker note")
        val mn = mt.ifd(mt.firstIfd())
        val meta = mn[0x96] ?: throw RawFormatException("no NEF decoding table")
        var p = meta.dataPos
        val ver0 = mt.u8(p); val ver1 = mt.u8(p + 1); p += 2
        if (ver0 == 0x49 || ver1 == 0x58) p += 2110
        if (ver0 != 0x46) throw RawUnsupportedException("lossy compressed NEF", tr("lossy compressed NEF"))
        vpred = IntArray(4) { mt.u16(p + it * 2L) }
        if (bits != 12 && bits != 14) throw RawUnsupportedException("$bits-bit NEF")
        tree = nikonTree(if (bits == 14) 5 else 2)
    }

    override fun decode(): Mosaic {
        val m = Mosaic(w, h, ShortArray(w * h))
        if (unpacked != null) { decodeUnpacked(blocks, unpacked, m); return m }
        val data = blocks.read(0)
        if (packed14 > 0) {
            for (row in 0 until h) {
                var sp = row * packed14; var dp = row * w
                for (q in 0 until w / 4) {
                    fun b(k: Int) = data[sp + k].toInt() and 0xFF
                    m.data[dp] = (((b(1) and 0x3F) shl 8) or b(0)).toShort()
                    m.data[dp + 1] = (((b(3) and 0xF) shl 10) or (b(2) shl 2) or (b(1) ushr 6)).toShort()
                    m.data[dp + 2] = (((b(5) and 0x3) shl 12) or (b(4) shl 4) or (b(3) ushr 4)).toShort()
                    m.data[dp + 3] = ((b(6) shl 6) or (b(5) ushr 2)).toShort()
                    sp += 7; dp += 4
                }
            }
            return m
        }
        val br = PlainBitReader(data)
        val t = tree!!
        val vp = vpred.copyOf(); val hp = IntArray(2)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val len = br.huff(t)
                var diff = br.bits(len)
                if (len > 0 && diff and (1 shl (len - 1)) == 0) diff -= (1 shl len) - 1
                if (col < 2) { val k = (row and 1) * 2 + col; vp[k] = (vp[k] + diff).toShort().toInt() and 0xFFFF; hp[col] = vp[k] }
                else hp[col and 1] = (hp[col and 1] + diff).toShort().toInt() and 0xFFFF
                m.data[row * w + col] = hp[col and 1].toShort().toInt().coerceIn(0, 0x3FFF).toShort()
            }
        }
        return m
    }

    override fun encode(m: Mosaic): List<Patch> {
        checkSize(m)
        val out = PatchSet(tiff, src.size)
        if (unpacked != null) { encodeUnpacked(blocks, unpacked, m, out); return out.list }
        if (packed14 > 0) {
            val data = blocks.read(0)
            for (row in 0 until h) {
                var sp = row * packed14; var dp = row * w
                for (q in 0 until w / 4) {
                    var bitsAcc = 0L
                    for (k in 0 until 4) bitsAcc = bitsAcc or ((m.data[dp + k].toLong() and 0x3FFF) shl (14 * k))
                    for (k in 0 until 7) data[sp + k] = (bitsAcc ushr (8 * k)).toByte()
                    sp += 7; dp += 4
                }
            }
            out.add(Patch(blocks.offsets[0], data))
            return out.list
        }
        val t = tree!!
        val bw = BitWriter(blocks.counts[0].toInt(), stuffing = false)
        val vp = vpred.copyOf(); val hp = IntArray(2)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val v = m.data[row * w + col].toInt() and 0xFFFF
                val pred = if (col < 2) vp[(row and 1) * 2 + col] else hp[col and 1]
                val diff = v - pred
                if (col < 2) vp[(row and 1) * 2 + col] = v
                hp[col and 1] = v
                val len = if (diff == 0) 0 else 32 - Integer.numberOfLeadingZeros(Math.abs(diff))
                if (t.size[len] == 0 && len != 0) throw RawUnsupportedException("difference beyond the NEF code")
                bw.put(t.code[len], t.size[len])
                if (len > 0) bw.put(if (diff < 0) diff - 1 else diff, len)
            }
        }
        bw.flush()
        val data = bw.buf.copyOf(bw.len)
        val at = out.place(blocks.offsets[0], blocks.counts[0], data)
        if (at != blocks.offsets[0]) out.setInt(blocks.offsetsEntry, 0, at)
        out.setInt(blocks.countsEntry, 0, data.size.toLong())
        return out.list
    }

    companion object {
        /** LibRaw's nikon_tree: code counts per length 1–16, then the symbols. */
        private val TREES = arrayOf(
            intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 4, 3, 6, 2, 7, 1, 0, 8, 9, 11, 10, 12),
            intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0x39, 0x5a, 0x38, 0x27, 0x16, 5, 4, 3, 2, 1, 0, 11, 12, 12),
            intArrayOf(0, 1, 4, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 4, 6, 3, 7, 2, 8, 1, 9, 0, 10, 11, 12),
            intArrayOf(0, 1, 4, 3, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 5, 6, 4, 7, 8, 3, 9, 2, 1, 0, 10, 11, 12, 13, 14),
            intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 8, 0x5c, 0x4b, 0x3a, 0x29, 7, 6, 5, 4, 3, 2, 1, 0, 13, 14),
            intArrayOf(0, 1, 4, 2, 2, 3, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 7, 6, 8, 5, 9, 4, 10, 3, 11, 12, 2, 0, 1, 13, 14),
        )

        fun nikonTree(i: Int): HuffTable {
            val t = TREES[i]
            val counts = IntArray(17) { if (it == 0) 0 else t[it - 1] }
            val n = counts.sum()
            return HuffTable(counts, IntArray(n) { t[16 + it] })
        }
    }
}

/** Reads bits most significant first, without JPEG's byte stuffing; zeros past the end. */
internal class PlainBitReader(private val d: ByteArray) {
    private var pos = 0
    private var acc = 0L
    private var n = 0

    private fun fill() {
        while (n <= 56) { acc = (acc shl 8) or (if (pos < d.size) (d[pos].toLong() and 0xFF) else 0L); pos++; n += 8 }
    }

    fun peek(k: Int): Int { if (n < k) fill(); return ((acc ushr (n - k)) and ((1L shl k) - 1)).toInt() }
    fun bits(k: Int): Int { if (k == 0) return 0; val v = peek(k); n -= k; return v }

    fun huff(t: HuffTable): Int {
        val look = t.look[peek(HuffTable.LOOK)]
        if (look >= 0) { n -= look ushr 8; return look and 0xFF }
        var len = HuffTable.LOOK + 1
        var code = bits(len)
        while (len <= 16 && code > t.maxCode[len]) { code = (code shl 1) or bits(1); len++ }
        if (len > 16) throw RawFormatException("bad Huffman code")
        return t.vals[t.valPtr[len] + code - t.minCode[len]]
    }
}
