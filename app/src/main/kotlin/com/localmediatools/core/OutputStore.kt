package com.localmediatools.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.OutputStream

/** Where each tool's results are saved. All locations live under a "LocalMediaTools" folder. */
enum class OutputArea(val collection: Collection, val relativePath: String) {
    SPLITS(Collection.VIDEO, "Movies/LocalMediaTools/Splits"),
    OPTIMIZED_VIDEO(Collection.VIDEO, "Movies/LocalMediaTools/Optimized"),
    COMPRESSED_VIDEO(Collection.VIDEO, "Movies/LocalMediaTools/Compressed"),
    NO_AUDIO(Collection.VIDEO, "Movies/LocalMediaTools/NoAudio"),
    TRIMMED(Collection.VIDEO, "Movies/LocalMediaTools/Trimmed"),
    CLEAN_VIDEO(Collection.VIDEO, "Movies/LocalMediaTools/Clean"),
    MERGED_VIDEO(Collection.VIDEO, "Movies/LocalMediaTools/Merged"),
    SPEED_VIDEO(Collection.VIDEO, "Movies/LocalMediaTools/Speed"),
    BLURRED_VIDEO(Collection.VIDEO, "Movies/LocalMediaTools/Blurred faces"),
    EDITED(Collection.IMAGES, "Pictures/LocalMediaTools/Edited"),
    CUTOUTS(Collection.IMAGES, "Pictures/LocalMediaTools/Cutouts"),
    ENHANCED(Collection.IMAGES, "Pictures/LocalMediaTools/Enhanced"),
    BLURRED_IMAGES(Collection.IMAGES, "Pictures/LocalMediaTools/Blurred faces"),
    CLEAN_IMAGES(Collection.IMAGES, "Pictures/LocalMediaTools/Clean"),
    COMPRESSED_IMAGES(Collection.IMAGES, "Pictures/LocalMediaTools/Compressed"),
    MERGED(Collection.IMAGES, "Pictures/LocalMediaTools/Merged"),
    STITCHED(Collection.IMAGES, "Pictures/LocalMediaTools/Stitched"),
    WATERMARKED(Collection.IMAGES, "Pictures/LocalMediaTools/Watermarked"),
    CONVERTED(Collection.IMAGES, "Pictures/LocalMediaTools/Converted"),
    CONVERTED_GIF(Collection.IMAGES, "Pictures/LocalMediaTools/Converted/GIF"),
    GIFS(Collection.IMAGES, "Pictures/LocalMediaTools/GIFs"),
    PDF_IMAGES(Collection.IMAGES, "Pictures/LocalMediaTools/PDF Images"),
    PDF(Collection.DOCUMENTS, "Documents/LocalMediaTools/PDF"),
    EXTRACTED_AUDIO(Collection.AUDIO, "Music/LocalMediaTools/Extracted Audio");

    enum class Collection { IMAGES, VIDEO, AUDIO, DOCUMENTS }

    /** Human-readable path shown in the UI. */
    val displayPath: String
        get() = if (collection == Collection.DOCUMENTS && Build.VERSION.SDK_INT < 30)
            "Download/LocalMediaTools/PDF" else relativePath
}

data class OutputFile(val uri: Uri, val displayName: String, val mime: String, val size: Long, val area: OutputArea)

/** An output being written. Invisible to other apps until [commit]; [abort] removes it. */
abstract class PendingOutput {
    abstract val uri: Uri
    abstract val requestedName: String
    abstract val mime: String
    abstract val area: OutputArea
    abstract fun openStream(): OutputStream
    abstract fun openFd(mode: String = "rw"): ParcelFileDescriptor
    abstract fun commit(): OutputFile
    abstract fun abort()
}

/**
 * A MediaStore entry that stays hidden (IS_PENDING) until [commit]. If processing fails the entry is
 * deleted with [abort], so a failed export never leaves a broken or partial file behind.
 */
class MediaStorePendingOutput internal constructor(
    private val ctx: Context,
    override val uri: Uri,
    override val requestedName: String,
    override val mime: String,
    override val area: OutputArea,
) : PendingOutput() {
    private var done = false

    override fun openStream(): OutputStream = ctx.contentResolver.openOutputStream(uri, "w")
        ?: throw java.io.IOException("Could not open the output file for writing")

    override fun openFd(mode: String): ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, mode)
        ?: throw java.io.IOException("Could not open the output file for writing")

    override fun commit(): OutputFile {
        check(!done)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null)
        done = true
        var name = requestedName
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        return OutputFile(uri, name, mime, size, area)
    }

    override fun abort() {
        if (done) return
        done = true
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
    }
}

object OutputStore {
    /** Replaces MediaStore (used by automated tests to write into a temporary folder). */
    @Volatile var factory: ((Context, OutputArea, String, String) -> PendingOutput)? = null

    fun create(ctx: Context, area: OutputArea, displayName: String, mime: String): PendingOutput {
        factory?.let { return it(ctx, area, Format.safeFileName(displayName), mime) }
        val resolver = ctx.contentResolver
        val name = Format.safeFileName(displayName)
        fun values(path: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$path/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val uri: Uri? = when (area.collection) {
            OutputArea.Collection.IMAGES -> resolver.insert(MediaStore.Images.Media.getContentUri(vol), values(area.relativePath))
            OutputArea.Collection.VIDEO -> resolver.insert(MediaStore.Video.Media.getContentUri(vol), values(area.relativePath))
            OutputArea.Collection.AUDIO -> resolver.insert(MediaStore.Audio.Media.getContentUri(vol), values(area.relativePath))
            OutputArea.Collection.DOCUMENTS -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        resolver.insert(MediaStore.Files.getContentUri(vol), values(area.relativePath))
                    } catch (e: IllegalArgumentException) {
                        resolver.insert(MediaStore.Downloads.getContentUri(vol), values("Download/LocalMediaTools/PDF"))
                    }
                } else {
                    resolver.insert(MediaStore.Downloads.getContentUri(vol), values("Download/LocalMediaTools/PDF"))
                }
            }
        }
        uri ?: throw java.io.IOException("The system media store refused to create \"$name\"")
        return MediaStorePendingOutput(ctx, uri, name, mime, area)
    }

    /** Free bytes on shared storage, or -1 if unknown. */
    fun freeBytes(): Long = try {
        @Suppress("DEPRECATION")
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    } catch (e: Exception) { -1 }

    /** Throws a clear error when an output of roughly [estimate] bytes clearly won't fit. */
    fun ensureSpace(estimate: Long) {
        val free = freeBytes()
        if (free >= 0 && estimate > 0 && free < estimate + 64L * 1024 * 1024) {
            throw UserFacingException("Not enough free storage: about ${Format.bytes(estimate)} is needed but only ${Format.bytes(free)} is free.")
        }
    }

    /** Removes hidden entries left behind if the app was killed mid-export. */
    fun cleanupStalePending(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(vol), MediaStore.Video.Media.getContentUri(vol),
            MediaStore.Audio.Media.getContentUri(vol), MediaStore.Files.getContentUri(vol)
        )
        for (c in collections) {
            try {
                val args = android.os.Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ctx.packageName))
                }
                val ids = ArrayList<Long>()
                ctx.contentResolver.query(c, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { cur ->
                    while (cur.moveToNext()) ids.add(cur.getLong(0))
                }
                for (id in ids) ctx.contentResolver.delete(android.content.ContentUris.withAppendedId(c, id), null, null)
            } catch (_: Exception) {
            }
        }
    }
}
