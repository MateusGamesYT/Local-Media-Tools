package com.localmediatools.meta.core

import com.localmediatools.docs.core.XmlReader
import com.localmediatools.docs.core.XmlReader.Event

/**
 * XMP (the XML metadata Adobe and others write into photos, videos and PDFs): every property, named
 * as ExifTool names them ("XMP-dc:Creator", "XMP-photoshop:City"); lists are joined, structures are
 * flattened into names ("CreatorContactInfoCiAdrCity"), GPS and dates read the way EXIF's do.
 */
object XmpReader {
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    /** ExifTool's short names for the usual namespaces (others are named from their address). */
    private val PREFIX = mapOf(
        "http://purl.org/dc/elements/1.1/" to "dc", "http://ns.adobe.com/xap/1.0/" to "xmp", "http://ns.adobe.com/xap/1.0/mm/" to "xmpMM",
        "http://ns.adobe.com/xap/1.0/rights/" to "xmpRights", "http://ns.adobe.com/xap/1.0/bj/" to "xmpBJ", "http://ns.adobe.com/xap/1.0/t/pg/" to "xmpTPg",
        "http://ns.adobe.com/xmp/1.0/DynamicMedia/" to "xmpDM", "http://ns.adobe.com/xmp/note/" to "xmpNote", "http://ns.adobe.com/photoshop/1.0/" to "photoshop",
        "http://ns.adobe.com/exif/1.0/" to "exif", "http://cipa.jp/exif/1.0/" to "exifEX", "http://ns.adobe.com/tiff/1.0/" to "tiff",
        "http://ns.adobe.com/exif/1.0/aux/" to "aux", "http://ns.adobe.com/camera-raw-settings/1.0/" to "crs", "http://ns.adobe.com/lightroom/1.0/" to "lr",
        "http://ns.adobe.com/pdf/1.3/" to "pdf", "http://ns.adobe.com/pdfx/1.3/" to "pdfx", "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/" to "iptcCore",
        "http://iptc.org/std/Iptc4xmpExt/2008-02-29/" to "iptcExt", "http://ns.useplus.org/ldf/xmp/1.0/" to "plus",
        "http://ns.google.com/photos/1.0/camera/" to "GCamera", "http://ns.google.com/photos/1.0/panorama/" to "GPano",
        "http://ns.google.com/photos/1.0/image/" to "GImage", "http://ns.google.com/photos/1.0/audio/" to "GAudio", "http://ns.google.com/photos/1.0/depthmap/" to "GDepth",
        "http://ns.google.com/photos/1.0/container/" to "GContainer", "http://ns.microsoft.com/photo/1.0/" to "MicrosoftPhoto",
        "http://ns.microsoft.com/photo/1.2/" to "MP", "http://ns.adobe.com/xap/1.0/g/img/" to "xmpGImg", "http://ns.adobe.com/photoshop/1.0/camera-profile" to "crs",
        "http://ns.apple.com/adjustment-settings/1.0/" to "AppleAdjust", "http://ns.apple.com/faceinfo/1.0/" to "apple-fi",
        "http://www.metadataworkinggroup.com/schemas/regions/" to "mwg-rs", "http://www.metadataworkinggroup.com/schemas/keywords/" to "mwg-kw",
        "http://ns.adobe.com/xap/1.0/sType/ResourceEvent#" to "stEvt", "http://ns.adobe.com/xap/1.0/sType/ResourceRef#" to "stRef",
        "http://ns.adobe.com/photoshop/1.0/panorama-profile" to "GPano", "http://purl.org/dc/terms/" to "dcterms", "http://prismstandard.org/namespaces/basic/2.0/" to "prism",
        "http://ns.adobe.com/exif/1.0/aux/" to "aux", "http://www.digikam.org/ns/1.0/" to "digiKam", "http://ns.iview-multimedia.com/mediapro/1.0/" to "mediapro",
        "http://ns.acdsee.com/iptc/1.0/" to "acdsee", "http://ns.adobe.com/DICOM/" to "DICOM", "http://ns.adobe.com/creatorAtom/1.0/" to "creatorAtom",
        "http://www.w3.org/2003/12/exif/ns#" to "exif", "http://ns.adobe.com/hdr-gain-map/1.0/" to "hdrgm", "http://ns.adobe.com/hdr-metadata/1.0/" to "hdrm",
    )

    /** Reads the XMP packet in [xml] into [out]. */
    fun read(xml: ByteArray, out: Fields) {
        val text = packet(xml) ?: return
        try {
            val x = XmlReader.of(text)
            while (x.next() != Event.EOF) {
                if (x.event == Event.START && (x.local == "xmpmeta" || x.local == "xapmeta")) x.attr("xmptk")?.let { out.add("XMP-x", "XMPToolkit", it, privacy = Privacy.SOFTWARE) }
                if (x.event == Event.START && x.ns == RDF && x.local == "Description") {
                    x.attr(RDF, "about")?.takeIf { it.isNotBlank() }?.let { out.add("XMP-rdf", "About", it, privacy = Privacy.IDENTIFIER) }
                    description(x, out, "")
                }
            }
        } catch (e: Exception) {
            out.note("The XMP metadata is damaged: only part of it was read.")
        }
    }

    /** The packet's XML: from the first "<" of x:xmpmeta or rdf:RDF to its end (packets can be padded or wrapped). */
    private fun packet(b: ByteArray): String? {
        val s = when {
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, Charsets.UTF_16LE)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, Charsets.UTF_16BE)
            // UTF-16 without a byte-order mark: every other byte of the "<?xpacket" is zero.
            b.size >= 4 && b[0] == 0.toByte() && b[1] != 0.toByte() && b[2] == 0.toByte() -> String(b, Charsets.UTF_16BE)
            b.size >= 4 && b[0] != 0.toByte() && b[1] == 0.toByte() && b[3] == 0.toByte() -> String(b, Charsets.UTF_16LE)
            else -> String(b, Charsets.UTF_8)
        }
        val start = listOf(s.indexOf("<x:xmpmeta"), s.indexOf("<x:xapmeta"), s.indexOf("<rdf:RDF")).filter { it >= 0 }.minOrNull() ?: return null
        val end = listOf("</x:xmpmeta>", "</x:xapmeta>", "</rdf:RDF>").mapNotNull { e -> s.lastIndexOf(e).takeIf { it >= 0 }?.plus(e.length) }.maxOrNull() ?: s.length
        return s.substring(start, maxOf(start, end))
    }

    private fun group(ns: String): String = "XMP-" + (PREFIX[ns] ?: ns.trimEnd('/', '#').substringAfterLast('/').ifEmpty { "xmp" })

    /** ExifTool's tag name: the local name with its first letter upper-cased. */
    private fun tagName(local: String) = local.replaceFirstChar { it.uppercaseChar() }

    /** An rdf:Description: its properties as attributes and as child elements. [prefix] names a structure it is inside. */
    private fun description(x: XmlReader, out: Fields, prefix: String, groupOf: String? = null) {
        for (i in 0 until x.attributeCount) {
            val ns = x.attributeNs(i)
            if (ns.isEmpty() || ns == RDF || ns == "http://www.w3.org/XML/1998/namespace" || ns.startsWith("http://www.w3.org/2000/xmlns")) continue
            add(out, groupOf ?: group(ns), prefix + tagName(x.attributeLocal(i)), listOf(x.attributeValue(i)))
        }
        x.children { c -> if (c.event == Event.START) property(c, out, prefix, groupOf) }
    }

    /** One property element: plain text, a list, or a structure. */
    private fun property(x: XmlReader, out: Fields, prefix: String, groupOf: String?) {
        val g = groupOf ?: group(x.ns)
        val name = prefix + tagName(x.local)
        // A property given as an attribute-only resource: <ns:prop rdf:resource="…"/> or with structure attributes.
        x.attr(RDF, "resource")?.let { add(out, g, name, listOf(it)); x.skip(); return }
        if (x.attr(RDF, "parseType") == "Resource") { description(x, out, name, g); return }
        val structAttrs = (0 until x.attributeCount).any { val n = x.attributeNs(it); n.isNotEmpty() && n != RDF && n != "http://www.w3.org/XML/1998/namespace" }
        if (structAttrs) { description(x, out, name, g); return }
        val values = ArrayList<String>()
        val text = StringBuilder()
        x.children { c ->
            when {
                c.event == Event.TEXT -> text.append(c.text)
                c.event == Event.START && c.ns == RDF && (c.local == "Bag" || c.local == "Seq" || c.local == "Alt") -> c.children { li ->
                    if (li.event == Event.START && li.ns == RDF && li.local == "li") {
                        if (li.attr(RDF, "parseType") == "Resource") description(li, out, name, g)
                        else {
                            var nested = false
                            val sb = StringBuilder()
                            li.children { v ->
                                when {
                                    v.event == Event.TEXT -> sb.append(v.text)
                                    v.event == Event.START && v.ns == RDF && v.local == "Description" -> { nested = true; description(v, out, name, g) }
                                    v.event == Event.START -> { nested = true; property(v, out, name, g) }
                                }
                            }
                            if (!nested) values.add(sb.toString().trim())
                        }
                    } else if (li.event == Event.START) li.skip()
                }
                c.event == Event.START && c.ns == RDF && c.local == "Description" -> description(c, out, name, g)
                c.event == Event.START -> property(c, out, name, g)
            }
        }
        if (values.isEmpty() && text.isNotBlank()) values.add(text.toString().trim())
        if (values.isNotEmpty()) add(out, g, name, values)
    }

    private fun add(out: Fields, group: String, name: String, values: List<String>) {
        val vs = values.filter { it.isNotEmpty() }
        if (vs.isEmpty()) return
        val joined = vs.joinToString(", ")
        when {
            // Positions: "40,42.768N" or "40,42,46.08N" → signed decimal degrees.
            (name.endsWith("GPSLatitude") || name.endsWith("GPSLongitude") || name.endsWith("GPSDestLatitude") || name.endsWith("GPSDestLongitude")) && vs.size == 1 -> {
                val d = xmpDegrees(vs[0])
                if (d != null) out.add(group, name, ExifReader.dms(d, name.contains("Latitude")), ExifReader.num(d)) else out.add(group, name, joined)
            }
            looksLikeDate(joined) && vs.size == 1 -> out.add(group, name, joined, exifDate(joined))
            // Rationals ("18/1", "0/0" for "not known") as numbers, as EXIF's are read.
            vs.all { RATIONAL.matches(it) } -> vs.joinToString(" ") { r ->
                val (a, b) = r.split('/').map { it.trim().toDouble() }
                ExifReader.num(if (b == 0.0) (if (a == 0.0) Double.NaN else Double.POSITIVE_INFINITY) else a / b)
            }.let { out.add(group, name, it, it) }
            else -> out.add(group, name, joined)
        }
    }

    fun xmpDegrees(s: String): Double? {
        val m = Regex("""^\s*(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)(?:,(\d+(?:\.\d+)?))?\s*([NSEW])\s*$""").find(s) ?: return s.toDoubleOrNull()
        val (d, mi, se, side) = m.destructured
        val v = d.toDouble() + mi.toDouble() / 60 + (se.toDoubleOrNull() ?: 0.0) / 3600
        return if (side == "S" || side == "W") -v else v
    }

    private val RATIONAL = Regex("""^\s*-?\d+\s*/\s*\d+\s*$""")
    private val DATE = Regex("""^\d{4}-\d{2}(-\d{2}(T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})?)?)?$""")
    private fun looksLikeDate(s: String) = DATE.matches(s)

    /** "2017-05-08T15:31:25+02:00" → "2017:05:08 15:31:25+02:00", as ExifTool shows XMP dates. */
    fun exifDate(s: String): String {
        val t = s.replace('T', ' ')
        val date = t.substringBefore(' ').replace('-', ':')
        return if (t.contains(' ')) "$date ${t.substringAfter(' ')}" else date
    }
}
