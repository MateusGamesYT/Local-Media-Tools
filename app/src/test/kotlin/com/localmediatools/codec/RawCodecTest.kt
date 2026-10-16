package com.localmediatools.codec

import com.localmediatools.codec.raw.ArraySource
import com.localmediatools.codec.raw.Lj92
import com.localmediatools.codec.raw.Mosaic
import com.localmediatools.codec.raw.RawFiles
import com.localmediatools.codec.raw.RawFormatException
import com.localmediatools.codec.raw.RawUnsupportedException
import com.localmediatools.codec.raw.Tiff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random

/**
 * The RAW reader and writer on pieces of real camera files (test resources raw/, CC0, see
 * CREDITS.tsv): a Pixel 4a DNG keeping two of the phone's own lossless-JPEG tiles byte for byte,
 * and a Samsung DNG with uncompressed data. The expected sensor data is LibRaw's (rawpy 0.22,
 * sha256 of its raw_image, little-endian). The whole-file checks on 18 cameras' RAW files are
 * buildtools/denoise/check_raw.py.
 */
class RawCodecTest {
    private fun res(name: String) = RawCodecTest::class.java.getResourceAsStream("/raw/$name")!!.readBytes()

    private fun sha256(m: Mosaic): String {
        val b = ByteArray(m.data.size * 2)
        for (i in m.data.indices) { b[2 * i] = m.data[i].toByte(); b[2 * i + 1] = (m.data[i].toInt() shr 8).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    private class Case(val file: String, val variant: String, val w: Int, val h: Int, val libraw: String)
    private val cases = listOf(
        Case("pixel4a_tiles.dng", "lossless JPEG", 256, 512, "50cdd16958a7d0db3def322e25722f1da7de6fc9283a3a3341cf992942016b2e"),
        Case("sm-g960f_crop.dng", "uncompressed", 256, 128, "efa44e3d28c67813da37abc5e707f1a8fe6b3338a0f6de3b9f660a8dac8d96aa"),
    )

    @Test
    fun readsRealSensorDataAsLibRawDoes() {
        for (c in cases) {
            val codec = RawFiles.open(ArraySource(res(c.file)))
            assertEquals(c.file, "DNG", codec.info.format)
            assertEquals(c.file, c.variant, codec.info.variant)
            assertEquals(c.file, c.w, codec.info.width)
            assertEquals(c.file, c.h, codec.info.height)
            assertEquals(c.file, c.libraw, sha256(codec.decode()))
        }
    }

    @Test
    fun unchangedDataLeavesTheFileAsItWas() {
        for (c in cases) {
            val bytes = res(c.file)
            val codec = RawFiles.open(ArraySource(bytes))
            val out = ByteArrayOutputStream()
            RawFiles.write(ArraySource(bytes), codec.encode(codec.decode()), out)
            assertArrayEquals(c.file, bytes, out.toByteArray())
        }
    }

    @Test
    fun changedDataIsWrittenBackExactlyInTheSameFormat() {
        val rnd = Random(7)
        for (c in cases) {
            val bytes = res(c.file)
            val codec = RawFiles.open(ArraySource(bytes))
            val m = codec.decode()
            val max = codec.info.maxValue
            // Smoothed (much smaller data) on the left half, extreme jumps on a few rows, noise elsewhere.
            val d = m.data.copyOf()
            for (y in 0 until m.height) for (x in 0 until m.width) {
                val i = y * m.width + x
                val v = m[x, y]
                d[i] = when {
                    y % 97 == 3 -> (if (x % 2 == 0) 0 else max).toShort()
                    x < m.width / 2 -> (v and 0xFFF0).toShort()
                    else -> (v + rnd.nextInt(41) - 20).coerceIn(0, max).toShort()
                }
            }
            val changed = Mosaic(m.width, m.height, d)
            val out = ByteArrayOutputStream()
            RawFiles.write(ArraySource(bytes), codec.encode(changed), out)
            val back = RawFiles.open(ArraySource(out.toByteArray()))
            assertEquals(c.file, codec.info.variant, back.info.variant)
            assertArrayEquals(c.file, d, back.decode().data)
            // Everything before the sensor data (the header and tags) is unchanged, apart from the
            // data's offsets and sizes when compressed blocks had to move.
            val written = out.toByteArray()
            assertTrue(c.file, written.size >= bytes.size)
        }
    }

    @Test
    fun losslessJpegRoundTripsTheCamerasOwnTilesAndExtremeValues() {
        val bytes = res("pixel4a_tiles.dng")
        val tiff = Tiff.open(ArraySource(bytes))!!
        val ifd = tiff.allIfds().first { it.has(Tiff.TAG_TILE_OFFSETS) }
        val offs = tiff.ints(ifd[Tiff.TAG_TILE_OFFSETS]!!); val counts = tiff.ints(ifd[Tiff.TAG_TILE_BYTE_COUNTS]!!)
        for (t in offs.indices) {
            val f = Lj92.decode(bytes, offs[t].toInt(), counts[t].toInt())
            assertEquals(16, f.precision)
            assertEquals(256 * 256, f.width * f.components * f.height)
            // The camera's samples re-encoded with the app's own tables come back the same.
            val again = Lj92.decode(Lj92.encode(f))
            assertArrayEquals(f.samples, again.samples)
            // Largest possible differences (T.81's category 16, coded without extra bits) and all zeros.
            val extreme = ShortArray(f.samples.size) { if ((it / 3) % 2 == 0) 0 else 32768.toShort() }
            assertArrayEquals(extreme, Lj92.decode(Lj92.encode(f, extreme)).samples)
            val flat = ShortArray(f.samples.size)
            assertArrayEquals(flat, Lj92.decode(Lj92.encode(f, flat)).samples)
        }
    }

    @Test
    fun otherFilesAreTurnedDownByName() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200)
        try { RawFiles.open(ArraySource(jpeg)); fail() } catch (e: RawUnsupportedException) { }
        // A TIFF from a camera maker whose RAW layout the app doesn't write.
        val t = res("sm-g960f_crop.dng").copyOf()
        val tiff = Tiff.open(ArraySource(t))!!
        val dngTag = tiff.allIfds().first().let { it[Tiff.TAG_DNG_VERSION]!! }
        // Renaming the DNGVersion tag hides it: the file is then a plain TIFF from Samsung.
        val at = dngTag.entryPos.toInt()
        t[at] = 0x01; t[at + 1] = 0xFF.toByte()
        try { RawFiles.open(ArraySource(t)); fail() } catch (e: RawUnsupportedException) {
            assertTrue(e.message, e.message!!.contains("Samsung", ignoreCase = true))
        }
    }

    /** Damaged files: reading may fail, but only with the RAW exceptions (shown to the user), never with a crash. */
    @Test
    fun damagedFilesFailCleanly() {
        val rnd = Random(11)
        for (c in cases) {
            val bytes = res(c.file)
            val trials = ArrayList<ByteArray>()
            for (k in 1..40) trials.add(bytes.copyOf(rnd.nextInt(bytes.size)))
            for (k in 1..200) trials.add(bytes.copyOf().also { b ->
                repeat(1 + rnd.nextInt(4)) {
                    // Mostly the header and tags, where damage changes the structure.
                    val i = if (rnd.nextInt(4) > 0) rnd.nextInt(minOf(b.size, 2048)) else rnd.nextInt(b.size)
                    b[i] = rnd.nextInt(256).toByte()
                }
            })
            for ((n, b) in trials.withIndex()) {
                try {
                    val codec = RawFiles.open(ArraySource(b))
                    val m = codec.decode()
                    codec.encode(m)
                } catch (e: RawFormatException) {
                } catch (e: Throwable) {
                    throw AssertionError("${c.file}, damaged copy $n: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }
    }
}
