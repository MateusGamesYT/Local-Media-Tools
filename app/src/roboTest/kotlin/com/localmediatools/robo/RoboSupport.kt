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
    /** Face-scan stand-in: two people (a coloured disc each) seen in every given file. */
    fun fakeFaces(items: List<MediaItem>): com.localmediatools.vision.FaceScanResult {
        val thumbs = java.util.IdentityHashMap<com.localmediatools.vision.core.FaceTrack, Bitmap>()
        val tracks = ArrayList<com.localmediatools.vision.core.FaceTrack>()
        for ((k, item) in items.withIndex()) for (p in 0 until 2) {
            val t = com.localmediatools.vision.core.FaceTrack(p, k, still = item.kind != com.localmediatools.core.MediaKind.VIDEO)
            val f = FloatArray(128) { if (it % 2 == p) 1f else 0f }
            t.samples.add(com.localmediatools.vision.core.FaceSample(0, 0.2f + 0.4f * p, 0.3f, 0.2f, 0.25f, 0.9f, com.localmediatools.vision.core.FaceEngine.normalize(f)))
            if (!t.still) t.samples.add(com.localmediatools.vision.core.FaceSample(500_000, 0.2f + 0.4f * p, 0.3f, 0.2f, 0.25f, 0.9f, com.localmediatools.vision.core.FaceEngine.normalize(f.copyOf())))
            tracks.add(t)
            thumbs[t] = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF2A2F3A.toInt())
                android.graphics.Canvas(this).drawCircle(48f, 52f, 30f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = if (p == 0) 0xFFE0A47A.toInt() else 0xFF8D5A3B.toInt() })
            }
        }
        return com.localmediatools.vision.FaceScanResult(items, com.localmediatools.vision.core.FaceTracker.cluster(tracks), thumbs, emptyList())
    }

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

/**
 * A pretend photo library for gallery tests: MediaStore, the analyzer and the thumbnailer are
 * replaced, so indexing, people grouping, search and every gallery screen run for real on top.
 * The people are real (test resources /people, Creative Commons, credits in CREDITS.tsv), with the
 * faces and embeddings the app's face pipeline found in those photos: Caroline Wozniacki (A) and
 * Ian Somerhalder (B) in several photos (side by side in some), Kelly Clarkson (C) in three, and a
 * small, far-away face of Caroline in profile (SFace left it on its own; MobileFaceNet recognises it).
 */
object FakeGallery {
    const val DAY = 86_400_000L
    val now = System.currentTimeMillis()
    /** Which identity each stored face belongs to: 0 Caroline, 1 Ian, 2 Kelly. */
    val faceOwners = HashMap<Long, Int>()

    /** A face from the fixture: its photo, box (fractions of the photo) and what the pipeline found. */
    class RealFace(val file: String, val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val eye: Float, val yaw: Float, val emb: FloatArray)

    /** Fixture faces by role (A1..A10, A-small, B1..B12, C1..C3). */
    val roles: Map<String, RealFace> by lazy {
        val out = HashMap<String, RealFace>()
        val text = FakeGallery::class.java.getResourceAsStream("/people/faces.tsv")!!.readBytes().toString(Charsets.UTF_8)
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val r = line.split('\t')
            val fb = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(r[12])).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val face = RealFace(r[0], r[2].toFloat(), r[3].toFloat(), r[4].toFloat(), r[5].toFloat(), r[6].toFloat(), r[7].toFloat(), r[8].toFloat(), FloatArray(fb.remaining()) { fb.get(it) })
            for (role in r[11].split(',')) if (role.isNotEmpty() && role != "jvm") out[role] = face
        }
        out
    }

    /** Who is in each item, left to right. */
    fun people(id: Long): List<String> = when (id) {
        in 1L..2L -> listOf("A$id")
        in 3L..10L -> listOf("A$id", "B${id - 2}")
        in 11L..14L -> listOf("B${id - 2}")
        in 20L..22L -> listOf("C${id - 19}")
        30L -> listOf("A-small")
        else -> emptyList()
    }

    fun media(): List<com.localmediatools.gallery.GMedia> = (1L..36L).map { id ->
        val video = id == 7L || id == 21L
        val shot = id in 33L..35L
        val wa = id in 26L..30L
        com.localmediatools.gallery.GMedia(
            id = id, video = video, mime = if (video) "video/mp4" else "image/jpeg",
            name = if (shot) "Screenshot_2026_$id.png" else if (video) "VID_$id.mp4" else "IMG_$id.jpg",
            bucketId = if (shot) "s" else if (wa) "w" else "c", bucket = if (shot) "Screenshots" else if (wa) "WhatsApp Images" else "Camera",
            path = if (shot) "Pictures/Screenshots/" else if (wa) "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/" else "DCIM/Camera/",
            taken = now - ((id - 1) / 4) * DAY * 3 - ((id - 1) % 4) * 3_600_000L - 600_000L, modified = 1000 + id, size = 2_000_000, width = 4000, height = 3000,
            orientation = 0, durationMs = if (video) 12_000 else 0, favorite = id == 2L,
        )
    }

    fun tags(id: Long): Map<String, Float> {
        val t = HashMap<String, Float>()
        if (id in setOf(2L, 6L, 12L, 18L)) t["dog"] = 0.82f
        if (id in setOf(6L, 7L, 8L, 25L)) { t["beach"] = 0.74f; t["sea"] = 0.66f }
        if (id in setOf(15L, 16L)) { t["food"] = 0.9f; t["pizza"] = 0.8f }
        if (id == 19L) t["car"] = 0.62f
        if (id in setOf(8L, 9L)) t["sunset"] = 0.7f
        if (id == 34L) t["document"] = 0.6f
        return t
    }

    private val photos = HashMap<String, Bitmap>()
    private fun photo(file: String): Bitmap = synchronized(photos) {
        photos.getOrPut(file) { FakeGallery::class.java.getResourceAsStream("/people/$file")!!.use { BitmapFactory.decodeStream(it)!! } }
    }

    /**
     * Where each person's photo sits in an item's 4:3 picture (fractions): one photo fills it, two
     * stand side by side, each scaled to fit its half and centred.
     */
    private fun layout(id: Long): List<Pair<RealFace, android.graphics.RectF>> {
        val who = people(id).map { roles.getValue(it) }
        return who.mapIndexed { k, f ->
            val b = photo(f.file)
            val cellW = 4f / who.size
            val s = minOf(cellW / b.width, 3f / b.height)
            val w = b.width * s; val h = b.height * s
            val left = k * cellW + (cellW - w) / 2; val top = (3f - h) / 2
            f to android.graphics.RectF(left / 4f, top / 3f, (left + w) / 4f, (top + h) / 3f)
        }
    }

    fun analysis(m: com.localmediatools.gallery.GMedia): com.localmediatools.gallery.Analysis {
        val faces = layout(m.id).map { (f, r) ->
            com.localmediatools.gallery.FoundFace(r.left + f.x * r.width(), r.top + f.y * r.height(), f.w * r.width(), f.h * r.height(), f.score, f.eye, f.yaw, f.emb)
        }
        val t = tags(m.id).toMutableMap()
        if (faces.isNotEmpty()) t["people"] = 0.92f
        return com.localmediatools.gallery.Analysis(t, faces)
    }

    /** Pictures: the real photos for items with people (a crop around the face when asked for a face), a drawn scene per tag otherwise. */
    fun picture(m: com.localmediatools.gallery.GMedia?, f: com.localmediatools.gallery.GFace?, size: Int): Bitmap {
        val s = size.coerceIn(64, 512)
        if (f != null) {
            // The face with some room around it, cut from the photo it came from.
            val cx = f.x + f.w / 2; val cy = f.y + f.h / 2
            val placed = layout(f.mediaId)
            val (rf, r) = placed.firstOrNull { it.second.contains(cx, cy) } ?: placed.firstOrNull() ?: return scene(f.mediaId, null, s)
            val b = photo(rf.file)
            val px = (cx - r.left) / r.width() * b.width; val py = (cy - r.top) / r.height() * b.height
            val side = maxOf(f.w / r.width() * b.width, f.h / r.height() * b.height) * 1.8f
            val sq = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(sq)
            c.drawColor(0xFF15171C.toInt())
            c.drawBitmap(b, android.graphics.Rect((px - side / 2).toInt(), (py - side / 2).toInt(), (px + side / 2).toInt(), (py + side / 2).toInt()),
                android.graphics.Rect(0, 0, s, s), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return sq
        }
        return scene(m?.id ?: 0, m, s)
    }

    private fun scene(id: Long, m: com.localmediatools.gallery.GMedia?, s: Int): Bitmap {
        val b = Bitmap.createBitmap(s, s * 3 / 4, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val w = b.width.toFloat(); val h = b.height.toFloat()
        val placed = layout(id)
        if (placed.isNotEmpty()) {
            c.drawColor(0xFF15171C.toInt())
            for ((f, r) in placed) c.drawBitmap(photo(f.file), null, android.graphics.RectF(r.left * w, r.top * h, r.right * w, r.bottom * h), p)
            return b
        }
        val t = tags(id)
        when {
            "beach" in t -> {
                p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF7EC8F2.toInt(), 0xFF2E86C1.toInt(), 0xFFE9D7A6.toInt()), floatArrayOf(0f, 0.55f, 0.7f), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null
            }
            "food" in t -> { c.drawColor(0xFF5D4037.toInt()); p.color = 0xFFFAFAFA.toInt(); c.drawCircle(w / 2, h / 2, h * 0.4f, p); p.color = 0xFFE57373.toInt(); c.drawCircle(w / 2, h / 2, h * 0.3f, p) }
            "document" in t || (m?.screenshot == true) -> { c.drawColor(0xFFF5F5F5.toInt()); p.color = 0xFF9E9E9E.toInt(); for (k in 1..7) c.drawRect(w * 0.1f, h * k / 9f, w * (0.5f + 0.4f * ((k * 37) % 10) / 10f), h * k / 9f + h / 30f, p) }
            "sunset" in t -> {
                p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF3F2B63.toInt(), 0xFFF28C38.toInt(), 0xFF6D3B2E.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null; p.color = 0xFFFFD27F.toInt(); c.drawCircle(w * 0.5f, h * 0.62f, h * 0.12f, p)
            }
            else -> {
                val hue = (id * 47 % 360).toFloat()
                p.shader = android.graphics.LinearGradient(0f, 0f, w, h, android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.75f)),
                    android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.55f, 0.45f)), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p); p.shader = null
            }
        }
        if ("dog" in t) { p.color = 0xFF8D6E63.toInt(); c.drawOval(w * 0.35f, h * 0.45f, w * 0.75f, h * 0.85f, p); c.drawCircle(w * 0.75f, h * 0.45f, h * 0.13f, p) }
        if ("car" in t) { p.color = 0xFFD32F2F.toInt(); c.drawRoundRect(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.75f, 20f, 20f, p); p.color = 0xFF212121.toInt(); c.drawCircle(w * 0.33f, h * 0.77f, h * 0.08f, p); c.drawCircle(w * 0.67f, h * 0.77f, h * 0.08f, p) }
        return b
    }

    fun install(app: Context) {
        com.localmediatools.gallery.GalleryDb.closeForTests()
        app.deleteDatabase("gallery.db")
        com.localmediatools.gallery.GalleryIndex.resetForTests()
        app.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().apply()
        org.robolectric.Shadows.shadowOf(app as android.app.Application).grantPermissions(
            android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        com.localmediatools.gallery.GalleryLibrary.source = { media() }
        com.localmediatools.gallery.GalleryIndex.analyzerOverride = { m -> analysis(m) }
        com.localmediatools.ui.gallery.GalleryThumbs.override = { m, f, size -> picture(m, f, size) }
    }

    /** After indexing: remember who each stored face is (the fixture embedding it was stored with). */
    fun learnOwners(app: Context) {
        faceOwners.clear()
        val db = com.localmediatools.gallery.GalleryDb.get(app)
        for (f in db.faces("1", withEmb = true)) {
            val e = f.emb ?: continue
            val role = roles.entries.firstOrNull { it.value.emb.contentEquals(e) }?.key ?: continue
            faceOwners[f.id] = "ABC".indexOf(role[0])
        }
    }

    fun uninstall() {
        com.localmediatools.gallery.GalleryLibrary.source = null
        com.localmediatools.gallery.GalleryIndex.analyzerOverride = null
        com.localmediatools.ui.gallery.GalleryThumbs.override = null
        com.localmediatools.gallery.GalleryIndex.resetForTests()
        com.localmediatools.gallery.GalleryDb.closeForTests()
    }
}
