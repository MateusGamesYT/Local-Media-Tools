package com.localmediatools.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

enum class MediaKind { IMAGE, GIF, VIDEO, PDF, AUDIO, OTHER }

/** Container/codec family recognised from the file's leading bytes. */
enum class SniffedFormat(val kind: MediaKind, val label: String) {
    JPEG(MediaKind.IMAGE, "JPEG"), PNG(MediaKind.IMAGE, "PNG"), WEBP(MediaKind.IMAGE, "WebP"),
    GIF(MediaKind.GIF, "GIF"), BMP(MediaKind.IMAGE, "BMP"), HEIF(MediaKind.IMAGE, "HEIF"),
    AVIF(MediaKind.IMAGE, "AVIF"), TIFF(MediaKind.IMAGE, "TIFF"), DNG(MediaKind.IMAGE, "DNG"),
    ICO(MediaKind.IMAGE, "ICO"), PSD(MediaKind.IMAGE, "PSD"), QOI(MediaKind.IMAGE, "QOI"),
    PNM(MediaKind.IMAGE, "PNM"), TGA(MediaKind.IMAGE, "TGA"), WBMP(MediaKind.IMAGE, "WBMP"),
    RAW_CAMERA(MediaKind.IMAGE, "RAW"),
    MP4(MediaKind.VIDEO, "MP4"), MOV(MediaKind.VIDEO, "MOV"), THREE_GP(MediaKind.VIDEO, "3GP"),
    MATROSKA(MediaKind.VIDEO, "MKV/WebM"), AVI(MediaKind.VIDEO, "AVI"), MPEG_TS(MediaKind.VIDEO, "MPEG-TS"),
    PDF(MediaKind.PDF, "PDF"),
    AUDIO(MediaKind.AUDIO, "Audio"),
    UNKNOWN(MediaKind.OTHER, "Unknown");

    companion object {
        fun sniff(h: ByteArray, n: Int, name: String, mime: String?): SniffedFormat {
            fun at(o: Int, s: String) = n >= o + s.length && (s.indices).all { h[o + it] == s[it].code.toByte() }
            fun b(o: Int) = if (o < n) h[o].toInt() and 0xFF else -1
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when {
                b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> JPEG
                b(0) == 0x89 && at(1, "PNG") -> PNG
                at(0, "GIF87a") || at(0, "GIF89a") -> GIF
                at(0, "RIFF") && at(8, "WEBP") -> WEBP
                at(0, "RIFF") && at(8, "AVI ") -> AVI
                at(0, "%PDF") -> PDF
                at(0, "BM") && n >= 14 -> BMP
                at(0, "8BPS") -> PSD
                at(0, "qoif") -> QOI
                (at(0, "II") && b(2) == 42 && b(3) == 0) || (at(0, "MM") && b(2) == 0 && b(3) == 42) ->
                    when (ext) {
                        "dng" -> DNG
                        "cr2", "nef", "arw", "orf", "rw2", "pef", "srw", "nrw", "raf" -> RAW_CAMERA
                        else -> TIFF
                    }
                at(4, "ftyp") -> {
                    val brand = if (n >= 12) String(h, 8, 4, Charsets.ISO_8859_1) else ""
                    // Look at major and compatible brands.
                    val brands = StringBuilder(brand)
                    var o = 16
                    val boxLen = ((b(0) shl 24) or (b(1) shl 16) or (b(2) shl 8) or b(3)).coerceAtMost(n)
                    while (o + 4 <= boxLen) { brands.append(String(h, o, 4, Charsets.ISO_8859_1)); o += 4 }
                    val all = brands.toString()
                    when {
                        brand == "avif" || brand == "avis" -> AVIF
                        brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1") ->
                            if (all.contains("avif")) AVIF else HEIF
                        brand == "qt  " -> MOV
                        brand.startsWith("3g") -> THREE_GP
                        brand in setOf("M4A ", "M4B ", "M4P ", "F4A ") -> AUDIO
                        else -> MP4
                    }
                }
                b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> MATROSKA
                b(0) == 0x47 && n > 188 && b(188) == 0x47 -> MPEG_TS
                b(0) == 0 && b(1) == 0 && b(2) == 1 && b(3) == 0 && n > 6 -> ICO
                b(0) == 'P'.code && b(1) in '1'.code..'7'.code && (b(2) == 0x0A || b(2) == 0x20 || b(2) == 0x0D || b(2) == 0x09) -> PNM
                at(0, "ID3") || (b(0) == 0xFF && (b(1) and 0xE0) == 0xE0) || at(0, "fLaC") || at(0, "OggS") ||
                    (at(0, "RIFF") && at(8, "WAVE")) -> if (at(0, "OggS") && mime?.startsWith("video") == true) MATROSKA else AUDIO
                ext == "tga" || ext == "icb" || ext == "vda" -> TGA
                ext == "wbmp" -> WBMP
                ext == "heic" || ext == "heif" -> HEIF
                else -> UNKNOWN
            }
        }
    }
}

/** A user-selected file. Never modified: all tools write new outputs. */
data class MediaItem(
    val uri: Uri,
    val name: String,
    val mime: String?,
    val size: Long,
    val format: SniffedFormat,
    /** Set when the file can't be read at all. */
    val readError: String? = null,
) {
    val kind: MediaKind get() = format.kind
    val baseName: String get() = name.substringBeforeLast('.').ifBlank { "file" }.take(80)
    val key: String get() = uri.toString()
}

object MediaProbe {
    fun describe(ctx: Context, uri: Uri): MediaItem {
        val resolver = ctx.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // Some providers don't support queries; fall back to the URI.
        }
        val mime = try { resolver.getType(uri) } catch (e: Exception) { null }
        val head = ByteArray(512)
        var n = 0
        var error: String? = null
        try {
            openInput(resolver, uri).use { s ->
                while (n < head.size) {
                    val k = s.read(head, n, head.size - n)
                    if (k < 0) break
                    n += k
                }
            }
            if (n == 0) error = "The file is empty"
        } catch (e: SecurityException) {
            error = "Permission to read this file was not granted"
        } catch (e: Exception) {
            error = "The file can't be opened (${e.javaClass.simpleName})"
        }
        val format = if (error != null) SniffedFormat.UNKNOWN else SniffedFormat.sniff(head, n, name, mime)
        return MediaItem(uri, name, mime, size, format, error)
    }

    fun openInput(resolver: ContentResolver, uri: Uri) =
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("Provider returned no stream")
}

object Format {
    fun bytes(b: Long): String {
        if (b < 0) return "—"
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, if (mb < 10) "%.1f MB" else "%.0f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun seconds(sec: Double): String = when {
        sec >= 60 && sec % 60 == 0.0 -> "${(sec / 60).toInt()} min"
        sec >= 60 -> String.format(Locale.US, "%d min %d s", (sec / 60).toInt(), (sec % 60).toInt())
        sec == Math.floor(sec) -> "${sec.toInt()} s"
        else -> String.format(Locale.US, "%.1f s", sec)
    }

    fun percent(f: Double) = "${(f * 100).toInt()}%"

    /** Removes characters that are unsafe in file names and limits the length. */
    fun safeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(100).ifBlank { "output" }
}
