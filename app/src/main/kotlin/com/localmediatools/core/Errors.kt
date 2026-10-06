package com.localmediatools.core

import android.media.MediaCodec
import java.io.FileNotFoundException
import java.io.IOException

/** An error whose message is already written for the user. */
open class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when an item is intentionally not processed (e.g. "already smaller"). */
class SkipItemException(message: String) : Exception(message)

class ExportCancelledException : Exception("Export cancelled")

object Errors {
    /** Converts any failure into a short, specific explanation. */
    fun describe(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            t is UserFacingException -> msg
            t is SkipItemException -> msg
            t is OutOfMemoryError -> "Not enough free memory (RAM) to process this file. Close other apps, lower the export workload, or choose a smaller output size."
            isNoSpace(t) -> "Not enough free storage space to save the result."
            t is SecurityException -> "Android no longer allows reading this file. Please select it again."
            t is FileNotFoundException -> "The file could not be opened. It may have been moved, deleted or is stored in the cloud only."
            t is MediaCodec.CodecException -> "This device's video codec failed while processing the file" +
                (if (t.isRecoverable || t.isTransient) " (temporary codec problem — try again)." else ".")
            t is com.localmediatools.codec.gif.GifFormatException -> msg
            t is com.localmediatools.stitch.core.StitchException -> msg
            t is com.localmediatools.codec.image.SoftDecodeException -> msg
            t is com.localmediatools.codec.gif.GifUnrepresentableException -> msg
            t is com.localmediatools.codec.mp4.Mp4FormatException -> "The video file is damaged or incomplete: $msg"
            t is IllegalStateException && msg.contains("Failed to add the track", true) ->
                "This file's video or audio codec can't be stored in the output container on this device."
            t is IOException -> "Read/write error: ${msg.ifBlank { t.javaClass.simpleName }}"
            else -> "${t.javaClass.simpleName}${if (msg.isNotBlank()) ": $msg" else ""}"
        }
    }

    fun isNoSpace(t: Throwable?): Boolean {
        var e = t
        while (e != null) {
            val m = e.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", true)) return true
            e = e.cause
        }
        return false
    }
}
