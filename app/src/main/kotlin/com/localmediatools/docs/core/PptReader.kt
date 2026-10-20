package com.localmediatools.docs.core

import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn

/**
 * PowerPoint 97–2003 presentations (.ppt, .pps): each slide's text where it sits on the slide (titles
 * and bodies as the slide's outline holds them, text boxes from its drawing), with bullets, and the
 * speaker notes; shapes with their fill and outline colours, pictures (PNG, JPEG, bitmaps), slide
 * backgrounds, and the master's logos and lines. Shapes in groups are placed through their groups.
 * The current version of each slide is found through the file's edit history, so text left in the
 * file from older edits isn't shown. Encrypted presentations say so.
 */
class PptReader private constructor(private val doc: ByteArray, private val user: ByteArray?, private val pictures: ByteArray?) {
    companion object {
        fun read(ole: Ole2): Deck {
            val doc = ole.stream("PowerPoint Document") ?: throw UnreadableException(tr("This PowerPoint file is damaged: its slides are missing."))
            val pics = try { ole.stream("Pictures") } catch (_: UnreadableException) { null }
            return PptReader(doc, ole.stream("Current User"), pics).read()
        }
    }

    private class Rec(val ver: Int, val instance: Int, val type: Int, val start: Int, val len: Int) { val end get() = start + len; val container get() = ver == 0xF }

    private fun rec(at: Int): Rec? {
        if (at < 0 || at + 8 > doc.size) return null
        val vi = le16(doc, at)
        val len = le32(doc, at + 4)
        if (len < 0 || at + 8 + len > doc.size) return null
        return Rec(vi and 0xF, vi ushr 4, le16(doc, at + 2), at + 8, len)
    }

    private fun children(r: Rec): List<Rec> {
        val out = ArrayList<Rec>()
        var p = r.start
        while (p + 8 <= r.end) { val c = rec(p) ?: break; out.add(c); p = c.end }
        return out
    }

    private fun persistMap(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        var editOffset = user?.let { u -> if (u.size >= 20) le32(u, 16) else null } ?: findLastUserEdit()
        val seen = HashSet<Int>()
        while (editOffset > 0 && seen.add(editOffset)) {
            val ue = rec(editOffset) ?: break
            if (ue.type != 0x0FF5) break
            if (docPersist == 0) docPersist = le32(doc, ue.start + 16)
            val dirOff = le32(doc, ue.start + 12)
            rec(dirOff)?.takeIf { it.type == 0x1772 }?.let { dir ->
                var p = dir.start
                while (p + 4 <= dir.end) {
                    val v = le32(doc, p); p += 4
                    val id = v and 0xFFFFF; val n = v ushr 20
                    for (k in 0 until n) { if (p + 4 > dir.end) break; map.putIfAbsent(id + k, le32(doc, p)); p += 4 }
                }
            }
            editOffset = le32(doc, ue.start + 8)
        }
        return map
    }

    private var docPersist = 0

    private fun findLastUserEdit(): Int {
        var p = 0; var last = -1
        while (true) { val r = rec(p) ?: break; if (r.type == 0x0FF5) last = p; p = r.end }
        return last
    }

    /** Text with its kind (title, body…) and where its character and paragraph formatting is (StyleTextPropAtom), -1 for none. */
    private class TextItem(val type: Int, val text: String, var style: Int = -1, var styleLen: Int = 0)

    /** How a shape looks: outline, fill and line colours, picture. */
    private class Look(
        val geometry: Geometry, val fill: Int, val line: Int, val lineWidth: Float, val image: String?, val crop: FloatArray?,
        val rotation: Float, val flipH: Boolean, val flipV: Boolean,
    )

    private class Placed(val x: Float, val y: Float, val w: Float, val h: Float, val text: String, val type: Int, val outlineRef: Int?, val look: Look, val placeholder: Boolean, val style: Int = -1, val styleLen: Int = 0)

    /** A slide's or master's drawing: its shapes and its background (colour, picture). */
    private class Drawing(val shapes: List<Placed>, val background: Int?, val backgroundImage: String?)

    // ------------------------------------------------------------------ pictures
    /** Pictures of the presentation (1-based "pib" in the shapes): where each one's record is. */
    private class Blip(val inDoc: Int, val inPictures: Int)
    private val blips = ArrayList<Blip>()
    private var missingPictures = 0

    private fun readBStore(group: Rec) {
        fun walk(r: Rec) {
            for (c in children(r)) when (c.type) {
                0xF000, 0xF001 -> walk(c)
                0xF007 -> {
                    val cbName = doc[c.start + 33].toInt() and 0xFF
                    val embedded = c.start + 36 + cbName
                    blips.add(Blip(if (c.len > 36 + cbName + 8) embedded else -1, le32(doc, c.start + 28)))
                }
            }
        }
        walk(group)
    }

    private fun picture(pib: Int): ByteArray? {
        val b = blips.getOrNull(pib - 1) ?: return null
        if (b.inDoc >= 0 && OfficeArt.isBlip(doc, b.inDoc)) return OfficeArt.blip(doc, b.inDoc)
        val p = pictures ?: return null
        return if (OfficeArt.isBlip(p, b.inPictures)) OfficeArt.blip(p, b.inPictures) else null
    }

    private val decodable = HashMap<Int, Boolean>()
    private fun pictureRef(pib: Int?): String? {
        if (pib == null || pib <= 0) return null
        val ok = decodable.getOrPut(pib) { picture(pib) != null }
        if (!ok) { missingPictures++; return null }
        return "ppt-pic:$pib"
    }

    // ------------------------------------------------------------------ colours
    /** The colour scheme in use (background, text, shadow, title text, fill, accents…). */
    private var scheme = IntArray(8) { listOf(0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFF000000, 0xFFBBE0E3, 0xFF333399, 0xFF009999, 0xFF99CC00)[it].toInt() }

    private fun schemeOf(r: Rec): IntArray? {
        val a = children(r).firstOrNull { it.type == 0x07F0 && it.instance == 1 && it.len >= 32 } ?: return null
        return IntArray(8) { k -> val o = a.start + k * 4; (0xFF shl 24) or ((doc[o].toInt() and 0xFF) shl 16) or ((doc[o + 1].toInt() and 0xFF) shl 8) or (doc[o + 2].toInt() and 0xFF) }
    }

    /** An OfficeArtCOLORREF: an RGB colour or an index into the colour scheme; null for system colours. */
    private fun color(v: Int?, alpha: Int = 255): Int? {
        v ?: return null
        val flags = v ushr 24
        val rgb = when {
            flags and 0x08 != 0 -> scheme.getOrNull(v and 0xFF) ?: return null
            flags and 0x10 != 0 -> return null
            else -> (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v ushr 16) and 0xFF)
        }
        return (rgb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    // ------------------------------------------------------------------ reading
    private fun read(): Deck {
        if (user != null && user.size >= 16 && le32(user, 12) == 0xF3D1C4DF.toInt()) {
            throw UnreadableException(tr("This file is protected with a password, which this app can't open."))
        }
        val persist = persistMap()
        val docRec = persist[docPersist]?.let { rec(it) }?.takeIf { it.type == 0x03E8 } ?: run {
            var p = 0; var found: Rec? = null
            while (true) { val r = rec(p) ?: break; if (r.type == 0x03E8) found = r; p = r.end }
            found
        } ?: throw UnreadableException(tr("This PowerPoint file is damaged: its slides are missing."))
        var w = 720f; var h = 540f
        val slideTexts = ArrayList<Pair<Int, MutableList<TextItem>>>() // persist id → outline texts
        val notesBySlideId = HashMap<Int, Int>() // notes slide id → its persist id
        val mastersById = HashMap<Int, Int>() // master slide id → its persist id
        for (c in children(docRec)) {
            when {
                c.type == 0x03E9 -> { w = le32(doc, c.start) / 8f; h = le32(doc, c.start + 4) / 8f }
                c.type == 0x040B -> try { readBStore(c) } catch (_: RuntimeException) { }
                c.type == 0x0FF0 && c.instance == 1 -> for (k in children(c)) if (k.type == 0x03F3) mastersById[le32(doc, k.start + 12)] = le32(doc, k.start)
                c.type == 0x0FF0 && (c.instance == 0 || c.instance == 2) -> {
                    var current: MutableList<TextItem>? = null
                    var type = 4
                    for (k in children(c)) when (k.type) {
                        0x03F3 -> {
                            val id = le32(doc, k.start); current = ArrayList()
                            if (c.instance == 0) slideTexts.add(id to current!!) else notesBySlideId[le32(doc, k.start + 12)] = id
                        }
                        0x0F9F -> type = le32(doc, k.start)
                        0x0FA0 -> current?.add(TextItem(type, String(doc, k.start, k.len, Charsets.UTF_16LE)))
                        0x0FA8 -> current?.add(TextItem(type, String(doc, k.start, k.len, Charsets.ISO_8859_1)))
                        0x0FA1 -> current?.lastOrNull()?.let { it.style = k.start; it.styleLen = k.len }
                    }
                }
            }
        }
        if (w <= 0 || h <= 0) { w = 720f; h = 540f }
        val defaultScheme = scheme.copyOf()
        val masterCache = HashMap<Int, Pair<IntArray?, Drawing?>>()
        fun master(persistId: Int): Pair<IntArray?, Drawing?> = masterCache.getOrPut(persistId) {
            val mr = persist[persistId]?.let { rec(it) }?.takeIf { it.type == 0x03F8 || it.type == 0x03EE } ?: return@getOrPut (null to null)
            val ms = schemeOf(mr)
            val saved = scheme
            if (ms != null) scheme = ms
            val d = children(mr).firstOrNull { it.type == 0x040C }?.let { try { drawing(it) } catch (_: RuntimeException) { null } }
            scheme = saved
            ms to d
        }
        val slides = ArrayList<Slide>()
        for ((id, outline) in slideTexts) {
            val sr = persist[id]?.let { rec(it) }?.takeIf { it.type == 0x03EE }
            var notesId: Int? = null
            var flags = 0x07
            var masterId = 0
            var drawingRec: Rec? = null
            if (sr != null) {
                for (c in children(sr)) {
                    if (c.type == 0x03EF) {
                        masterId = le32(doc, c.start + 12)
                        notesId = le32(doc, c.start + 16).takeIf { it != 0 }?.let { notesBySlideId[it] }
                        if (c.len >= 22) flags = le16(doc, c.start + 20)
                    }
                    if (c.type == 0x040C) drawingRec = c
                }
            }
            val (masterScheme, masterDrawing) = mastersById[masterId]?.let { master(it) } ?: (null to null)
            scheme = (if (flags and 0x02 != 0) masterScheme else sr?.let { schemeOf(it) } ?: masterScheme) ?: defaultScheme
            val drawing = drawingRec?.let { try { drawing(it) } catch (_: RuntimeException) { null } }
            val shapes = ArrayList<Shape>()
            // The master's own pictures, lines and text (not its placeholders) under the slide's shapes.
            if (flags and 0x01 != 0) masterDrawing?.shapes?.filter { !it.placeholder }?.forEach { p -> toShape(p, TextItem(p.type, p.text, p.style, p.styleLen))?.let { shapes.add(it) } }
            val usedOutline = HashSet<Int>()
            for (p in drawing?.shapes.orEmpty()) {
                val item = p.outlineRef?.let { outline.getOrNull(it)?.also { _ -> usedOutline.add(p.outlineRef) } } ?: TextItem(p.type, if (p.outlineRef != null) "" else p.text, p.style, p.styleLen)
                toShape(p, item)?.let { shapes.add(it) }
            }
            // Outline text with no shape of its own: title on top, the rest below.
            var y = 30f
            for ((k, item) in outline.withIndex()) {
                if (k in usedOutline || item.text.isBlank()) continue
                val title = item.type == 0 || item.type == 6
                val hh = if (title) h * 0.18f else h * 0.6f
                shapes.add(textShape(w * 0.06f, y, w * 0.88f, hh, TextItem(item.type, item.text, item.style, item.styleLen), null))
                y += hh + 10f
            }
            val bgDrawing = if (flags and 0x04 != 0 || drawing?.background == null && drawing?.backgroundImage == null) masterDrawing else drawing
            val background = bgDrawing?.background ?: scheme[0]
            val notes = notesId?.let { nid -> persist[nid]?.let { rec(it) } }?.let { nr -> notesText(nr) } ?: emptyList()
            slides.add(Slide(shapes, background, bgDrawing?.backgroundImage, notes))
        }
        if (slides.isEmpty()) throw UnreadableException(tr("The slides of this presentation can't be read."))
        val notes = if (missingPictures > 0) listOf(trn(missingPictures, "{0} picture in an older format isn't shown.", "{0} pictures in an older format aren't shown.")) else emptyList()
        return Deck(null, slides, w, h, Media { ref -> ref.removePrefix("ppt-pic:").toIntOrNull()?.let { picture(it) } }, notes)
    }

    private fun notesText(r: Rec): List<Paragraph> {
        val d = children(r).firstOrNull { it.type == 0x040C }?.let { try { drawing(it) } catch (_: RuntimeException) { null } } ?: return emptyList()
        return d.shapes.filter { it.type == 2 || it.type == 4 || it.type == 1 }.flatMap { para(TextItem(4, it.text, it.style, it.styleLen)) }.filter { !it.isEmpty() }
    }

    /** Maps a group's own coordinates to slide points. */
    private class Xf(val x: Float, val y: Float, val sx: Float, val sy: Float, val gx: Float, val gy: Float) {
        fun px(v: Float) = x + (v - gx) * sx
        fun py(v: Float) = y + (v - gy) * sy
    }

    /** Shapes of a slide's drawing: their place and look, their own text, or which outline text they show. */
    private fun drawing(r: Rec): Drawing {
        val out = ArrayList<Placed>()
        var bg: Int? = null; var bgImage: String? = null
        fun group(g: Rec, xf: Xf?, depth: Int) {
            if (depth > 40) return
            var inner: Xf? = xf
            for ((k, c) in children(g).withIndex()) when (c.type) {
                0xF004 -> {
                    val s = shape(c, xf) ?: continue
                    if (k == 0 && s.groupSpace != null) {
                        // The group's own record: where it sits, and the coordinates its members use.
                        val (gl, gt, gr, gb) = s.groupSpace
                        val rect = s.rect
                        inner = if (rect != null && gr != gl && gb != gt) Xf(rect[0], rect[1], (rect[2] - rect[0]) / (gr - gl), (rect[3] - rect[1]) / (gb - gt), gl, gt) else xf
                        continue
                    }
                    if (s.background) { bg = s.placed?.look?.fill?.takeIf { it != 0 } ?: bg; bgImage = s.placed?.look?.image ?: bgImage; continue }
                    s.placed?.let { out.add(it) }
                }
                0xF003 -> group(c, inner, depth + 1)
            }
        }
        fun walk(c: Rec) {
            when (c.type) {
                0xF002 -> for (k in children(c)) walk(k)
                0xF003 -> group(c, null, 0)
                0xF004 -> shape(c, null)?.let { s -> if (s.background) { bg = s.placed?.look?.fill?.takeIf { it != 0 } ?: bg; bgImage = s.placed?.look?.image ?: bgImage } else s.placed?.let { out.add(it) } }
                else -> if (c.container) for (k in children(c)) walk(k)
            }
        }
        for (c in children(r)) walk(c)
        return Drawing(out, bg, bgImage)
    }

    private class Read(val placed: Placed?, val rect: FloatArray?, val groupSpace: FloatArray?, val background: Boolean)

    private fun shape(sp: Rec, xf: Xf?): Read? {
        var rect: FloatArray? = null
        var groupSpace: FloatArray? = null
        val text = StringBuilder(); var type = 4; var ref: Int? = null; var style = -1; var styleLen = 0
        var shapeType = 1; var flags = 0
        var placeholder = false
        val values = HashMap<Int, Int>(); val complex = HashMap<Int, ByteArray>()
        fun walk(c: Rec) {
            when (c.type) {
                0xF00A -> if (c.len >= 8) { shapeType = c.instance; flags = le32(doc, c.start + 4) }
                0xF00B, 0xF122 -> OfficeArt.props(doc, c.start, c.len, c.instance, values, complex)
                0xF009 -> if (c.len >= 16) groupSpace = floatArrayOf(le32(doc, c.start).toFloat(), le32(doc, c.start + 4).toFloat(), le32(doc, c.start + 8).toFloat(), le32(doc, c.start + 12).toFloat())
                0xF010 -> rect = when {
                    c.len >= 16 -> floatArrayOf(le32(doc, c.start + 4) / 8f, le32(doc, c.start) / 8f, le32(doc, c.start + 8) / 8f, le32(doc, c.start + 12) / 8f)
                    c.len >= 8 -> floatArrayOf(le16s(c.start + 2) / 8f, le16s(c.start) / 8f, le16s(c.start + 4) / 8f, le16s(c.start + 6) / 8f)
                    else -> rect
                }
                0xF00F -> if (c.len >= 16 && xf != null) rect = floatArrayOf(xf.px(le32(doc, c.start).toFloat()), xf.py(le32(doc, c.start + 4).toFloat()), xf.px(le32(doc, c.start + 8).toFloat()), xf.py(le32(doc, c.start + 12).toFloat()))
                0x0BC3 -> placeholder = true
                0x0F9F -> type = le32(doc, c.start)
                0x0FA0 -> text.append(String(doc, c.start, c.len, Charsets.UTF_16LE))
                0x0FA8 -> text.append(String(doc, c.start, c.len, Charsets.ISO_8859_1))
                0x0FA1 -> { style = c.start; styleLen = c.len }
                0x0F9E -> ref = le32(doc, c.start)
                else -> if (c.container || c.type == 0xF00D || c.type == 0xF011) for (k in children(c)) walk(k)
            }
        }
        for (c in children(sp)) walk(c)
        val props = OfficeArt.Props(values, complex)
        val background = flags and 0x400 != 0
        if (flags and 0x08 != 0) return null // deleted
        val look = look(props, shapeType, flags)
        val r = rect
        if (background) return Read(Placed(0f, 0f, 0f, 0f, "", type, null, look, false), null, null, true)
        if (r == null) return if (groupSpace != null) Read(null, null, groupSpace, false) else null
        val x = minOf(r[0], r[2]); val y = minOf(r[1], r[3])
        val placed = Placed(x, y, (maxOf(r[0], r[2]) - x).coerceAtLeast(0f), (maxOf(r[1], r[3]) - y).coerceAtLeast(0f), text.toString(), type, ref, look, placeholder, style, styleLen)
        return Read(placed, floatArrayOf(x, y, x + placed.w, y + placed.h), groupSpace, false)
    }

    private fun look(p: OfficeArt.Props, shapeType: Int, flags: Int): Look {
        val geometry = when (shapeType) {
            1, 75, 202 -> Geometry.RECT; 2 -> Geometry.ROUND_RECT; 3 -> Geometry.ELLIPSE; 4 -> Geometry.DIAMOND
            5 -> Geometry.TRIANGLE; 13 -> Geometry.RIGHT_ARROW; 20, 32 -> Geometry.LINE
            else -> Geometry.OTHER
        }
        val opacity = p[0x0182]?.let { (it / 65536f * 255).toInt() } ?: 255
        val filled = p.flag(0x01BF, 0x10)
        val fillType = p[0x0180] ?: 0
        var image = pictureRef(p[0x0104])
        if (image == null && (fillType == 2 || fillType == 3) && filled != false) image = pictureRef(p[0x0186])
        val fillColor = color(p[0x0181], opacity)
        val fill = when {
            filled == false || fillType == 2 || fillType == 3 -> 0
            fillColor != null -> fillColor
            filled == true -> 0xFFFFFFFF.toInt()
            else -> 0
        }
        val lined = p.flag(0x01FF, 0x08)
        val lineColor = color(p[0x01C0])
        val line = when {
            lined == false -> 0
            lineColor != null -> lineColor
            lined == true || geometry == Geometry.LINE -> 0xFF000000.toInt()
            else -> 0
        }
        val lineWidth = (p[0x01CB] ?: 9525) / 12700f
        fun crop(id: Int) = (p[id] ?: 0) / 65536f
        val crop = if (image != null && listOf(0x0100, 0x0101, 0x0102, 0x0103).any { p[it] != null }) floatArrayOf(crop(0x0102), crop(0x0100), crop(0x0103), crop(0x0101)) else null
        return Look(geometry, fill, line, lineWidth, image, crop, (p[0x0004] ?: 0) / 65536f, flags and 0x40 != 0, flags and 0x80 != 0)
    }

    private fun le16s(o: Int): Int = le16(doc, o).toShort().toInt()

    private class Pf(val align: Int?, val bullet: Boolean?, val level: Int)
    private class Cf(val bold: Boolean?, val italic: Boolean?, val underline: Boolean?, val size: Float?, val color: Int?)

    /** The paragraph and character runs of a StyleTextPropAtom for [length] characters: (characters, format). */
    private fun runs(at: Int, len: Int, length: Int): Pair<List<Pair<Int, Pf>>, List<Pair<Int, Cf>>> {
        val pfs = ArrayList<Pair<Int, Pf>>(); val cfs = ArrayList<Pair<Int, Cf>>()
        if (at < 0) return pfs to cfs
        val end = at + len
        var p = at
        fun u16(): Int { val v = le16(doc, p); p += 2; return v }
        fun u32(): Int { val v = le32(doc, p); p += 4; return v }
        try {
            var covered = 0
            while (covered <= length && p + 10 <= end) {
                val count = u32(); val level = u16(); val m = u32()
                var bullet: Boolean? = null; var align: Int? = null
                if (m and 0xF != 0) { val f = u16(); if (m and 1 != 0) bullet = f and 1 != 0 }
                if (m and 0x80 != 0) u16()
                if (m and 0x10 != 0) u16()
                if (m and 0x40 != 0) u16()
                if (m and 0x20 != 0) u32()
                if (m and 0x800 != 0) align = u16()
                for (bit in intArrayOf(0x1000, 0x2000, 0x4000, 0x100, 0x400, 0x8000)) if (m and bit != 0) u16()
                if (m and 0x100000 != 0) { val n = u16(); p += n * 4 }
                if (m and 0x10000 != 0) u16()
                if (m and 0xE0000 != 0) u16()
                if (m and 0x200000 != 0) u16()
                if (count <= 0) break
                pfs.add(count to Pf(align, bullet, level)); covered += count
            }
            covered = 0
            while (covered <= length && p + 8 <= end) {
                val count = u32(); val m = u32()
                var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null; var size: Float? = null; var color: Int? = null
                // Bold, italic, underline, shadow, emboss… share one field.
                if (m and 0x3EB7 != 0) {
                    val st = u16()
                    if (m and 1 != 0) bold = st and 1 != 0
                    if (m and 2 != 0) italic = st and 2 != 0
                    if (m and 4 != 0) underline = st and 4 != 0
                }
                if (m and 0x10000 != 0) u16()
                if (m and 0x200000 != 0) u16()
                if (m and 0x400000 != 0) u16()
                if (m and 0x800000 != 0) u16()
                if (m and 0x20000 != 0) size = u16().toFloat()
                if (m and 0x40000 != 0) {
                    val r = doc[p].toInt() and 0xFF; val g = doc[p + 1].toInt() and 0xFF; val b = doc[p + 2].toInt() and 0xFF; val idx = doc[p + 3].toInt() and 0xFF
                    p += 4
                    color = if (idx == 0xFE) (0xFF shl 24) or (r shl 16) or (g shl 8) or b else scheme.getOrNull(idx)
                }
                if (m and 0x80000 != 0) u16()
                if (m and 0x100000 != 0) u32()
                if (m and 0x1000000 != 0) u16()
                if (m and 0x2000000 != 0) u16()
                if (m and 0x4000000 != 0) u32()
                if (count <= 0) break
                cfs.add(count to Cf(bold, italic, underline, size, color)); covered += count
            }
        } catch (_: RuntimeException) { }
        return pfs to cfs
    }

    private fun para(item: TextItem): List<Paragraph> {
        val text = item.text; val type = item.type
        val title = type == 0 || type == 6
        val size = when (type) { 0 -> 36f; 6 -> 40f; 5 -> 28f; 1 -> 24f; else -> 18f }
        val color = if (title) scheme[3] else scheme[1]
        val base = RunStyle(bold = title, sizePt = size, color = color)
        val (pfs, cfs) = runs(item.style, item.styleLen, text.length)
        fun <T> at(list: List<Pair<Int, T>>, pos: Int): T? { var n = 0; for ((c, v) in list) { n += c; if (pos < n) return v }; return list.lastOrNull()?.second }
        val out = ArrayList<Paragraph>()
        var start = 0
        while (start <= text.length) {
            var end = start
            while (end < text.length && text[end] != '\r') end++
            val pf = at(pfs, start)
            // Characters of the paragraph, split where their formatting changes.
            val spans = ArrayList<Span>()
            var i = start
            while (i < end) {
                val cf = at(cfs, i)
                var j = i + 1
                while (j < end && at(cfs, j) === cf) j++
                val st = if (cf == null) base else base.copy(bold = cf.bold ?: base.bold, italic = cf.italic ?: false, underline = cf.underline ?: false, sizePt = cf.size ?: base.sizePt, color = cf.color ?: base.color)
                spans.add(Span(text.substring(i, j).replace('\u000B', '\n').replace('\t', ' '), st))
                i = j
            }
            if (spans.isEmpty()) spans.add(Span("", base))
            val line = text.substring(start, end)
            val bullet = (pf?.bullet ?: (type == 1)) && line.isNotBlank()
            val align = when (pf?.align) { 1 -> Align.CENTER; 2 -> Align.END; 3 -> Align.JUSTIFY; 0 -> Align.START; else -> if (title || type == 5 || type == 6) Align.CENTER else Align.START }
            val level = pf?.level ?: 0
            out.add(Paragraph(spans, align = align, listLabel = if (bullet) "•" else null, indentPt = if (bullet) 20f + 27f * level else 27f * level))
            start = end + 1
            if (end >= text.length) break
        }
        return out
    }

    private fun textShape(x: Float, y: Float, w: Float, h: Float, item: TextItem, look: Look?): Shape {
        val paras = para(item)
        val type = item.type
        val title = type == 0 || type == 6
        val body = TextBody(paras, anchor = if (title) Anchor.MIDDLE else Anchor.TOP, defaultSizePt = paras.firstOrNull()?.spans?.firstOrNull()?.style?.sizePt ?: 18f, defaultColor = if (title) scheme[3] else scheme[1])
        return if (look == null) Shape(x, y, w, h, text = body)
        else Shape(x, y, w, h, look.rotation, look.flipH, look.flipV, look.geometry, look.fill, look.line, look.lineWidth, body, look.image, look.crop)
    }

    /** A placed shape as the slide draws it; null when it shows nothing. */
    private fun toShape(p: Placed, item: TextItem): Shape? {
        val text = item.text
        val l = p.look
        val visible = l.fill != 0 || l.line != 0 || l.image != null
        if (text.isBlank() && !visible) return null
        if (text.isBlank()) return Shape(p.x, p.y, p.w, p.h, l.rotation, l.flipH, l.flipV, l.geometry, l.fill, l.line, l.lineWidth, null, l.image, l.crop)
        return textShape(p.x, p.y, p.w.coerceAtLeast(10f), p.h.coerceAtLeast(10f), item, l)
    }
}
