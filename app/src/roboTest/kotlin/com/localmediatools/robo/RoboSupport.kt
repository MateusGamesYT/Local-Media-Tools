package com.localmediatools.robo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.OutputArea
import com.localmediatools.core.OutputFile
import com.localmediatools.core.OutputStore
import com.localmediatools.core.PendingOutput
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Writes outputs into a temporary folder instead of MediaStore, keeping the pending/commit contract. */
class FileOutputs(val dir: File) {
    val committed = ArrayList<File>()
    val aborted = ArrayList<File>()

    inner class FilePending(private val file: File, override val requestedName: String, override val mime: String, override val area: OutputArea) : PendingOutput() {
        override val uri: Uri = Uri.fromFile(file)
        override fun openStream(): OutputStream = FileOutputStream(file)
        override fun openFd(mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        override fun commit(): OutputFile {
            committed.add(file)
            return OutputFile(uri, file.name, mime, file.length(), area)
        }
        override fun abort() {
            aborted.add(file)
            file.delete()
        }
    }

    fun install() {
        dir.mkdirs()
        // Robolectric reports 0 free bytes by default; pretend there is plenty of shared storage.
        @Suppress("DEPRECATION")
        org.robolectric.shadows.ShadowStatFs.registerStats(android.os.Environment.getExternalStorageDirectory(), 1_000_000, 1_000_000, 1_000_000)
        OutputStore.factory = { _, area, name, mime ->
            var f = File(dir, name)
            var k = 1
            while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + " ($k)." + name.substringAfterLast('.')); k++ }
            f.createNewFile()
            FilePending(f, name, mime, area)
        }
    }

    fun uninstall() { OutputStore.factory = null }
}

object Robo {
    /** App-wide UI state lives in singletons that survive between tests in one Robolectric sandbox. */
    fun resetUiState() {
        for (t in com.localmediatools.tools.ToolId.entries) com.localmediatools.ui.Selection.of(t).clear()
        com.localmediatools.ui.tools.WatermarkState.text = ""
        com.localmediatools.ui.tools.WatermarkState.logo = null
        com.localmediatools.ui.tools.WatermarkState.perGroup.clear()
    }

    /** Runs [job] to completion; [budget] is the memory budget each parallel item gets. */
    fun runJob(app: Context, job: ExportJob, budget: Long = 64L shl 20): List<ItemResult> {
        val ctx = JobContext(app, job, object : JobContext.Listener {
            override fun onProgress(ctx: JobContext) {}
            override fun onResult(ctx: JobContext, result: ItemResult) {}
        })
        ctx.budgetOverride = budget * com.localmediatools.core.Workload.profile().parallelism.coerceAtLeast(1)
        runBlocking { job.run(ctx) }
        return ctx.results.toList()
    }

    fun write(dir: File, name: String, bytes: ByteArray): File = File(dir, name).also { dir.mkdirs(); it.writeBytes(bytes) }

    fun item(app: Context, f: File): MediaItem = MediaProbe.describe(app, Uri.fromFile(f))

    fun encode(bmp: Bitmap, fmt: Bitmap.CompressFormat, q: Int = 100): ByteArray =
        ByteArrayOutputStream().also { assertTrue(bmp.compress(fmt, q, it)) }.toByteArray()

    fun decode(f: File): Bitmap = BitmapFactory.decodeFile(f.absolutePath) ?: throw AssertionError("output does not decode: $f")

    /** Inserts an EXIF APP1 segment carrying [orientation] right after the JPEG SOI marker. */
    fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0, 34,
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
            0, 0, 0, 0,
        )
        assertEquals(36, app1.size)
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    /** Colour distance (max channel difference). */
    fun diff(a: Int, b: Int): Int = maxOf(
        Math.abs(((a shr 16) and 255) - ((b shr 16) and 255)),
        Math.abs(((a shr 8) and 255) - ((b shr 8) and 255)),
        Math.abs((a and 255) - (b and 255)))
}
