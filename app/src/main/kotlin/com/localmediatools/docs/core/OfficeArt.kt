package com.localmediatools.docs.core

/**
 * Pictures in Office 97–2003 drawings ("OfficeArt" BLIP records): PNG and JPEG data as stored, and
 * device-independent bitmaps given the file header BitmapFactory needs. Metafiles (EMF, WMF, PICT)
 * and TIFF give null: phones can't draw them.
 */
internal object OfficeArt {
    /** The image of the BLIP record at [at] in [d], or null. */
    fun blip(d: ByteArray, at: Int): ByteArray? {
        if (at < 0 || at + 8 > d.size) return null
        val verInst = le16(d, at); val type = le16(d, at + 2); val len = le32(d, at + 4)
        if (len <= 17 || at + 8 + len > d.size) return null
        val inst = verInst ushr 4
        val start = at + 8
        fun body(uids: Int) = d.copyOfRange(start + 16 * uids + 1, start + len)
        return when (type) {
            0xF01E -> body(if (inst == 0x6E1) 2 else 1)
            0xF01D, 0xF02A -> body(if (inst == 0x46B || inst == 0x6E3) 2 else 1)
            0xF01F -> bmp(body(if (inst == 0x7A9) 2 else 1))
            else -> null
        }
    }

    /** True for a BLIP record header at [at]. */
    fun isBlip(d: ByteArray, at: Int): Boolean =
        at + 8 <= d.size && le16(d, at) and 0xF == 0 && le16(d, at + 2) in 0xF01A..0xF02A && le32(d, at + 4) in 18..(d.size - at - 8)

    /** A device-independent bitmap with the 14-byte file header in front. */
    fun bmp(dib: ByteArray): ByteArray {
        if (dib.size < 40) return dib
        val headerSize = le32(dib, 0)
        val bits = le16(dib, 14)
        val colors = le32(dib, 32).let { if (it == 0 && bits <= 8) 1 shl bits else it }
        val offset = 14 + headerSize + colors * 4
        val out = ByteArray(14 + dib.size)
        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        fun put32(at: Int, v: Int) { for (k in 0..3) out[at + k] = (v ushr (8 * k)).toByte() }
        put32(2, out.size); put32(10, offset)
        System.arraycopy(dib, 0, out, 14, dib.size)
        return out
    }

    /** A shape's property table (OfficeArtFOPT and its tertiary twin): property id → value; complex data by id. */
    class Props(val values: Map<Int, Int>, val complex: Map<Int, ByteArray>) {
        operator fun get(id: Int): Int? = values[id]
        /** A boolean of a property set: [bit] when its "use" bit ([bit] shl 16) says it was set; null when not set. */
        fun flag(id: Int, bit: Int): Boolean? = values[id]?.let { v -> if (v and (bit shl 16) != 0) v and bit != 0 else null }
    }

    fun props(d: ByteArray, start: Int, len: Int, count: Int, into: MutableMap<Int, Int> = HashMap(), complex: MutableMap<Int, ByteArray> = HashMap()): Props {
        var p = start
        var cx = start + count * 6
        val end = start + len
        for (k in 0 until count) {
            if (p + 6 > end) break
            val opid = le16(d, p); val op = le32(d, p + 2); p += 6
            val id = opid and 0x3FFF
            if (opid and 0x8000 != 0) {
                if (op in 0..(end - cx)) complex[id] = d.copyOfRange(cx, cx + op)
                cx += op.coerceAtLeast(0)
            }
            into[id] = op
        }
        return Props(into, complex)
    }
}
