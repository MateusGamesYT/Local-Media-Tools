package com.localmediatools.meta.core

/**
 * IPTC (the news agencies' captions, keywords, places and credits), Photoshop's resource blocks that
 * carry it, and ICC colour profiles.
 */
object IptcReader {
    private val RECORD2 = mapOf(
        0 to "ApplicationRecordVersion", 3 to "ObjectTypeReference", 4 to "ObjectAttributeReference", 5 to "ObjectName", 7 to "EditStatus",
        10 to "Urgency", 12 to "SubjectReference", 15 to "Category", 20 to "SupplementalCategories", 22 to "FixtureIdentifier", 25 to "Keywords",
        26 to "ContentLocationCode", 27 to "ContentLocationName", 30 to "ReleaseDate", 35 to "ReleaseTime", 37 to "ExpirationDate",
        38 to "ExpirationTime", 40 to "SpecialInstructions", 42 to "ActionAdvised", 45 to "ReferenceService", 47 to "ReferenceDate",
        50 to "ReferenceNumber", 55 to "DateCreated", 60 to "TimeCreated", 62 to "DigitalCreationDate", 63 to "DigitalCreationTime",
        65 to "OriginatingProgram", 70 to "ProgramVersion", 75 to "ObjectCycle", 80 to "By-line", 85 to "By-lineTitle", 90 to "City",
        92 to "Sub-location", 95 to "Province-State", 100 to "Country-PrimaryLocationCode", 101 to "Country-PrimaryLocationName",
        103 to "OriginalTransmissionReference", 105 to "Headline", 110 to "Credit", 115 to "Source", 116 to "CopyrightNotice",
        118 to "Contact", 120 to "Caption-Abstract", 121 to "LocalCaption", 122 to "Writer-Editor", 130 to "ImageType",
        131 to "ImageOrientation", 135 to "LanguageIdentifier", 184 to "JobID", 187 to "OwnerID", 221 to "Prefs", 228 to "ClassifyState",
        230 to "DocumentNotes", 231 to "DocumentHistory", 232 to "ExifCameraInfo",
    )
    private val RECORD1 = mapOf(0 to "EnvelopeRecordVersion", 5 to "Destination", 20 to "FileFormat", 22 to "FileVersion", 30 to "ServiceIdentifier",
        40 to "EnvelopeNumber", 50 to "ProductID", 60 to "EnvelopePriority", 70 to "DateSent", 80 to "TimeSent", 90 to "CodedCharacterSet", 100 to "UniqueObjectName")

    fun read(b: ByteArray, out: Fields) {
        var utf8 = false
        // Repeated datasets (keywords) become one list.
        val lists = LinkedHashMap<String, MutableList<String>>()
        var i = 0
        while (i + 5 <= b.size) {
            if (b[i] != 0x1C.toByte()) { i++; continue }
            val rec = b[i + 1].toInt() and 0xFF; val ds = b[i + 2].toInt() and 0xFF
            var len = B.u16be(b, i + 3); var start = i + 5
            if (len and 0x8000 != 0) {
                // Extended length: the next (len & 0x7FFF) bytes hold it.
                val n = len and 0x7FFF
                len = (0 until n).fold(0) { v, k -> (v shl 8) or (b.getOrElse(start + k) { 0 }.toInt() and 0xFF) }
                start += n
            }
            if (start + len > b.size || len < 0) break
            val data = b.copyOfRange(start, start + len)
            i = start + len
            if (rec == 1 && ds == 90) utf8 = data.contentEquals(byteArrayOf(0x1B, 0x25, 0x47))
            val name = (if (rec == 1) RECORD1 else if (rec == 2) RECORD2 else null)?.get(ds) ?: continue
            val v = when {
                name == "ApplicationRecordVersion" || name == "EnvelopeRecordVersion" || name == "FileFormat" || name == "FileVersion" -> B.u16be(data, 0).toString()
                name == "CodedCharacterSet" -> if (utf8) "UTF8" else data.joinToString(" ") { (it.toInt() and 0xFF).toString() }
                else -> String(data, if (utf8) Charsets.UTF_8 else Charsets.ISO_8859_1).trimEnd('\u0000').trim()
            }
            lists.getOrPut(name) { ArrayList() }.add(v)
        }
        for ((name, vs) in lists) {
            val v = vs.joinToString(", ")
            val raw = when {
                name.endsWith("Date") && v.length == 8 && v.all { it.isDigit() } -> "${v.take(4)}:${v.substring(4, 6)}:${v.substring(6)}"
                name.endsWith("Time") && v.length >= 6 -> v.take(2) + ":" + v.substring(2, 4) + ":" + v.substring(4, 6) + v.drop(6).let { z -> if (z.length == 5) z.take(3) + ":" + z.drop(3) else z }
                else -> v
            }
            out.add("IPTC", name, raw, raw)
        }
    }
}

/** Photoshop's image resource blocks ("8BIM"): IPTC, XMP, EXIF, ICC, resolution, thumbnail. */
object PhotoshopReader {
    class Found(var iptc: ByteArray? = null, var xmp: ByteArray? = null, var exif: ByteArray? = null, var icc: ByteArray? = null)

    fun read(b: ByteArray, at: Int, out: Fields): Found {
        val f = Found()
        var i = at
        while (i + 12 <= b.size && B.starts(b, i, "8BIM")) {
            val id = B.u16be(b, i + 4)
            val nameLen = b[i + 6].toInt() and 0xFF
            var p = i + 7 + nameLen
            if ((nameLen + 1) % 2 == 1) p++
            val size = B.u32be(b, p).toInt()
            val start = p + 4
            if (size < 0 || start + size > b.size) break
            val data = b.copyOfRange(start, start + size)
            when (id) {
                0x0404 -> f.iptc = data
                0x0424 -> f.xmp = data
                0x0422 -> f.exif = data
                0x040F -> f.icc = data
                0x03ED -> if (data.size >= 16) {
                    out.add("Photoshop", "XResolution", ExifReader.num(B.u32be(data, 0) / 65536.0))
                    out.add("Photoshop", "YResolution", ExifReader.num(B.u32be(data, 8) / 65536.0))
                }
                0x0406 -> if (data.size >= 2) out.add("Photoshop", "PhotoshopQuality", (B.u16be(data, 0).toShort() + 4).toString())
                0x0409, 0x040C -> out.add("Photoshop", "PhotoshopThumbnail", "(${maxOf(0, size - 28)} bytes, a smaller copy of the picture)", "${maxOf(0, size - 28)}")
                0x0425 -> out.add("Photoshop", "IPTCDigest", data.joinToString("") { "%02x".format(it) })
                0x041A -> {} // slices
                0x0421 -> if (data.size > 8) out.add("Photoshop", "HasRealMergedData", if (data[4].toInt() != 0) "Yes" else "No", "${data[4].toInt()}")
            }
            i = start + size + (size % 2)
        }
        return f
    }
}

object IccReader {
    private val CLASS = mapOf("scnr" to "Input Device Profile", "mntr" to "Display Device Profile", "prtr" to "Output Device Profile",
        "link" to "DeviceLink Profile", "spac" to "ColorSpace Conversion Profile", "abst" to "Abstract Profile", "nmcl" to "NamedColor Profile")
    private val PLATFORM = mapOf("APPL" to "Apple Computer Inc.", "MSFT" to "Microsoft Corporation", "SGI " to "Silicon Graphics Inc.", "SUNW" to "Sun Microsystems Inc.")
    private val INTENT = mapOf(0 to "Perceptual", 1 to "Media-Relative Colorimetric", 2 to "Saturation", 3 to "ICC-Absolute Colorimetric")

    fun read(b: ByteArray, out: Fields) {
        if (b.size < 132 || !B.starts(b, 36, "acsp")) { if (b.isNotEmpty()) out.note("The colour profile is damaged."); return }
        val g = "ICC-header"
        fun cc(o: Int) = B.ascii(b, o, 4)
        out.add(g, "ProfileCMMType", cc(4).trim())
        out.add(g, "ProfileVersion", "${b[8].toInt() and 0xFF}.${(b[9].toInt() shr 4) and 0xF}.${b[9].toInt() and 0xF}", "${B.u16be(b, 8)}")
        out.add(g, "ProfileClass", CLASS[cc(12)] ?: cc(12), cc(12).trim())
        out.add(g, "ColorSpaceData", cc(16).trim())
        out.add(g, "ProfileConnectionSpace", cc(20).trim())
        val y = B.u16be(b, 24)
        if (y > 0) out.add(g, "ProfileDateTime", "%04d:%02d:%02d %02d:%02d:%02d".format(y, B.u16be(b, 26), B.u16be(b, 28), B.u16be(b, 30), B.u16be(b, 32), B.u16be(b, 34)))
        out.add(g, "ProfileFileSignature", "acsp")
        out.add(g, "PrimaryPlatform", PLATFORM[cc(40)] ?: cc(40).trim(), cc(40).trim())
        B.u32be(b, 44).let { out.add(g, "CMMFlags", if (it and 1L != 0L) "Embedded" else "Not Embedded", "$it") }
        out.add(g, "DeviceManufacturer", cc(48).trim())
        out.add(g, "DeviceModel", cc(52).trim())
        out.add(g, "DeviceAttributes", "${B.u32be(b, 56)} ${B.u32be(b, 60)}")
        out.add(g, "RenderingIntent", INTENT[B.u32be(b, 64).toInt()] ?: "${B.u32be(b, 64)}", "${B.u32be(b, 64)}")
        out.add(g, "ConnectionSpaceIlluminant", xyz(b, 68))
        out.add(g, "ProfileCreator", cc(80).trim())
        out.add(g, "ProfileID", b.copyOfRange(84, 100).joinToString("") { "%02x".format(it) }, b.copyOfRange(84, 100).joinToString(" ") { "${it.toInt() and 0xFF}" })
        val n = B.u32be(b, 128).toInt()
        for (k in 0 until minOf(n, 200)) {
            val e = 132 + k * 12
            if (e + 12 > b.size) break
            val sig = cc(e); val off = B.u32be(b, e + 4).toInt(); val len = B.u32be(b, e + 8).toInt()
            if (off < 0 || len <= 0 || off + len > b.size) continue
            when (sig) {
                "wtpt", "bkpt", "rXYZ", "gXYZ", "bXYZ", "lumi" -> if (B.ascii(b, off, 4) == "XYZ ") out.add("ICC_Profile", XYZ_NAMES.getValue(sig), xyz(b, off + 8))
                "tech" -> if (B.ascii(b, off, 4) == "sig ") B.ascii(b, off + 8, 4).trim().let { out.add("ICC_Profile", "Technology", TECH[it] ?: it, it) }
                "chad" -> if (B.ascii(b, off, 4) == "sf32" && len >= 44) out.add("ICC_Profile", "ChromaticAdaptation", (0 until 9).joinToString(" ") { ExifReader.num(B.u32be(b, off + 8 + it * 4).toInt() / 65536.0, 5) })
                "view" -> if (B.ascii(b, off, 4) == "view" && len >= 36) {
                    out.add("ICC-view", "ViewingCondIlluminant", xyz(b, off + 8)); out.add("ICC-view", "ViewingCondSurround", xyz(b, off + 20))
                    out.add("ICC-view", "ViewingCondIlluminantType", "${B.u32be(b, off + 32)}")
                }
                "meas" -> if (B.ascii(b, off, 4) == "meas" && len >= 36) {
                    out.add("ICC-meas", "MeasurementObserver", "${B.u32be(b, off + 8)}"); out.add("ICC-meas", "MeasurementBacking", xyz(b, off + 12))
                    out.add("ICC-meas", "MeasurementGeometry", "${B.u32be(b, off + 24)}"); out.add("ICC-meas", "MeasurementFlare", ExifReader.num(B.u32be(b, off + 28) / 65536.0, 5))
                    out.add("ICC-meas", "MeasurementIlluminant", "${B.u32be(b, off + 32)}")
                }
                else -> {
                    val name = when (sig) { "desc" -> "ProfileDescription"; "cprt" -> "ProfileCopyright"; "dmnd" -> "DeviceMfgDesc"; "dmdd" -> "DeviceModelDesc"; "vued" -> "ViewingCondDesc"; else -> null } ?: continue
                    text(b, off, len)?.let { out.add("ICC_Profile", name, it) }
                }
            }
        }
    }

    private val XYZ_NAMES = mapOf("wtpt" to "MediaWhitePoint", "bkpt" to "MediaBlackPoint", "rXYZ" to "RedMatrixColumn", "gXYZ" to "GreenMatrixColumn",
        "bXYZ" to "BlueMatrixColumn", "lumi" to "Luminance")
    private val TECH = mapOf("fscn" to "Film Scanner", "dcam" to "Digital Camera", "rscn" to "Reflective Scanner", "ijet" to "Ink Jet Printer",
        "twax" to "Thermal Wax Printer", "epho" to "Electrophotographic Printer", "esta" to "Electrostatic Printer", "dsub" to "Dye Sublimation Printer",
        "rpho" to "Photographic Paper Printer", "fprn" to "Film Writer", "vidm" to "Video Monitor", "vidc" to "Video Camera", "pjtv" to "Projection Television",
        "CRT" to "Cathode Ray Tube Display", "PMD" to "Passive Matrix Display", "AMD" to "Active Matrix Display", "KPCD" to "Photo CD",
        "imgs" to "Photo Image Setter", "grav" to "Gravure", "offs" to "Offset Lithography", "silk" to "Silkscreen", "flex" to "Flexography")

    /** Three s15Fixed16 numbers. */
    private fun xyz(b: ByteArray, o: Int) = (0 until 3).joinToString(" ") { ExifReader.num(B.u32be(b, o + it * 4).toInt() / 65536.0, 5) }

    /** A 'desc', 'text' or 'mluc' tag's text. */
    private fun text(b: ByteArray, off: Int, len: Int): String? = when (B.ascii(b, off, 4)) {
        "desc" -> { val n = B.u32be(b, off + 8).toInt(); if (n in 1..len) B.cstring(b, off + 12, n) else null }
        "text" -> B.cstring(b, off + 8, len - 8)
        "mluc" -> {
            val count = B.u32be(b, off + 8).toInt()
            if (count < 1) null else {
                // The first record (usually English).
                val l = B.u32be(b, off + 20).toInt(); val o = B.u32be(b, off + 24).toInt()
                if (o + l <= len) String(b, off + o, l, Charsets.UTF_16BE).trimEnd('\u0000').trim() else null
            }
        }
        else -> null
    }
}
