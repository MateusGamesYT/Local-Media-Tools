package com.localmediatools.meta.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * What a file says about itself. Plain Kotlin (no Android), so it runs and is measured on the JVM
 * (buildtools/meta, against ExifTool).
 */

/** What a field can tell others about the person who made the file. */
enum class Privacy {
    /** Where it was made (GPS coordinates, a place name). */
    LOCATION,
    /** Who made it or owns it (author, artist, owner, company, last editor). */
    PERSON,
    /** The camera, phone or computer, and its serial numbers. */
    DEVICE,
    /** When it was made or changed. */
    TIME,
    /** The programs that made or edited it. */
    SOFTWARE,
    /** Numbers that identify this file or device across copies. */
    IDENTIFIER,
    /** A smaller copy of the picture inside the file (it can show what was cropped away). */
    HIDDEN_IMAGE,
    /** Free text written into the file (comments, descriptions, keywords). */
    TEXT,
}

/**
 * One piece of metadata. [group] is where it is stored ("EXIF", "GPS", "XMP-dc", "ID3v2", "PDF"…),
 * [name] its name as ExifTool calls it ("DateTimeOriginal"), [value] as shown to people, [raw]
 * the bare value (numbers as numbers, as `exiftool -n` gives them) for comparing.
 */
class MetaField(val group: String, val name: String, val value: String, val raw: String = value, val privacy: Privacy? = null) {
    override fun toString() = "$group:$name=$value"
}

/** Everything read from a file: its kind, the fields in file order, and what couldn't be read. */
class MetaReport(val format: String, val fields: List<MetaField>, val notes: List<String>) {
    fun get(name: String): MetaField? = fields.firstOrNull { it.name == name }
    fun all(name: String): List<MetaField> = fields.filter { it.name == name }
    fun group(g: String): List<MetaField> = fields.filter { it.group == g }

    /** Decimal latitude and longitude, when the file says where it was made. */
    val location: Pair<Double, Double>? get() {
        val lat = (get("GPSLatitude") ?: get("Latitude"))?.raw?.toDoubleOrNull() ?: return null
        val lon = (get("GPSLongitude") ?: get("Longitude"))?.raw?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return lat to lon
    }
}

/** Random access to the file being read, with the little helpers every reader needs. */
class MetaInput(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val size: Long = raf.length()

    fun bytes(at: Long, len: Int): ByteArray {
        if (at < 0 || len <= 0 || at >= size) return ByteArray(0)
        val n = minOf(len.toLong(), size - at).toInt()
        val b = ByteArray(n)
        synchronized(raf) { raf.seek(at); raf.readFully(b) }
        return b
    }

    fun u8(at: Long): Int = bytes(at, 1).let { if (it.isEmpty()) -1 else it[0].toInt() and 0xFF }
    fun u16be(at: Long): Int = bytes(at, 2).let { if (it.size < 2) -1 else ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
    fun u16le(at: Long): Int = bytes(at, 2).let { if (it.size < 2) -1 else ((it[1].toInt() and 0xFF) shl 8) or (it[0].toInt() and 0xFF) }
    fun u32be(at: Long): Long = bytes(at, 4).let { b -> if (b.size < 4) -1 else (0 until 4).fold(0L) { v, i -> (v shl 8) or (b[i].toLong() and 0xFF) } }
    fun u32le(at: Long): Long = bytes(at, 4).let { b -> if (b.size < 4) -1 else (3 downTo 0).fold(0L) { v, i -> (v shl 8) or (b[i].toLong() and 0xFF) } }
    fun u64be(at: Long): Long = bytes(at, 8).let { b -> if (b.size < 8) -1 else (0 until 8).fold(0L) { v, i -> (v shl 8) or (b[i].toLong() and 0xFF) } }
    fun ascii(at: Long, len: Int): String = String(bytes(at, len), Charsets.ISO_8859_1)
    fun startsWith(at: Long, s: String) = ascii(at, s.length) == s

    override fun close() = raf.close()
}

/** Byte helpers on arrays. */
internal object B {
    fun u16be(b: ByteArray, o: Int) = if (o + 2 > b.size || o < 0) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun u16le(b: ByteArray, o: Int) = if (o + 2 > b.size || o < 0) 0 else ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    fun u32be(b: ByteArray, o: Int): Long = if (o + 4 > b.size || o < 0) 0 else (0 until 4).fold(0L) { v, i -> (v shl 8) or (b[o + i].toLong() and 0xFF) }
    fun u32le(b: ByteArray, o: Int): Long = if (o + 4 > b.size || o < 0) 0 else (3 downTo 0).fold(0L) { v, i -> (v shl 8) or (b[o + i].toLong() and 0xFF) }
    fun u64be(b: ByteArray, o: Int): Long = if (o + 8 > b.size || o < 0) 0 else (0 until 8).fold(0L) { v, i -> (v shl 8) or (b[o + i].toLong() and 0xFF) }
    fun ascii(b: ByteArray, o: Int, n: Int) = if (o < 0 || o >= b.size) "" else String(b, o, minOf(n, b.size - o), Charsets.ISO_8859_1)
    fun starts(b: ByteArray, o: Int, s: String) = o >= 0 && o + s.length <= b.size && s.indices.all { b[o + it] == s[it].code.toByte() }

    /** Text up to its first zero, trimmed. */
    fun cstring(b: ByteArray, o: Int, n: Int, cs: java.nio.charset.Charset = Charsets.ISO_8859_1): String {
        if (o < 0 || o >= b.size) return ""
        val end = minOf(b.size, o + n)
        var e = o
        while (e < end && b[e] != 0.toByte()) e++
        return String(b, o, e - o, cs).trim()
    }
}

/** Collects fields while a reader goes through a file. */
class Fields {
    val list = ArrayList<MetaField>()
    val notes = ArrayList<String>()

    fun add(group: String, name: String, value: String, raw: String = value, privacy: Privacy? = null) {
        val v = clean(value)
        if (v.isEmpty()) return
        list.add(MetaField(group, name, v, clean(raw).ifEmpty { v }, privacy ?: Privacies.of(name)))
    }

    fun note(text: String) { if (text !in notes) notes.add(text) }

    companion object {
        /** No control characters, no runaway values (a field over 2,000 characters shows its start). */
        fun clean(s: String): String {
            val t = buildString(minOf(s.length, 2100)) {
                for (c in s) { if (length >= 2000) break; append(if (c < ' ' && c != '\n' && c != '\t') ' ' else c) }
            }.trim()
            return if (s.length > 2000) "$t…" else t
        }
    }
}

/** Which fields say something about the person, by their name (the same names in every format). */
object Privacies {
    private val location = setOf("GPSLatitude", "GPSLongitude", "GPSAltitude", "GPSPosition", "GPSDateStamp", "GPSTimeStamp", "GPSAreaInformation",
        "GPSDestLatitude", "GPSDestLongitude", "GPSImgDirection", "GPSSpeed", "GPSTrack", "GPSProcessingMethod", "GPSHPositioningError",
        "Latitude", "Longitude", "Location", "LocationName", "City", "State", "Country", "CountryCode", "Sub-location", "Province-State",
        "Country-PrimaryLocationName", "LocationCreated", "LocationShown", "GPSCoordinates", "Altitude")
    private val person = setOf("Artist", "Author", "Creator", "By-line", "Copyright", "CopyrightNotice", "Rights", "OwnerName", "CameraOwnerName",
        "LastModifiedBy", "LastAuthor", "Company", "Manager", "Writer", "Composer", "Lyricist", "Publisher", "Contact", "Email", "Credit",
        "CreatorContactInfo", "Owner", "InitialCreator", "UserName", "Photographer", "Copyrights", "Director", "Producer", "Encoder", "EncodedBy",
        "Signature", "Contributor", "Band", "AlbumArtist", "Performer", "Owner-Name", "XPAuthor", "Uploader")
    private val device = setOf("Make", "Model", "LensMake", "LensModel", "LensSerialNumber", "SerialNumber", "BodySerialNumber", "InternalSerialNumber",
        "CameraSerialNumber", "UniqueCameraModel", "LocalizedCameraModel", "HostComputer", "AndroidModel", "AndroidMake", "AndroidManufacturer",
        "CameraModelName", "DeviceManufacturer", "DeviceModel", "LensInfo", "ComAppleQuicktimeMake", "ComAppleQuicktimeModel")
    private val identifier = setOf("ImageUniqueID", "DocumentID", "InstanceID", "OriginalDocumentID", "DerivedFromDocumentID", "DerivedFromInstanceID",
        "UniqueID", "GUID", "ContentIdentifier", "MediaUID", "SegmentUID", "Identifier", "PreservedFileName", "RawFileName", "OriginalRawFileName",
        "ImageNumber", "ShutterCount", "FileNumber", "UserComment-ID", "RevisionNumber")
    private val software = setOf("Software", "CreatorTool", "Producer", "Creator-Tool", "Application", "AppVersion", "Encoder", "WritingApp", "MuxingApp",
        "HistorySoftwareAgent", "ProcessingSoftware", "Generator", "Template", "AndroidVersion")
    private val time = setOf("DateTimeOriginal", "CreateDate", "ModifyDate", "DateTime", "DateTimeDigitized", "MetadataDate", "DateCreated", "TimeCreated",
        "CreationDate", "ModDate", "MediaCreateDate", "TrackCreateDate", "DateTimeUTC", "Date", "Year", "RecordingTime", "LastPrinted", "TotalEditTime",
        "LastSaved", "Created", "Modified", "OffsetTime", "OffsetTimeOriginal", "SubSecDateTimeOriginal")
    private val text = setOf("ImageDescription", "UserComment", "Comment", "Description", "Caption-Abstract", "Keywords", "Subject", "Title", "Headline",
        "Instructions", "SpecialInstructions", "Notes", "XPComment", "XPKeywords", "XPSubject", "XPTitle", "Lyrics", "Category", "Label")

    fun of(name: String): Privacy? = when (name) {
        in location -> Privacy.LOCATION
        in person -> Privacy.PERSON
        in device -> Privacy.DEVICE
        in identifier -> Privacy.IDENTIFIER
        in software -> Privacy.SOFTWARE
        in time -> Privacy.TIME
        in text -> Privacy.TEXT
        "ThumbnailImage", "PreviewImage", "JpgFromRaw", "OtherImage", "CoverArt", "Picture", "ThumbnailLength", "PreviewImageLength" -> Privacy.HIDDEN_IMAGE
        else -> null
    }
}
