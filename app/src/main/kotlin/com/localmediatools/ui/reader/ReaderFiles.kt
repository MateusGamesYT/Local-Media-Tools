package com.localmediatools.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.localmediatools.core.Format
import com.localmediatools.core.MediaProbe
import com.localmediatools.docs.core.DocFormat
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

/**
 * A file to show: where it came from ([uri], or a copy the reader keeps in [kept]) and its name.
 * The readers need a seekable file, so content is first copied into the app's cache.
 */
data class ReaderSource(val uri: Uri?, val name: String, val kept: File? = null)

/** A file opened before, for the list on the reader's first screen. */
data class RecentFile(val key: String, val name: String, val size: Long, val format: String?, val openedAt: Long, val uri: String?, val kept: String?) {
    val source: ReaderSource get() = ReaderSource(uri?.let(Uri::parse), name, kept?.let(::File))
}

/**
 * Recently opened files. A file picked in the reader keeps the system's permission to read it
 * again; a file another app sent ("Open with") can't be read again later, so a copy of it is kept
 * in the app's private storage (at most [MAX_KEPT] files and [MAX_KEPT_BYTES]), removed with its entry.
 */
object RecentFiles {
    private const val PREFS = "reader"
    private const val KEY = "recent"
    const val MAX = 30
    private const val MAX_KEPT = 12
    private const val MAX_KEPT_BYTES = 300L shl 20

    fun keptDir(ctx: Context) = File(ctx.filesDir, "reader-kept").apply { mkdirs() }

    fun list(ctx: Context): List<RecentFile> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                RecentFile(o.optString("key"), o.optString("name"), o.optLong("size", -1), o.optString("format").ifEmpty { null },
                    o.optLong("at"), o.optString("uri").ifEmpty { null }, o.optString("kept").ifEmpty { null })
            }.filter { it.uri != null || it.kept?.let { k -> File(k).exists() } == true }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, items: List<RecentFile>) {
        val a = JSONArray()
        for (r in items.take(MAX)) a.put(JSONObject().put("key", r.key).put("name", r.name).put("size", r.size).put("format", r.format ?: "")
            .put("at", r.openedAt).put("uri", r.uri ?: "").put("kept", r.kept ?: ""))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        // Kept copies no entry points to any more.
        val used = items.take(MAX).mapNotNull { it.kept }.toSet()
        keptDir(ctx).listFiles()?.forEach { if (it.absolutePath !in used) it.delete() }
    }

    /**
     * Records that [source] was opened (as [copy], a file in the cache) and returns the entry.
     * [persistent]: the app may read [ReaderSource.uri] again later.
     */
    fun opened(ctx: Context, source: ReaderSource, copy: File, format: DocFormat?, persistent: Boolean): RecentFile {
        val key = source.uri?.toString() ?: source.kept?.absolutePath ?: copy.absolutePath
        val old = list(ctx)
        val previous = old.firstOrNull { it.key == key || (source.kept != null && it.kept == source.kept.absolutePath) }
        var kept: String? = previous?.kept ?: source.kept?.absolutePath
        if (!persistent && kept == null && copy.length() <= MAX_KEPT_BYTES / 4) {
            val f = File(keptDir(ctx), "${System.currentTimeMillis()}-${Format.safeFileName(source.name)}")
            try { copy.copyTo(f, overwrite = true); kept = f.absolutePath } catch (_: Exception) { f.delete() }
        }
        val entry = RecentFile(key, source.name, copy.length(), format?.name, System.currentTimeMillis(), if (persistent) source.uri?.toString() else null, kept)
        if (entry.uri == null && entry.kept == null) return entry
        var items = listOf(entry) + old.filter { it.key != key && it != previous }
        // Keep the newest copies within the limits.
        var count = 0; var bytes = 0L
        items = items.filter { r ->
            val k = r.kept ?: return@filter true
            val size = File(k).length()
            if (r.uri == null && (count >= MAX_KEPT || bytes + size > MAX_KEPT_BYTES)) return@filter false
            count++; bytes += size; true
        }
        save(ctx, items)
        return entry
    }

    fun remove(ctx: Context, r: RecentFile) = save(ctx, list(ctx).filter { it.key != r.key })

    fun clear(ctx: Context) = save(ctx, emptyList())

    /** Asks to keep reading [uri] after this screen (granted for files picked with the system's file picker). */
    fun persist(ctx: Context, uri: Uri): Boolean = uri.scheme == "content" && try {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
    } catch (_: Exception) { false }
}

object ReaderFiles {
    fun openDir(ctx: Context) = File(ctx.cacheDir, "reader").apply { mkdirs() }

    /** Removes copies left by screens that ended without closing (the app was stopped). */
    fun cleanUp(ctx: Context) {
        openDir(ctx).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 6 * 3_600_000L) it.deleteRecursively() }
    }

    /** A private copy of [source] the readers can seek in; [progress] gets bytes copied so far. */
    fun copy(ctx: Context, source: ReaderSource, progress: (Long) -> Unit): File {
        source.kept?.let { if (it.exists()) return it }
        val uri = source.uri ?: throw java.io.FileNotFoundException()
        if (uri.scheme == "file") uri.path?.let { p -> File(p).takeIf { it.isFile && it.canRead() }?.let { return it } }
        val dir = openDir(ctx)
        val f = File(dir, "${System.nanoTime()}-${Format.safeFileName(source.name)}")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException()
            input.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L; var last = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n
                        if (total - last > (1 shl 20)) { last = total; progress(total) }
                        if (Thread.interrupted()) throw InterruptedException()
                    }
                }
            }
        } catch (e: Throwable) { f.delete(); throw e }
        return f
    }

    /** The name and size other apps give for [uri]. */
    fun describe(ctx: Context, uri: Uri): Pair<String, Long> = MediaProbe.describe(ctx, uri).let { it.name to it.size }

    /** True for files the reader made (its cache), which are deleted when the screen closes. */
    fun isTemporary(ctx: Context, f: File) = f.absolutePath.startsWith(openDir(ctx).absolutePath)
}

/**
 * Runs [block] on a thread of its own with a large stack: documents nest (tables in tables, groups
 * in groups) and the readers recurse, so a deep but valid file must not overflow a small stack.
 */
suspend fun <T> onReaderThread(block: () -> T): T = suspendCancellableCoroutine { c ->
    val t = Thread(null, {
        val r = try { Result.success(block()) } catch (e: StackOverflowError) {
            Result.failure(com.localmediatools.docs.core.UnreadableException(tr("This file is built too deeply to be shown.")))
        } catch (e: OutOfMemoryError) {
            Result.failure(com.localmediatools.docs.core.UnreadableException(tr("This file is too large to show with the memory this phone has free.")))
        } catch (e: Throwable) { Result.failure(e) }
        r.fold({ c.resume(it) }, { c.resumeWithException(it) })
    }, "reader", 64L shl 20)
    c.invokeOnCancellation { t.interrupt() }
    t.start()
}

/** How a format is named and drawn in lists. */
object FormatLook {
    fun label(f: DocFormat?): String = when (f) {
        null -> tr("Document")
        DocFormat.DOC -> tr("Word 97–2003")
        DocFormat.XLS -> tr("Excel 97–2003")
        DocFormat.PPT -> tr("PowerPoint 97–2003")
        DocFormat.ODT -> tr("OpenDocument text")
        DocFormat.ODS -> tr("OpenDocument spreadsheet")
        DocFormat.ODP -> tr("OpenDocument presentation")
        DocFormat.TEXT -> trc("kind of file", "Text")
        else -> f.label
    }

    fun color(f: DocFormat?): Int = when (f) {
        DocFormat.PDF -> 0xFFEF5350.toInt()
        DocFormat.DOCX, DocFormat.DOC, DocFormat.ODT, DocFormat.RTF -> 0xFF4F8DF7.toInt()
        DocFormat.XLSX, DocFormat.XLS, DocFormat.ODS, DocFormat.CSV, DocFormat.TSV -> 0xFF34B37A.toInt()
        DocFormat.PPTX, DocFormat.PPT, DocFormat.ODP -> 0xFFF08A3C.toInt()
        DocFormat.EPUB -> 0xFFB15CFF.toInt()
        else -> 0xFF9AA3B5.toInt()
    }

    /** Short tag drawn on the file icon. */
    fun tag(f: DocFormat?, name: String): String = when (f) {
        null -> name.substringAfterLast('.', "").take(4).uppercase()
        DocFormat.MARKDOWN -> "MD"
        DocFormat.TEXT -> name.substringAfterLast('.', "TXT").take(4).uppercase()
        else -> f.name.take(4)
    }

    fun parse(s: String?): DocFormat? = s?.let { n -> DocFormat.entries.firstOrNull { it.name == n } }
}
