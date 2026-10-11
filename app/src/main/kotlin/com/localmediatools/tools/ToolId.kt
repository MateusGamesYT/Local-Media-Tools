package com.localmediatools.tools

import com.localmediatools.core.OutputArea
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr

enum class ToolSection(private val titleEn: String, private val subtitleEn: String) {
    STACKS(N_("Stacks"), N_("Run several tools in a row")),
    EDIT(N_("Edit & AI"), N_("Retouch, cut out and anonymise with on-device AI")),
    IMAGES(N_("Images"), N_("Compress, convert, combine and brand photos")),
    VIDEO(N_("Video"), N_("Trim, cut, repackage and shrink videos")),
    GIF(N_("GIF"), N_("Create and slim down animations")),
    DOCUMENTS(N_("PDF"), N_("Pages, scans and merges")),
    PRINT(N_("Print"), N_("Photos and documents on your Wi-Fi printer")),
    PRIVACY(N_("Privacy & audio"), N_("Clean metadata and extract sound"));

    val title: String get() = tr(titleEn)
    val subtitle: String get() = tr(subtitleEn)
}

enum class ToolId(
    val section: ToolSection,
    /** In English (shown with [title]). */
    val englishTitle: String,
    private val shortEn: String,
    private val longEn: String,
    val area: OutputArea,
    /** Tools that save images and videos to different places. */
    val videoArea: OutputArea? = null,
) {
    TOOL_STACK(ToolSection.STACKS, N_("Tool stack"), N_("Chain tools: each works on the last one's results"),
        N_("Pick files once, add the tools you want in order — for example Blur faces → Video compressor → Video → GIF → GIF optimizer — and run them all with one tap. Each tool keeps all its settings; only the final results are saved."),
        OutputArea.EDITED),
    PHOTO_EDITOR(ToolSection.EDIT, N_("Photo editor"), N_("Crop, straighten, light, colour and looks"),
        N_("Crop and straighten, fine-tune light and colour, apply looks, then save a full-resolution copy. Your original stays untouched."),
        OutputArea.EDITED),
    MAGIC_ERASER(ToolSection.EDIT, N_("Magic eraser"), N_("Brush away people and objects with AI"),
        N_("Brush over what you don't want and an AI model running on this phone fills the gap with matching background. Nothing is uploaded."),
        OutputArea.EDITED),
    BLUR_REDACT(ToolSection.EDIT, N_("Blur & pixelate"), N_("Hide faces, plates and private details"),
        N_("Paint over faces, number plates, addresses or screens to blur or pixelate them before you share a photo."),
        OutputArea.EDITED),
    BACKGROUND_REMOVER(ToolSection.EDIT, N_("Background remover"), N_("Cut out the subject as a transparent PNG"),
        N_("An AI model on this phone finds the main subject (people, pets, products, objects) and removes everything behind it. Save as a transparent PNG or on a plain colour."),
        OutputArea.CUTOUTS),
    AUTO_ENHANCE(ToolSection.EDIT, N_("Auto enhance"), N_("One tap: light, colour and detail by AI"),
        N_("On-device AI recognises faces and the kind of scene (food, landscape, night, documents…) and fixes exposure, contrast, colour casts and sharpness to suit. Works on a whole batch."),
        OutputArea.ENHANCED),
    FACE_BLUR(ToolSection.EDIT, N_("Blur faces"), N_("Find faces in photos and videos, pick who to hide"),
        N_("On-device AI finds every face in your photos and videos and groups them by person. Choose who to blur or pixelate; faces are followed through the whole video."),
        OutputArea.BLURRED_IMAGES, OutputArea.BLURRED_VIDEO),

    HIGHLIGHT_VIDEO(ToolSection.VIDEO, N_("Highlight video"), N_("Your best moments, cut to music, automatically"),
        N_("Pick the photos and videos of a day out, a trip or a party. On-device AI puts them in the order they were taken, finds the moments (setting off, lunch, the beach…), picks the best shots and the liveliest part of each video, and edits them into a short film on the beat of background music it writes for it — always instrumental, and quieter while your videos speak. Review the moments, change anything, then save. Nothing is uploaded."),
        OutputArea.HIGHLIGHTS),
    SPLIT_VIDEO(ToolSection.VIDEO, N_("Split videos"), N_("Cut into equal-length parts without re-encoding"),
        N_("Divides each video into consecutive segments of the length you choose. Streams are copied, not re-encoded, so every part keeps the original quality."),
        OutputArea.SPLITS),
    TRIM_VIDEO(ToolSection.VIDEO, N_("Trim & rotate video"), N_("Cut the start and end, fix sideways videos"),
        N_("Keeps only the part you choose and can turn the video by 90° steps. Streams are copied, not re-encoded, so quality is untouched; the start snaps to the nearest earlier keyframe."),
        OutputArea.TRIMMED),
    OPTIMIZE_VIDEO(ToolSection.VIDEO, N_("Lossless video optimizer"), N_("Repackage for fast start; quality untouched"),
        N_("Copies the existing video and audio streams bit-for-bit into a clean MP4 with the index at the front (plays and uploads faster). This is not compression: quality is identical and the size changes only slightly."),
        OutputArea.OPTIMIZED_VIDEO),
    COMPRESS_VIDEO(ToolSection.VIDEO, N_("Video compressor"), N_("Re-encode to smaller H.264 MP4 files"),
        N_("Re-encodes videos to H.264 + AAC MP4 at the quality you choose. Lower quality gives smaller files."),
        OutputArea.COMPRESSED_VIDEO),
    MERGE_VIDEOS(ToolSection.VIDEO, N_("Merge videos"), N_("Join clips into one video, in your order"),
        N_("Joins videos end to end. Clips recorded with the same settings are joined without re-encoding (no quality loss); others are converted to a common size and format first."),
        OutputArea.MERGED_VIDEO),
    VIDEO_SPEED(ToolSection.VIDEO, N_("Speed & timelapse"), N_("Slow motion, fast forward, timelapses"),
        N_("Plays videos from 0.25× to 60× speed. The sound keeps its natural pitch, or can be muted; long recordings become smooth timelapses."),
        OutputArea.SPEED_VIDEO),
    REMOVE_AUDIO(ToolSection.VIDEO, N_("Remove video audio"), N_("Silent copies with the video untouched"),
        N_("Creates copies of your videos without the sound track. The picture is copied as-is, with no quality loss."),
        OutputArea.NO_AUDIO),

    COMPRESS_IMAGES(ToolSection.IMAGES, N_("Image compressor"), N_("JPEG or WebP with quality and size limits"),
        N_("Re-encodes photos as JPEG or WebP at the quality you choose, optionally limiting the width. Aspect ratio and orientation are always preserved."),
        OutputArea.COMPRESSED_IMAGES),
    OPTIMIZE_IMAGES(ToolSection.IMAGES, N_("Lossless image optimizer"), N_("Pixel-identical PNG or lossless WebP"),
        N_("Stores every pixel exactly as it is in an efficiently compressed lossless file. Savings depend on the source and are not guaranteed."),
        OutputArea.COMPRESSED_IMAGES),
    CONVERT_IMAGES(ToolSection.IMAGES, N_("Convert images"), N_("PNG, JPEG or WebP from most formats"),
        N_("Converts images (HEIC, AVIF, TIFF, BMP, PSD, RAW previews and more) to PNG, JPEG or WebP."),
        OutputArea.CONVERTED),
    MERGE_IMAGES(ToolSection.IMAGES, N_("Merge images"), N_("Stack, line up or pack images into one PNG"),
        N_("Combines two or more images into a single PNG without scaling or cropping any of them."),
        OutputArea.MERGED),
    STITCH(ToolSection.IMAGES, N_("Multi-shot stitcher"), N_("Join overlapping photos into a panorama"),
        N_("Finds where your overlapping photos meet, aligns them and blends them into one high-resolution lossless PNG."),
        OutputArea.STITCHED),
    WATERMARK(ToolSection.IMAGES, N_("Bulk watermark"), N_("Text or logo on every photo in a batch"),
        N_("Adds a text and/or logo watermark to every image, with independent placement for each image shape."),
        OutputArea.WATERMARKED),
    DUPLICATES(ToolSection.IMAGES, N_("Duplicate finder"), N_("Find copies and similar shots, free space"),
        N_("Scans your photos on this phone for identical copies, re-saved duplicates and near-identical shots, suggests the best one to keep and moves the rest to the trash when you confirm."),
        OutputArea.CLEAN_IMAGES),

    VIDEO_TO_GIF(ToolSection.GIF, N_("Video → GIF"), N_("Animated GIFs with adaptive colours"),
        N_("Turns videos into animated GIFs with an adaptive colour palette and accurate frame timing."),
        OutputArea.CONVERTED_GIF),
    COMPRESS_GIF(ToolSection.GIF, N_("GIF compressor"), N_("Smaller GIFs: size, frame rate, colours"),
        N_("Shrinks existing GIFs by reducing their dimensions, frame rate and colour palette."),
        OutputArea.GIFS),
    OPTIMIZE_GIF(ToolSection.GIF, N_("GIF optimizer"), N_("Re-pack GIFs while keeping every pixel"),
        N_("Re-encodes GIFs more efficiently while keeping every displayed pixel identical."),
        OutputArea.GIFS),

    PDF_TO_IMAGES(ToolSection.DOCUMENTS, N_("PDF → images"), N_("Every page as a PNG or JPEG"),
        N_("Renders each page of a PDF to an image exactly as it looks on screen."),
        OutputArea.PDF_IMAGES),
    IMAGES_TO_PDF(ToolSection.DOCUMENTS, N_("Images → PDF"), N_("One A4 page per image, in your order"),
        N_("Builds an A4 PDF with one image per page, in the order you choose."),
        OutputArea.PDF),
    MERGE_PDFS(ToolSection.DOCUMENTS, N_("Merge PDFs"), N_("Combine PDFs, keeping text and links"),
        N_("Joins PDFs into one document, keeping their original text, vector graphics, links and page sizes."),
        OutputArea.PDF),
    EXTRACT_PDF_PAGES(ToolSection.DOCUMENTS, N_("Extract PDF pages"), N_("Pick pages into a new PDF or separate files"),
        N_("Choose page numbers or ranges (like 1-3, 7, 10-) and save them as one new PDF or one PDF per page. Pages are copied as they are: text, links and quality are kept."),
        OutputArea.PDF),
    PDF_SCANNER(ToolSection.DOCUMENTS, N_("PDF scanner"), N_("Camera or gallery pages into a PDF"),
        N_("Captures pages with the camera or adds photos from the gallery and saves them as an A4 PDF."),
        OutputArea.PDF),

    PRINT(ToolSection.PRINT, N_("Print"), N_("Photos and PDFs on your Wi-Fi printer, no printer app"),
        N_("Prints photos and PDFs straight to a printer on your Wi-Fi — Epson, HP, Canon, Brother and others that support AirPrint, Mopria or IPP Everywhere — without the printer maker's app. Pick the paper size and type, borderless, several photos per sheet, colour or black & white, quality and copies, with a preview of every sheet. The app talks only to the printer on your own network."),
        OutputArea.PDF),

    REMOVE_METADATA(ToolSection.PRIVACY, N_("Remove metadata"), N_("Strip location, camera and date info"),
        N_("Makes clean copies of photos and videos without GPS location, camera details, dates, comments or hidden thumbnails. Photo pixels and video streams are copied unchanged (no quality loss); orientation and colour profile are kept."),
        OutputArea.CLEAN_IMAGES, OutputArea.CLEAN_VIDEO),
    EXTRACT_AUDIO(ToolSection.PRIVACY, N_("Extract audio"), N_("Save a video's AAC sound as M4A"),
        N_("Copies the AAC sound track of each video into an M4A audio file without re-encoding."),
        OutputArea.EXTRACTED_AUDIO);

    val title: String get() = tr(englishTitle)
    val shortDescription: String get() = tr(shortEn)
    val longDescription: String get() = tr(longEn)

    val outputPath: String get() = videoArea?.let { "${area.displayPath}\n${it.displayPath}" } ?: area.displayPath

    companion object {
        /** Number of tools (the tool stack runs other tools, so it isn't counted). */
        val toolCount: Int get() = entries.count { it != TOOL_STACK }
    }
}
