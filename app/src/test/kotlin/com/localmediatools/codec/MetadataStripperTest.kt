package com.localmediatools.codec

import com.localmediatools.codec.meta.MetadataStripper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

class MetadataStripperTest {
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Big-endian TIFF with Orientation, Make, Model, DateTime, a GPS IFD and an IFD1 (thumbnail). */
    private fun exifTiff(orientation: Int): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("MM".toByteArray()); b.write(be16(42)); b.write(be32(8))
        val entries = listOf(
            intArrayOf(0x010F, 2, 4, 0x43414E00), // "CAN\0" inline
            intArrayOf(0x0110, 2, 4, 0x58313000), // "X10\0"
            intArrayOf(0x0112, 3, 1, orientation shl 16),
            intArrayOf(0x0132, 2, 4, 0x32303200),
            intArrayOf(0x8825, 4, 1, 8 + 2 + 5 * 12 + 4), // GPS IFD right after IFD0
        )
        b.write(be16(entries.size))
        for (e in entries) { b.write(be16(e[0])); b.write(be16(e[1])); b.write(be32(e[2])); b.write(be32(e[3])) }
        val ifd1At = 8 + 2 + 5 * 12 + 4 + 2 + 12 + 4
        b.write(be32(ifd1At))
        // GPS IFD with one entry (latitude ref).
        b.write(be16(1)); b.write(be16(1)); b.write(be16(2)); b.write(be32(2)); b.write(be32(0x4E000000)); b.write(be32(0))
        // IFD1 with a thumbnail offset tag.
        b.write(be16(1)); b.write(be16(0x0201)); b.write(be16(4)); b.write(be32(1)); b.write(be32(0)); b.write(be32(0))
        return b.toByteArray()
    }

    private fun jpegWithMetadata(orientation: Int): Pair<ByteArray, ByteArray> {
        val img = BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 16) for (x in 0 until 24) img.setRGB(x, y, (x * 10 shl 16) or (y * 15 shl 8) or 0x40)
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        val exif = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + exifTiff(orientation)
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray(Charsets.ISO_8859_1)
        val icc = "ICC_PROFILE\u0000\u0001\u0001fakeprofile".toByteArray(Charsets.ISO_8859_1)
        fun seg(m: Int, d: ByteArray) = byteArrayOf(0xFF.toByte(), m.toByte()) + be16(d.size + 2) + d
        // Insert after SOI (and after ImageIO's JFIF APP0, which is kept).
        val app0Len = ((plain[4].toInt() and 255) shl 8) or (plain[5].toInt() and 255)
        val cut = 4 + app0Len
        val withMeta = plain.copyOfRange(0, cut) + seg(0xE1, exif) + seg(0xE1, xmp) + seg(0xE2, icc) + seg(0xFE, "secret comment".toByteArray()) +
            plain.copyOfRange(cut, plain.size) + "MOTIONPHOTO-TRAILER".toByteArray()
        return withMeta to plain
    }

    /** Offset of the SOS marker's compressed data. */
    private fun scanData(j: ByteArray): ByteArray {
        var i = 2
        while (i < j.size) {
            val m = j[i + 1].toInt() and 255
            val len = ((j[i + 2].toInt() and 255) shl 8) or (j[i + 3].toInt() and 255)
            if (m == 0xDA) {
                var end = j.size - 2
                while (!(j[end].toInt() and 255 == 0xFF && j[end + 1].toInt() and 255 == 0xD9)) end--
                return j.copyOfRange(i, end + 2)
            }
            i += 2 + len
        }
        error("no SOS")
    }

    @Test fun jpegLosesMetadataButKeepsPixelsOrientationAndProfile() {
        val (src, plain) = jpegWithMetadata(6)
        val out = ByteArrayOutputStream()
        val r = MetadataStripper.stripJpeg(ByteArrayInputStream(src), out)
        val clean = out.toByteArray()
        assertTrue(r.removed.containsAll(listOf("GPS location", "camera make & model", "date & time", "embedded thumbnail", "XMP metadata", "descriptions & comments")))
        assertEquals(6, r.orientation)
        assertTrue(r.kept.contains("colour profile"))
        assertEquals("MOTIONPHOTO-TRAILER".length.toLong(), r.trailingBytes)
        val text = String(clean, Charsets.ISO_8859_1)
        assertFalse(text.contains("secret comment")); assertFalse(text.contains("xmpmeta")); assertFalse(text.contains("TRAILER"))
        assertTrue(text.contains("ICC_PROFILE"))
        // The compressed image data is byte-identical.
        assertArrayEquals(scanData(plain), scanData(clean))
        // Orientation survives as a minimal EXIF block, readable by our orientation reader.
        val o = com.localmediatools.codec.image.ExifOrientationReader.read(clean)
        assertEquals(com.localmediatools.codec.image.Orientation.fromExif(6), o)
        // Still a valid JPEG of the same size.
        val img = ImageIO.read(ByteArrayInputStream(clean))
        assertEquals(24, img.width); assertEquals(16, img.height)
    }

    @Test fun jpegWithoutOrientationGetsNoExif() {
        val (src, _) = jpegWithMetadata(1)
        val out = ByteArrayOutputStream()
        MetadataStripper.stripJpeg(ByteArrayInputStream(src), out)
        assertFalse(String(out.toByteArray(), Charsets.ISO_8859_1).contains("Exif"))
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32(); crc.update(type.toByteArray()); crc.update(data)
        return be32(data.size) + type.toByteArray() + data + be32(crc.value.toInt())
    }

    @Test fun pngDropsTextAndTimeKeepsImageChunks() {
        val img = BufferedImage(10, 6, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(3, 2, 0x7F00FF00)
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        // Insert tEXt + tIME + eXIf (orientation 8) after IHDR.
        val ihdrEnd = 8 + 25
        val meta = pngChunk("tEXt", "Comment\u0000my house".toByteArray(Charsets.ISO_8859_1)) + pngChunk("tIME", ByteArray(7)) + pngChunk("eXIf", exifTiff(8)) +
            pngChunk("prVt", "private".toByteArray())
        val src = plain.copyOfRange(0, ihdrEnd) + meta + plain.copyOfRange(ihdrEnd, plain.size)
        val out = ByteArrayOutputStream()
        val r = MetadataStripper.stripPng(ByteArrayInputStream(src), out)
        val clean = out.toByteArray()
        val s = String(clean, Charsets.ISO_8859_1)
        assertFalse(s.contains("my house")); assertFalse(s.contains("tIME")); assertFalse(s.contains("prVt"))
        assertTrue(r.removed.contains("GPS location")); assertEquals(8, r.orientation)
        assertTrue(s.contains("eXIf"))
        val back = ImageIO.read(ByteArrayInputStream(clean))
        assertEquals(0x7F00FF00, back.getRGB(3, 2))
    }

    @Test fun webpDropsExifAndXmpAndFixesHeader() {
        // Minimal extended WebP: VP8X + fake VP8L payload + EXIF + XMP.
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        fun chunk(t: String, d: ByteArray) = t.toByteArray() + le32(d.size) + d + (if (d.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
        val vp8x = byteArrayOf(0x0C, 0, 0, 0, 9, 0, 0, 9, 0, 0) // EXIF+XMP flags, 10×10
        val body = chunk("VP8X", vp8x) + chunk("VP8L", byteArrayOf(0x2F, 1, 2, 3, 4)) + chunk("EXIF", exifTiff(1)) + chunk("XMP ", "<x:xmpmeta/>".toByteArray())
        val src = "RIFF".toByteArray() + le32(4 + body.size) + "WEBP".toByteArray() + body
        val out = ByteArrayOutputStream()
        val r = MetadataStripper.stripWebp({ ByteArrayInputStream(src) }, out)
        val clean = out.toByteArray()
        val s = String(clean, Charsets.ISO_8859_1)
        assertFalse(s.contains("EXIF")); assertFalse(s.contains("xmpmeta"))
        assertTrue(r.removed.contains("GPS location") && r.removed.contains("XMP metadata"))
        val size = (clean[4].toInt() and 255) or ((clean[5].toInt() and 255) shl 8)
        assertEquals(clean.size - 8, size)
        assertEquals(0, clean[20].toInt() and 0x0C) // VP8X flags cleared
        assertTrue(s.contains("VP8L"))
    }

    @Test fun gifDropsCommentsKeepsLoopAndFrames() {
        val b = ByteArrayOutputStream()
        b.write("GIF89a".toByteArray()); b.write(byteArrayOf(2, 0, 1, 0, 0x80.toByte(), 0, 0))
        b.write(byteArrayOf(0, 0, 0, -1, -1, -1)) // 2-colour palette
        b.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); b.write("NETSCAPE2.0".toByteArray()); b.write(byteArrayOf(3, 1, 0, 0, 0))
        b.write(byteArrayOf(0x21, 0xFE.toByte(), 5)); b.write("hello".toByteArray()); b.write(0)
        b.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); b.write("XMP DataXMP".toByteArray()); b.write(byteArrayOf(2, 'x'.code.toByte(), 'y'.code.toByte(), 0))
        b.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 1, 0, 0)); b.write(byteArrayOf(2, 2, 0x44, 0x01, 0))
        b.write(0x3B)
        val out = ByteArrayOutputStream()
        val r = MetadataStripper.stripGif(ByteArrayInputStream(b.toByteArray()), out)
        val s = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertFalse(s.contains("hello")); assertFalse(s.contains("XMP Data"))
        assertTrue(s.contains("NETSCAPE2.0"))
        assertTrue(r.removed.contains("descriptions & comments") && r.removed.contains("XMP metadata"))
        val img = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, img.width)
    }
}
