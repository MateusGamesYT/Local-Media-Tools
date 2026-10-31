package com.localmediatools.ui.meta

import com.localmediatools.app.R
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr
import com.localmediatools.meta.core.MetaField
import com.localmediatools.meta.core.Privacy

/**
 * How the metadata viewer names things: the kinds of personal detail, the groups fields are stored
 * in, and the common fields in plain words (the others keep their technical name, split into words).
 */
object MetaLabels {
    fun privacy(p: Privacy): String = when (p) {
        Privacy.LOCATION -> tr("Where it was made")
        Privacy.PERSON -> tr("Who made it or owns it")
        Privacy.DEVICE -> tr("The camera, phone or computer")
        Privacy.TIME -> tr("When it was made or changed")
        Privacy.SOFTWARE -> tr("The programs used")
        Privacy.IDENTIFIER -> tr("Numbers that link it to other files")
        Privacy.HIDDEN_IMAGE -> tr("Pictures hidden inside")
        Privacy.TEXT -> tr("Written descriptions, comments and keywords")
    }

    fun icon(p: Privacy): Int = when (p) {
        Privacy.LOCATION -> R.drawable.ic_pin
        Privacy.PERSON -> R.drawable.ic_person
        Privacy.DEVICE -> R.drawable.ic_camera
        Privacy.TIME -> R.drawable.ic_clock
        Privacy.SOFTWARE -> R.drawable.ic_tune
        Privacy.IDENTIFIER -> R.drawable.ic_lock
        Privacy.HIDDEN_IMAGE -> R.drawable.ic_image
        Privacy.TEXT -> R.drawable.ic_file
    }

    fun color(p: Privacy): Int = when (p) {
        Privacy.LOCATION -> 0xFFF87171.toInt()
        Privacy.PERSON -> 0xFFFB923C.toInt()
        Privacy.DEVICE -> 0xFF60A5FA.toInt()
        Privacy.TIME -> 0xFFA78BFA.toInt()
        Privacy.SOFTWARE -> 0xFF94A3B8.toInt()
        Privacy.IDENTIFIER -> 0xFFFBBF24.toInt()
        Privacy.HIDDEN_IMAGE -> 0xFF34D399.toInt()
        Privacy.TEXT -> 0xFF2DD4BF.toInt()
    }

    /** The kind of file, as people call it. */
    fun kind(format: String): String = when (format) {
        "JPEG", "PNG", "GIF", "WEBP", "BMP", "TIFF", "HEIC", "HEIF", "AVIF" -> tr("{0} picture", format)
        "DNG", "CR2", "CR3", "NEF", "NRW", "ARW", "SR2", "SRF", "PEF", "ORF", "RW2", "RAF", "SRW", "ERF", "3FR", "IIQ", "MOS", "MEF", "KDC", "DCR", "X3F" -> tr("{0} camera RAW photo", format)
        "MP4", "MOV", "MKV", "WEBM", "AVI", "3GP", "3G2", "M4V", "OGV" -> tr("{0} video", format)
        "MP3", "M4A", "M4B", "FLAC", "OGG", "OPUS", "WAV" -> tr("{0} audio", format)
        "PDF" -> tr("PDF document")
        "DOC", "DOCX", "DOCM" -> tr("Word document")
        "XLS", "XLSX", "XLSM" -> tr("Excel workbook")
        "PPT", "PPTX", "PPTM" -> tr("PowerPoint presentation")
        "ODT", "ODS", "ODP", "ODG" -> tr("OpenDocument file")
        "EPUB" -> tr("E-book")
        "ZIP" -> tr("ZIP archive")
        "PSD" -> tr("Photoshop file")
        else -> format
    }

    /** The section a field is listed in: groups of the same kind share one. */
    fun group(g: String): String = when {
        g == "File" -> tr("File")
        g == "Composite" -> tr("Worked out from the fields above")
        g == "IFD0" -> tr("Main picture (EXIF)")
        g == "ExifIFD" -> tr("Camera settings (EXIF)")
        g == "GPS" -> tr("Location (GPS)")
        g == "InteropIFD" -> tr("Compatibility (EXIF)")
        g == "IFD1" -> tr("Thumbnail (EXIF)")
        g.startsWith("SubIFD") || g.matches(Regex("IFD\\d+")) || g == "PreviewIFD" -> tr("Other pictures inside (EXIF)")
        g.startsWith("XMP-") -> "XMP · " + g.removePrefix("XMP-")
        g.startsWith("IPTC") -> tr("Captions and credits (IPTC)")
        g.startsWith("ICC") -> tr("Colour profile")
        g == "JFIF" || g == "Adobe" || g == "Ducky" -> "JPEG"
        g == "MPF0" || g.startsWith("MPImage") -> tr("Pictures inside (MPF)")
        g == "QuickTime" -> tr("Video file")
        g.startsWith("Track") -> tr("Track {0}", g.removePrefix("Track"))
        g == "Keys" || g == "UserData" || g == "ItemList" -> tr("Tags")
        g.startsWith("ID3") -> tr("Song tags (ID3)")
        g == "Vorbis" -> tr("Song tags")
        g == "MPEG" -> tr("Sound")
        g == "XML" || g == "FlashPix" -> tr("Document properties")
        g == "MS-DOC" -> tr("Word document")
        g == "EPUB" -> tr("E-book")
        g.startsWith("HTML") || g == "HTTP-equiv" -> "HTML"
        g.startsWith("PNG") -> "PNG"
        g == "RAF" -> "Fujifilm RAW"
        g == "PrintIM" -> "Print Image Matching"
        else -> g
    }

    /** A field's name in plain words when it's a common one, else its technical name split into words. */
    fun field(f: MetaField): String = NAMES[f.name]?.let { tr(it) } ?: words(f.name)

    /** A value as shown: yes/no in the app's language, dates as dates; the rest as the file has it. */
    fun value(f: MetaField): String = when (f.value) {
        "Yes" -> tr("Yes"); "No" -> tr("No"); "True" -> tr("Yes"); "False" -> tr("No")
        else -> (if (f.privacy == Privacy.TIME || f.name.contains("Date") || f.name.endsWith("Printed")) date(f.value) else null) ?: f.value
    }

    private val DATE = Regex("""^(\d{4}):(\d\d):(\d\d)(?:[ T](\d\d):(\d\d)(?::(\d\d))?(?:\.\d+)?)?\s*(Z|UTC|[+-]\d\d:?\d\d)?$""")

    /** "2023:07:14 18:30:05-03:00" → "14 Jul 2023, 18:30 (UTC−03:00)" in the app's language; null when it isn't a date. */
    fun date(s: String): String? {
        val m = DATE.matchEntire(s.trim()) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        if (y < 1800 || mo !in 1..12 || d !in 1..31) return null
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val c = java.util.Calendar.getInstance(utc).apply { clear(); set(y, mo - 1, d, g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0, g[6].toIntOrNull() ?: 0) }
        val loc = com.localmediatools.i18n.I18n.locale
        // The clock time as the file has it (not converted to this phone's time zone), then the zone it names.
        val fmt = if (g[4].isEmpty()) java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, loc)
            else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, loc)
        fmt.timeZone = utc
        val zone = when (val z = g[7]) {
            "" -> ""
            "Z", "UTC" -> " " + tr("(UTC)")
            else -> " (UTC" + z.replace("-", "−").let { if (it.length == 5) it.substring(0, 3) + ":" + it.substring(3) else it } + ")"
        }
        return fmt.format(c.time) + zone
    }

    /** "DateTimeOriginal" → "Date time original"; "GPSLatitude" → "GPS latitude". */
    fun words(name: String): String {
        val s = name.replace('_', ' ').replace('-', ' ')
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return s.split(' ').mapIndexed { i, w -> if (i > 0 && w.length > 1 && w.drop(1).all { it.isLowerCase() }) w.lowercase() else w }.joinToString(" ")
    }

    private val NAMES = mapOf(
        "FileName" to N_("File name"), "FileSize" to N_("File size"), "FileModifyDate" to N_("Changed on this phone"), "FileType" to N_("Kind of file"),
        "MIMEType" to N_("Media type"), "ImageSize" to N_("Picture size"), "Megapixels" to N_("Megapixels"), "ImageWidth" to N_("Width"),
        "ImageHeight" to N_("Height"), "ExifImageWidth" to N_("Width"), "ExifImageHeight" to N_("Height"), "Make" to N_("Camera maker"),
        "Model" to N_("Camera model"), "LensMake" to N_("Lens maker"), "LensModel" to N_("Lens"), "SerialNumber" to N_("Serial number"),
        "BodySerialNumber" to N_("Camera serial number"), "LensSerialNumber" to N_("Lens serial number"), "InternalSerialNumber" to N_("Serial number"),
        "OwnerName" to N_("Owner"), "CameraOwnerName" to N_("Owner"), "Artist" to N_("Artist"), "Copyright" to N_("Copyright"), "Software" to N_("Software"),
        "DateTimeOriginal" to N_("Taken"), "CreateDate" to N_("Created"), "ModifyDate" to N_("Changed"), "OffsetTimeOriginal" to N_("Time zone when taken"),
        "OffsetTime" to N_("Time zone"), "GPSLatitude" to N_("Latitude"), "GPSLongitude" to N_("Longitude"), "GPSPosition" to N_("Position"),
        "GPSAltitude" to N_("Altitude"), "GPSCoordinates" to N_("Position"), "GPSDateStamp" to N_("GPS date"), "GPSTimeStamp" to N_("GPS time (UTC)"),
        "GPSImgDirection" to N_("Facing direction"), "GPSSpeed" to N_("Speed"), "LocationInformation" to N_("Location"), "ExposureTime" to N_("Shutter speed"),
        "FNumber" to N_("Aperture"), "ISO" to N_("ISO"), "FocalLength" to N_("Focal length"), "FocalLengthIn35mmFormat" to N_("Focal length (35 mm equivalent)"),
        "Flash" to N_("Flash"), "WhiteBalance" to N_("White balance"), "Orientation" to N_("Orientation"), "ImageDescription" to N_("Description"),
        "UserComment" to N_("Comment"), "Comment" to N_("Comment"), "Comments" to N_("Comments"), "ImageUniqueID" to N_("Picture ID"), "Title" to N_("Title"),
        "Author" to N_("Author"), "Creator" to N_("Creator"), "LastModifiedBy" to N_("Last saved by"), "Company" to N_("Company"), "Manager" to N_("Manager"),
        "Keywords" to N_("Keywords"), "Subject" to N_("Subject"), "Description" to N_("Description"), "Duration" to N_("Length"),
        "VideoFrameRate" to N_("Frame rate"), "AvgBitrate" to N_("Average bit rate"), "AudioBitrate" to N_("Bit rate"), "AudioSampleRate" to N_("Sample rate"),
        "SampleRate" to N_("Sample rate"), "AudioChannels" to N_("Channels"), "NumChannels" to N_("Channels"), "Channels" to N_("Channels"),
        "Album" to N_("Album"), "AlbumArtist" to N_("Album artist"), "Genre" to N_("Genre"), "Year" to N_("Year"), "Track" to N_("Track"),
        "TrackNumber" to N_("Track"), "Composer" to N_("Composer"), "PageCount" to N_("Pages"), "Pages" to N_("Pages"), "Words" to N_("Words"),
        "Producer" to N_("Made by (program)"), "CreatorTool" to N_("Made with"), "Encryption" to N_("Protection"), "UserAccess" to N_("Allowed"),
        "Rotation" to N_("Rotation"), "City" to N_("City"), "Country" to N_("Country"), "State" to N_("State or province"), "Sub-location" to N_("Place"),
        "Province-State" to N_("State or province"), "Country-PrimaryLocationName" to N_("Country"), "Headline" to N_("Headline"),
        "Caption-Abstract" to N_("Caption"), "By-line" to N_("By"), "Credit" to N_("Credit"), "ThumbnailImage" to N_("Small copy of the picture"),
        "PreviewImage" to N_("Preview picture"), "JpgFromRaw" to N_("Full-size JPEG copy"), "Picture" to N_("Cover picture"), "CoverArt" to N_("Cover picture"),
        "TotalEditTime" to N_("Editing time"), "RevisionNumber" to N_("Revisions"), "Template" to N_("Template"), "Application" to N_("Application"),
        "LastPrinted" to N_("Last printed"), "LastSavedBy" to N_("Saved by (with the file's folder)"), "CommentBy" to N_("Comments written by"),
        "CurrentUser" to N_("Last opened by"), "AuthorEmail" to N_("Author's e-mail"), "AuthorEmailDisplayName" to N_("Author's name (e-mail)"),
        "EmailSubject" to N_("E-mail subject"), "HostComputer" to N_("Computer"), "AndroidModel" to N_("Phone model"), "AndroidManufacturer" to N_("Phone maker"),
        "AndroidVersion" to N_("Android version"), "XMPToolkit" to N_("XMP written by"), "DocumentID" to N_("Document ID"), "InstanceID" to N_("Instance ID"),
        "OriginalDocumentID" to N_("Original document ID"), "Rating" to N_("Rating"), "ColorSpace" to N_("Colour space"),
        "ProfileDescription" to N_("Colour profile"), "Language" to N_("Language"), "InitialCreator" to N_("First author"), "Generator" to N_("Made with"),
        "Encoder" to N_("Encoder"), "EncodedBy" to N_("Encoded by"), "Hyperlinks" to N_("Links"), "HyperlinkBase" to N_("Link base"),
        "MPImage" to N_("Pictures inside"), "MPImageType" to N_("Kind of picture"), "MakerNote" to N_("Camera maker's notes"),
    )
}
