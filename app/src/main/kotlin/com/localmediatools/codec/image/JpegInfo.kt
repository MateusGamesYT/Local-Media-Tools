package com.localmediatools.codec.image

import java.io.InputStream

/** Frame information parsed from a JPEG header, used to decide whether bytes can be embedded as-is. */
data class JpegInfo(
    val width: Int,
    val height: Int,
    val components: Int,
    val precision: Int,
    val sofMarker: Int,
    val adobeTransform: Int?, // APP14 Adobe transform flag, null when there is no Adobe segment
    val hasAdobeSegment: Boolean,
) {
    /** Baseline/extended/progressive Huffman 8-bit JPEGs are universally supported by PDF readers. */
    val embeddableInPdf: Boolean
        get() = precision == 8 && sofMarker in intArrayOf(0xC0, 0xC1, 0xC2) &&
            components in intArrayOf(1, 3, 4) && width > 0 && height > 0

    companion object {
        /** Parses markers until the first SOF; returns null if the stream is not a valid JPEG. */
        fun parse(input: InputStream): JpegInfo? {
            val s = input
            if (s.read() != 0xFF || s.read() != 0xD8) return null
            var adobe = false
            var adobeTransform: Int? = null
            while (true) {
                var b = s.read()
                if (b < 0) return null
                if (b != 0xFF) return null
                var marker = s.read()
                while (marker == 0xFF) marker = s.read()
                if (marker < 0) return null
                if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) continue
                if (marker == 0xD9 || marker == 0xDA) return null // no frame header before scan
                val hi = s.read(); val lo = s.read()
                if (hi < 0 || lo < 0) return null
                val len = (hi shl 8) or lo
                if (len < 2) return null
                val payload = ByteArray(len - 2)
                var read = 0
                while (read < payload.size) {
                    val n = s.read(payload, read, payload.size - read)
                    if (n < 0) return null
                    read += n
                }
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (marker == 0xEE && payload.size >= 12 && String(payload, 0, 5, Charsets.ISO_8859_1) == "Adobe") {
                    adobe = true
                    adobeTransform = payload[11].toInt() and 0xFF
                }
                if (isSof) {
                    if (payload.size < 6) return null
                    val precision = payload[0].toInt() and 0xFF
                    val h = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
                    val w = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
                    val comps = payload[5].toInt() and 0xFF
                    return JpegInfo(w, h, comps, precision, marker, adobeTransform, adobe)
                }
                b = 0
            }
        }
    }
}
