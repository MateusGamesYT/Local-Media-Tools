package com.localmediatools.robo

import android.app.Application
import com.localmediatools.core.MediaKind
import com.localmediatools.core.SniffedFormat
import com.localmediatools.export.ItemOutcome
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.tools.AutoEnhanceJob
import com.localmediatools.tools.DenoiseJob
import com.localmediatools.tools.DenoiseStrength
import com.localmediatools.tools.EnhanceStrength
import com.localmediatools.tools.OptimizeImagesJob
import com.localmediatools.tools.StackJob
import com.localmediatools.tools.StackStep
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ToolRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * RAW photos keep their format: only the tools made to change it (converter, compressor) or that make
 * something new from photos (PDF, merges, prints…) take them; the Denoiser writes RAW files back as
 * RAW, and leaves alone the ones it can't. (The RAW reading and writing itself is tested on real
 * camera data in RawCodecTest; OpenCV's denoising doesn't run under Robolectric.)
 */
@RunWith(RobolectricTestRunner::class)
class RawRulesTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        inDir = File(app.cacheDir, "raw-in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "raw-out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() { outputs.uninstall() }

    private fun res(name: String) = RawRulesTest::class.java.getResourceAsStream("/raw/$name")!!.readBytes()
    private fun file(name: String, bytes: ByteArray) = Robo.item(app, Robo.write(inDir, name, bytes))
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** A Canon CR2's first bytes: a little-endian TIFF header, then "CR", version 2. */
    private val cr2Head = byteArrayOf(0x49, 0x49, 0x2A, 0, 0x10, 0, 0, 0, 0x43, 0x52, 2, 0) + ByteArray(500)

    @Test fun rawFilesAreRecognisedByTheirContents() {
        val cases = mapOf(
            file("a.cr2", cr2Head) to SniffedFormat.RAW_CAMERA,
            file("b.cr3", byteArrayOf(0, 0, 0, 0x18) + ascii("ftypcrx ") + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("c.raf", ascii("FUJIFILMCCD-RAW 0201FF383501") + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("d.orf", ascii("IIRO") + byteArrayOf(8, 0, 0, 0) + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("e.rw2", ascii("IIU") + byteArrayOf(0, 8, 0, 0, 0) + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("f.nef", byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8) + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            // Named like a JPEG, but a DNG inside.
            file("g.jpg", res("sm-g960f_crop.dng")) to SniffedFormat.DNG,
            file("h.dng", res("pixel4a_tiles.dng")) to SniffedFormat.DNG,
        )
        for ((item, format) in cases) {
            assertEquals(item.name, format, item.format)
            assertTrue(item.name, item.format.isRaw)
            assertEquals(item.name, MediaKind.IMAGE, item.kind)
        }
    }

    @Test fun rawPhotosKeepTheirFormatInToolsNotMadeToChangeIt() {
        val cr2 = file("shot.cr2", cr2Head)
        for (t in listOf(ToolId.AUTO_ENHANCE, ToolId.WATERMARK, ToolId.OPTIMIZE_IMAGES, ToolId.MAGIC_ERASER, ToolId.FACE_BLUR, ToolId.REMOVE_METADATA)) {
            val issue = ToolRules.issue(t, cr2)
            assertTrue("$t: $issue", issue != null && issue.contains("keep their format") && issue.contains("CR2"))
        }
        for (t in listOf(ToolId.DENOISE, ToolId.CONVERT_IMAGES, ToolId.COMPRESS_IMAGES, ToolId.IMAGES_TO_PDF, ToolId.MERGE_IMAGES)) {
            assertNull(t.name, ToolRules.issue(t, cr2))
        }
    }

    @Test fun aStackPassesRawFilesThroughStepsThatWouldChangeTheirFormat() {
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val results = Robo.runJob(app, StackJob(listOf(dng), listOf(
            StackStep(ToolId.AUTO_ENHANCE, "test", AutoEnhanceJob(emptyList(), EnhanceStrength.NATURAL)),
            StackStep(ToolId.OPTIMIZE_IMAGES, "test", OptimizeImagesJob(emptyList(), ImageOutFormat.PNG, false)),
        ), keepInBetween = false))
        val r = results.single()
        assertTrue(r.toString(), r.message != null && r.message!!.contains("left this RAW file as it is"))
        // Nothing was written in another format.
        assertTrue(outputs.committed.map { it.name }.toString(), outputs.committed.none { it.name.endsWith(".png") || it.name.endsWith(".jpg") })
    }

    @Test fun theDenoiserLeavesRawFilesItCantWriteBackAndSaysWhy() {
        val items = listOf(
            file("night.orf", ascii("IIRO") + byteArrayOf(8, 0, 0, 0) + ByteArray(500)),
            file("night.cr3", byteArrayOf(0, 0, 0, 0x18) + ascii("ftypcrx ") + ByteArray(500)),
        )
        val results = Robo.runJob(app, DenoiseJob(items, DenoiseStrength.NORMAL))
        assertEquals(results.toString(), 2, results.size)
        for (r in results) {
            assertEquals(r.toString(), ItemOutcome.SKIPPED, r.outcome)
            val label = r.inputName.substringAfterLast('.').uppercase()
            assertTrue(r.message, r.message!!.contains("can't be written back as $label"))
        }
        assertTrue(outputs.committed.isEmpty())
    }

    @Test fun aDamagedRawFileIsReportedNotCrashedOn() {
        val good = res("pixel4a_tiles.dng")
        val broken = file("cut.dng", good.copyOf(good.size / 3))
        val r = Robo.runJob(app, DenoiseJob(listOf(broken), DenoiseStrength.NORMAL)).single()
        assertEquals(r.toString(), ItemOutcome.FAILED, r.outcome)
        assertTrue(r.message, r.message!!.contains("looks damaged"))
    }
}
