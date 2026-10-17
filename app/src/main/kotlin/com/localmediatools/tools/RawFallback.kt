package com.localmediatools.tools

import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.SniffedFormat
import com.localmediatools.i18n.tr
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.ui.ToolRules

/**
 * RAW photos are files like any other. Where you choose the format (Convert images, the Image
 * compressor, the Lossless optimizer, the editor's save sheet, a PDF's pages, the background
 * remover's background), a RAW photo gets your choice like every other file.
 *
 * The tools here keep each file's format. A RAW photo stays RAW where the tool can write it back (the
 * Denoiser, for DNG, CR2, NEF and ARW). Where it can't, you're told before the tool starts, and the
 * RAW photo's result takes the type the other files take: a PNG among PNGs, a JPEG among JPEGs (a
 * JPEG when there is no other file to follow, or as many of each, as for any camera photo). Either
 * way at full quality: PNG is lossless, and the JPEG is written at quality 100 with full colour
 * resolution.
 */
object RawFallback {
    /** Tools that keep each file's format. */
    val keepsFormat = setOf(ToolId.AUTO_ENHANCE, ToolId.FACE_BLUR, ToolId.WATERMARK, ToolId.REMOVE_METADATA, ToolId.DENOISE)

    /** The JPEG quality of a RAW photo's result: the highest (every quantisation step 1, colour not reduced). */
    const val JPEG_QUALITY = 100

    /** RAW types the Denoiser writes back as themselves (some variants of them only show up when the file is read). */
    private val DENOISER_WRITES = setOf("DNG", "CR2", "NEF", "ARW")

    /** True when [tool] keeps the RAW photo [item] RAW. */
    fun staysRaw(tool: ToolId, item: MediaItem): Boolean =
        tool == ToolId.DENOISE && (item.format == SniffedFormat.DNG || ToolRules.rawLabel(item) in DENOISER_WRITES)

    /** The RAW photos among [items] that [tool] can't keep RAW (none for tools that don't keep formats). */
    fun converted(tool: ToolId, items: List<MediaItem>): List<MediaItem> =
        if (tool !in keepsFormat) emptyList() else items.filter { it.format.isRaw && !staysRaw(tool, it) }

    /** The format [tool] gives a file that isn't RAW; null for files it makes no picture of. */
    fun keptFormat(tool: ToolId, item: MediaItem): ImageOutFormat? {
        if (item.kind != MediaKind.IMAGE || item.format.isRaw) return null
        val f = item.format
        return when (tool) {
            ToolId.AUTO_ENHANCE, ToolId.FACE_BLUR -> if (f == SniffedFormat.PNG || f == SniffedFormat.WEBP) ImageOutFormat.PNG else ImageOutFormat.JPEG
            ToolId.WATERMARK -> when (f) {
                SniffedFormat.JPEG, SniffedFormat.HEIF, SniffedFormat.AVIF -> ImageOutFormat.JPEG
                SniffedFormat.WEBP -> ImageOutFormat.WEBP
                else -> ImageOutFormat.PNG
            }
            ToolId.REMOVE_METADATA -> when (f) {
                SniffedFormat.JPEG -> ImageOutFormat.JPEG
                SniffedFormat.PNG, SniffedFormat.GIF -> ImageOutFormat.PNG
                SniffedFormat.WEBP -> ImageOutFormat.WEBP
                else -> null
            }
            ToolId.DENOISE -> when (f) {
                SniffedFormat.PNG -> ImageOutFormat.PNG
                SniffedFormat.WEBP -> ImageOutFormat.WEBP
                else -> ImageOutFormat.JPEG
            }
            else -> null
        }
    }

    /** What the RAW photos among [items] become in [tool] when they can't stay RAW. */
    fun format(tool: ToolId, items: List<MediaItem>): ImageOutFormat {
        val others = items.mapNotNull { keptFormat(tool, it) }
        return if (others.count { it.lossless } > others.count { !it.lossless }) ImageOutFormat.PNG else ImageOutFormat.JPEG
    }

    /** How a RAW photo's result in [format] is encoded: at full quality. */
    fun spec(format: ImageOutFormat) = EncodeSpec(format, JPEG_QUALITY)

    /** The result's note for a RAW photo saved as [format]; [variant]: the kind of file it is, when only that kind can't be written ("Canon sRAW"). */
    fun note(format: ImageOutFormat, item: MediaItem, variant: String? = null): String = ToolRules.rawLabel(item).let { label ->
        val png = format == ImageOutFormat.PNG
        when {
            variant != null && png -> tr("Saved as a lossless PNG, because this tool can't write this kind of {0} file ({1}).", label, variant)
            variant != null -> tr("Saved as a JPEG at full quality, because this tool can't write this kind of {0} file ({1}).", label, variant)
            png -> tr("Saved as a lossless PNG, because this tool can't write {0} files.", label)
            else -> tr("Saved as a JPEG at full quality, because this tool can't write {0} files.", label)
        }
    }
}
