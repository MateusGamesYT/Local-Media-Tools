package com.localmediatools.docs.core

import com.localmediatools.docs.core.XmlReader.Event
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn

/**
 * PowerPoint presentations (.pptx, .ppsx, .potx, .pptm): each slide with its shapes where they are
 * (rectangles, ellipses, lines…, their fills and outlines), pictures (cropped as on the slide),
 * text with the sizes, colours, alignment, bullets and numbering it gets from its layout and master,
 * tables, groups, backgrounds and theme colours; and the speaker notes. Charts, diagrams and media
 * are shown as labelled boxes; animations and transitions are left out.
 */
class PptxReader private constructor(private val parts: Parts) {
    companion object {
        fun read(parts: Parts): Deck = PptxReader(parts).read()
        private const val EMU = 12700f
    }

    private var charts = 0
    private var diagrams = 0
    private var media = 0

    // ------------------------------------------------------------------ inherited properties
    private class Lvl(
        var sz: Float? = null, var bold: Boolean? = null, var italic: Boolean? = null, var underline: Boolean? = null, var color: Int? = null,
        var align: Align? = null, var marL: Float? = null, var indent: Float? = null, var bullet: String? = null, var autoNum: String? = null,
        var bulletColor: Int? = null, var spcBefore: Float? = null, var spcAfter: Float? = null, var font: String? = null,
    ) {
        fun over(o: Lvl?): Lvl = if (o == null) this else Lvl(
            o.sz ?: sz, o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.color ?: color, o.align ?: align, o.marL ?: marL,
            o.indent ?: indent, o.bullet ?: bullet, if (o.bullet != null && o.autoNum == null) null else o.autoNum ?: autoNum, o.bulletColor ?: bulletColor,
            o.spcBefore ?: spcBefore, o.spcAfter ?: spcAfter, o.font ?: font,
        )
    }

    private class BodyPr(var l: Float? = null, var t: Float? = null, var r: Float? = null, var b: Float? = null, var anchor: Anchor? = null, var wrap: Boolean? = null, var fontScale: Float? = null) {
        fun over(o: BodyPr?) = if (o == null) this else BodyPr(o.l ?: l, o.t ?: t, o.r ?: r, o.b ?: b, o.anchor ?: anchor, o.wrap ?: wrap, o.fontScale ?: fontScale)
    }

    /** What a placeholder passes on: position, text levels and body settings. */
    private class PhInfo(val box: FloatArray?, val levels: Array<Lvl?>, val body: BodyPr?, val type: String)

    private class Master(
        val clrMap: Map<String, String>, val theme: Theme, val title: Array<Lvl?>, val body: Array<Lvl?>, val other: Array<Lvl?>,
        val placeholders: List<Pair<Pair<String?, String?>, PhInfo>>, val shapes: List<Shape>, val bg: Pair<Int?, String?>,
    )

    private class Layout(val master: Master, val placeholders: List<Pair<Pair<String?, String?>, PhInfo>>, val shapes: List<Shape>, val bg: Pair<Int?, String?>, val showMasterShapes: Boolean)

    /** The context shapes are read in: colours, and the placeholders they may inherit from. */
    private inner class Ctx(val part: String, val clrMap: Map<String, String>, val theme: Theme, val layout: Layout?, val master: Master?) {
        val rels by lazy { parts.rels(part) }
    }

    private val masters = HashMap<String, Master>()
    private val layouts = HashMap<String, Layout>()
    private var defaultText = arrayOfNulls<Lvl>(9)

    private fun read(): Deck {
        val main = parts.mainPart()?.takeIf { parts.has(it) } ?: "ppt/presentation.xml"
        val x = parts.xml(main) ?: throw UnreadableException(tr("This isn't a PowerPoint presentation: its main part is missing."))
        val rels = parts.rels(main)
        var w = 720f; var h = 540f
        val slideParts = ArrayList<String>()
        while (x.next() != Event.EOF) {
            if (x.event != Event.START) continue
            when (x.local) {
                "sldSz" -> { w = (x.attr("cx")?.toLongOrNull() ?: 9144000) / EMU; h = (x.attr("cy")?.toLongOrNull() ?: 6858000) / EMU }
                "sldId" -> x.attr(Ns.R, "id")?.let { rels[it] }?.let { slideParts.add(it.target) }
                "defaultTextStyle" -> defaultText = levels(x, Ctx(main, DEFAULT_MAP, Theme.DEFAULT, null, null))
            }
        }
        val slides = slideParts.mapNotNull { p -> try { slide(p) } catch (e: UnreadableException) { throw e } catch (e: Exception) { null } }
        if (slides.isEmpty() && slideParts.isNotEmpty()) throw UnreadableException(tr("The slides of this presentation can't be read."))
        val notes = ArrayList<String>()
        if (charts > 0) notes.add(trn(charts, "{0} chart is shown as a box.", "{0} charts are shown as boxes."))
        if (diagrams > 0) notes.add(trn(diagrams, "{0} diagram is shown as a box.", "{0} diagrams are shown as boxes."))
        if (media > 0) notes.add(trn(media, "{0} video or sound isn't played.", "{0} videos or sounds aren't played."))
        return Deck(coreTitle(parts), slides, w, h, Media { ref -> parts.bytes(ref, 64L shl 20) }, notes)
    }

    // ------------------------------------------------------------------ masters and layouts
    private val DEFAULT_MAP = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")

    private fun master(part: String): Master = masters.getOrPut(part) {
        val rels = parts.rels(part)
        val theme = Theme.read(parts, rels.values.firstOrNull { it.type.endsWith("/theme") }?.target)
        var clrMap: Map<String, String> = DEFAULT_MAP
        // The colour map is read first so the rest can use it.
        parts.xml(part)?.let { x -> while (x.next() != Event.EOF) if (x.event == Event.START && x.local == "clrMap") { clrMap = attrsMap(x); break } }
        val ctx = Ctx(part, clrMap, theme, null, null)
        var title = arrayOfNulls<Lvl>(9); var body = arrayOfNulls<Lvl>(9); var other = arrayOfNulls<Lvl>(9)
        val phs = ArrayList<Pair<Pair<String?, String?>, PhInfo>>()
        val shapes = ArrayList<Shape>()
        var bg: Pair<Int?, String?> = null to null
        parts.xml(part)?.let { x ->
            while (x.next() != Event.EOF) {
                if (x.event != Event.START) continue
                when {
                    x.ns == Ns.P && x.local == "bg" -> bg = background(x, ctx)
                    x.ns == Ns.P && x.local == "spTree" -> tree(x, ctx, shapes, phs, null, decorativeOnly = true)
                    x.ns == Ns.P && x.local == "titleStyle" -> title = levels(x, ctx)
                    x.ns == Ns.P && x.local == "bodyStyle" -> body = levels(x, ctx)
                    x.ns == Ns.P && x.local == "otherStyle" -> other = levels(x, ctx)
                }
            }
        }
        Master(clrMap, theme, title, body, other, phs, shapes, bg)
    }

    private fun layout(part: String): Layout = layouts.getOrPut(part) {
        val rels = parts.rels(part)
        val m = master(rels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target ?: "")
        var map = m.clrMap
        var showMaster = true
        parts.xml(part)?.let { x ->
            while (x.next() != Event.EOF) {
                if (x.event == Event.START && x.ns == Ns.P && x.local == "sldLayout") showMaster = x.attr("showMasterSp") != "0"
                if (x.event == Event.START && x.local == "overrideClrMapping") { map = attrsMap(x); break }
                if (x.event == Event.START && x.local == "cSld") break
            }
        }
        val ctx = Ctx(part, map, m.theme, null, m)
        val phs = ArrayList<Pair<Pair<String?, String?>, PhInfo>>()
        val shapes = ArrayList<Shape>()
        var bg: Pair<Int?, String?> = null to null
        parts.xml(part)?.let { x ->
            while (x.next() != Event.EOF) {
                if (x.event != Event.START) continue
                if (x.ns == Ns.P && x.local == "bg") bg = background(x, ctx)
                if (x.ns == Ns.P && x.local == "spTree") { tree(x, ctx, shapes, phs, null, decorativeOnly = true); break }
            }
        }
        Layout(m, phs, shapes, bg, showMaster)
    }

    private fun attrsMap(x: XmlReader): Map<String, String> {
        val m = HashMap<String, String>()
        for (i in 0 until x.attributeCount) m[x.attributeLocal(i)] = x.attributeValue(i)
        return m
    }

    private fun findPh(list: List<Pair<Pair<String?, String?>, PhInfo>>, type: String?, idx: String?): PhInfo? {
        val t = normType(type)
        if (idx != null) list.firstOrNull { it.first.second == idx && (type == null || normType(it.first.first) == t) }?.let { return it.second }
        list.firstOrNull { normType(it.first.first) == t }?.let { return it.second }
        if (idx != null) list.firstOrNull { it.first.second == idx }?.let { return it.second }
        return null
    }

    private fun normType(t: String?) = when (t) { null, "obj" -> "body"; "ctrTitle" -> "title"; else -> t }

    // ------------------------------------------------------------------ slides
    private fun slide(part: String): Slide? {
        val rels = parts.rels(part)
        val layout = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target?.let { layout(it) }
        val master = layout?.master
        var map = master?.clrMap ?: DEFAULT_MAP
        var hidden = false
        parts.xml(part)?.let { x ->
            while (x.next() != Event.EOF) {
                if (x.event == Event.START && x.ns == Ns.P && x.local == "sld") hidden = x.attr("show") == "0"
                if (x.event == Event.START && x.local == "overrideClrMapping") { map = attrsMap(x); break }
                if (x.event == Event.START && x.local == "cSld") break
            }
        }
        val theme = master?.theme ?: Theme.DEFAULT
        val ctx = Ctx(part, map, theme, layout, master)
        val shapes = ArrayList<Shape>()
        if (layout?.showMasterShapes != false) master?.shapes?.let { shapes.addAll(it) }
        layout?.shapes?.let { shapes.addAll(it) }
        var bg: Pair<Int?, String?> = null to null
        val x = parts.xml(part) ?: return null
        while (x.next() != Event.EOF) {
            if (x.event != Event.START) continue
            if (x.ns == Ns.P && x.local == "bg") bg = background(x, ctx)
            if (x.ns == Ns.P && x.local == "spTree") tree(x, ctx, shapes, null, null, decorativeOnly = false)
        }
        val background = listOf(bg, layout?.bg, master?.bg).firstOrNull { it != null && (it.first != null || it.second != null) } ?: (0xFFFFFFFF.toInt() to null)
        val notes = rels.values.firstOrNull { it.type.endsWith("/notesSlide") }?.target?.let { notes(it, theme) } ?: emptyList()
        return Slide(shapes, background.first ?: 0xFFFFFFFF.toInt(), background.second, notes, hidden)
    }

    private fun notes(part: String, theme: Theme): List<Paragraph> {
        val x = parts.xml(part) ?: return emptyList()
        val ctx = Ctx(part, DEFAULT_MAP, theme, null, null)
        val out = ArrayList<Paragraph>()
        while (x.next() != Event.EOF) {
            if (x.event == Event.START && x.ns == Ns.P && x.local == "sp") {
                var isBody = false
                var body: TextBody? = null
                x.children { c ->
                    if (c.event != Event.START) return@children
                    when {
                        c.local == "ph" -> { isBody = c.attr("type") == "body" }
                        c.ns == Ns.P && c.local == "txBody" -> body = textBody(c, ctx, arrayOfNulls(9), null, 12f, 0xFF000000.toInt())
                        c.ns == Ns.P && (c.local == "nvSpPr" || c.local == "nvPr") -> Unit
                        c.ns == Ns.P && c.local == "cNvPr" -> c.skip()
                        else -> if (c.local != "nvPr" && c.local != "nvSpPr") c.skip()
                    }
                }
                if (isBody) body?.paragraphs?.filter { !it.isEmpty() }?.let { out.addAll(it) }
            }
        }
        return out
    }

    private fun background(x: XmlReader, ctx: Ctx): Pair<Int?, String?> {
        var color: Int? = null; var image: String? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "bgPr" -> c.children { f ->
                    if (f.event != Event.START) return@children
                    when (f.local) {
                        "solidFill" -> color = color(f, ctx)
                        "gradFill" -> color = firstGradientColor(f, ctx)
                        "blipFill" -> image = blip(f, ctx)
                        else -> f.skip()
                    }
                }
                "bgRef" -> { color = color(c, ctx) }
                else -> c.skip()
            }
        }
        return color to image
    }

    // ------------------------------------------------------------------ shape trees
    private class Group(val offX: Float, val offY: Float, val sx: Float, val sy: Float, val chX: Float, val chY: Float) {
        fun x(v: Float) = offX + (v - chX) * sx
        fun y(v: Float) = offY + (v - chY) * sy
    }

    /**
     * Reads a p:spTree (or group) into [out]. In masters and layouts ([decorativeOnly]) placeholders
     * are collected into [phs] instead of drawn.
     */
    private fun tree(x: XmlReader, ctx: Ctx, out: MutableList<Shape>, phs: MutableList<Pair<Pair<String?, String?>, PhInfo>>?, group: Group?, decorativeOnly: Boolean) {
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.ns == Ns.P && (c.local == "sp" || c.local == "cxnSp") -> shape(c, ctx, out, phs, group, decorativeOnly)
                c.ns == Ns.P && c.local == "pic" -> picture(c, ctx, out, group, decorativeOnly)
                c.ns == Ns.P && c.local == "graphicFrame" -> frame(c, ctx, out, group, decorativeOnly)
                c.ns == Ns.P && c.local == "grpSp" -> grp(c, ctx, out, phs, group, decorativeOnly)
                c.ns == Ns.MC && c.local == "AlternateContent" -> {
                    var done = false
                    c.children { a ->
                        if (a.event != Event.START) return@children
                        if (!done && (a.local == "Choice" || a.local == "Fallback")) { done = true; tree(a, ctx, out, phs, group, decorativeOnly) } else a.skip()
                    }
                }
                else -> c.skip()
            }
        }
    }

    private var groupDepth = 0

    private fun grp(x: XmlReader, ctx: Ctx, out: MutableList<Shape>, phs: MutableList<Pair<Pair<String?, String?>, PhInfo>>?, parent: Group?, decorativeOnly: Boolean) {
        if (groupDepth > 40) { x.skip(); return }
        groupDepth++
        try { grpBody(x, ctx, out, phs, parent, decorativeOnly) } finally { groupDepth-- }
    }

    private fun grpBody(x: XmlReader, ctx: Ctx, out: MutableList<Shape>, phs: MutableList<Pair<Pair<String?, String?>, PhInfo>>?, parent: Group?, decorativeOnly: Boolean) {
        var g: Group? = parent
        x.children { c ->
            if (c.event != Event.START) return@children
            if (c.ns == Ns.P && c.local == "grpSpPr") {
                c.children { p ->
                    if (p.event == Event.START && p.local == "xfrm") {
                        var off = floatArrayOf(0f, 0f); var ext = floatArrayOf(0f, 0f); var chOff = floatArrayOf(0f, 0f); var chExt = floatArrayOf(0f, 0f)
                        p.children { e ->
                            if (e.event != Event.START) return@children
                            val a = floatArrayOf((e.attr("x") ?: e.attr("cx"))?.toLongOrNull()?.div(EMU) ?: 0f, (e.attr("y") ?: e.attr("cy"))?.toLongOrNull()?.div(EMU) ?: 0f)
                            when (e.local) { "off" -> off = a; "ext" -> ext = a; "chOff" -> chOff = a; "chExt" -> chExt = a }
                            e.skip()
                        }
                        val sx = if (chExt[0] > 0) ext[0] / chExt[0] else 1f
                        val sy = if (chExt[1] > 0) ext[1] / chExt[1] else 1f
                        // Compose with the parent group.
                        g = if (parent == null) Group(off[0], off[1], sx, sy, chOff[0], chOff[1])
                            else Group(parent.x(off[0]), parent.y(off[1]), sx * parent.sx, sy * parent.sy, chOff[0], chOff[1])
                    } else if (p.event == Event.START) p.skip()
                }
                return@children
            }
            when {
                c.ns == Ns.P && (c.local == "sp" || c.local == "cxnSp") -> shape(c, ctx, out, phs, g, decorativeOnly)
                c.ns == Ns.P && c.local == "pic" -> picture(c, ctx, out, g, decorativeOnly)
                c.ns == Ns.P && c.local == "graphicFrame" -> frame(c, ctx, out, g, decorativeOnly)
                c.ns == Ns.P && c.local == "grpSp" -> grp(c, ctx, out, phs, g, decorativeOnly)
                c.ns == Ns.MC && c.local == "AlternateContent" -> {
                    var done = false
                    c.children { a -> if (a.event == Event.START) { if (!done && (a.local == "Choice" || a.local == "Fallback")) { done = true; tree(a, ctx, out, phs, g, decorativeOnly) } else a.skip() } }
                }
                else -> c.skip()
            }
        }
    }

    private class SpPr {
        var box: FloatArray? = null; var rot = 0f; var flipH = false; var flipV = false
        var geom: Geometry? = null; var fill: Int? = null; var noFill = false; var line: Int? = null; var noLine = false; var lineW: Float? = null
        var image: String? = null
    }

    private fun spPr(x: XmlReader, ctx: Ctx): SpPr {
        val s = SpPr()
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "xfrm" -> {
                    s.rot = (c.attr("rot")?.toLongOrNull() ?: 0) / 60000f
                    s.flipH = c.attr("flipH") == "1"; s.flipV = c.attr("flipV") == "1"
                    val box = FloatArray(4)
                    c.children { e ->
                        if (e.event != Event.START) return@children
                        when (e.local) {
                            "off" -> { box[0] = (e.attr("x")?.toLongOrNull() ?: 0) / EMU; box[1] = (e.attr("y")?.toLongOrNull() ?: 0) / EMU }
                            "ext" -> { box[2] = (e.attr("cx")?.toLongOrNull() ?: 0) / EMU; box[3] = (e.attr("cy")?.toLongOrNull() ?: 0) / EMU }
                        }
                        e.skip()
                    }
                    s.box = box
                }
                "prstGeom" -> { s.geom = geometry(c.attr("prst")); c.skip() }
                "custGeom" -> { s.geom = Geometry.OTHER; c.skip() }
                "solidFill" -> s.fill = color(c, ctx)
                "gradFill" -> s.fill = firstGradientColor(c, ctx)
                "noFill" -> { s.noFill = true; c.skip() }
                "blipFill" -> s.image = blip(c, ctx)
                "ln" -> {
                    s.lineW = c.attr("w")?.toLongOrNull()?.div(EMU)
                    c.children { l ->
                        if (l.event != Event.START) return@children
                        when (l.local) { "solidFill" -> s.line = color(l, ctx); "noFill" -> { s.noLine = true; l.skip() }; "gradFill" -> s.line = firstGradientColor(l, ctx); else -> l.skip() }
                    }
                }
                else -> c.skip()
            }
        }
        return s
    }

    private fun geometry(p: String?): Geometry = when (p) {
        "rect", "snip1Rect", "flowChartProcess" -> Geometry.RECT
        "roundRect", "round1Rect", "round2SameRect", "flowChartAlternateProcess" -> Geometry.ROUND_RECT
        "ellipse", "flowChartConnector", "donut" -> Geometry.ELLIPSE
        "line", "straightConnector1", "bentConnector2", "bentConnector3", "curvedConnector3" -> Geometry.LINE
        "triangle", "rtTriangle" -> Geometry.TRIANGLE
        "rightArrow", "chevron", "homePlate" -> Geometry.RIGHT_ARROW
        "diamond", "flowChartDecision" -> Geometry.DIAMOND
        null -> Geometry.RECT
        else -> Geometry.OTHER
    }

    /** p:style references: the theme's fill, line and font colours for an autoshape. */
    private class StyleRefs { var fill: Int? = null; var fillIdx = 0; var line: Int? = null; var lineIdx = 0; var font: Int? = null }

    private fun styleRefs(x: XmlReader, ctx: Ctx): StyleRefs {
        val r = StyleRefs()
        x.children { c ->
            if (c.event != Event.START) return@children
            val idx = c.attr("idx")?.toIntOrNull() ?: 0
            when (c.local) {
                "fillRef" -> { r.fillIdx = idx; r.fill = color(c, ctx) }
                "lnRef" -> { r.lineIdx = idx; r.line = color(c, ctx) }
                "fontRef" -> r.font = color(c, ctx)
                else -> c.skip()
            }
        }
        return r
    }

    private fun shape(x: XmlReader, ctx: Ctx, out: MutableList<Shape>, phs: MutableList<Pair<Pair<String?, String?>, PhInfo>>?, group: Group?, decorativeOnly: Boolean) {
        var phType: String? = null; var phIdx: String? = null; var isPh = false
        var sp: SpPr? = null; var refs: StyleRefs? = null
        var bodyXml: (() -> Unit)? = null
        var text: TextBody? = null
        val connector = x.local == "cxnSp"
        // The text is read after the placeholder and style are known, so it can inherit from them.
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.local == "ph" -> { isPh = true; phType = c.attr("type"); phIdx = c.attr("idx"); c.skip() }
                c.ns == Ns.P && (c.local == "nvSpPr" || c.local == "nvPr" || c.local == "nvCxnSpPr") -> Unit
                c.ns == Ns.P && c.local == "spPr" -> sp = spPr(c, ctx)
                c.ns == Ns.P && c.local == "style" -> refs = styleRefs(c, ctx)
                c.ns == Ns.P && c.local == "txBody" -> {
                    val inherited = inherit(ctx, isPh, phType, phIdx)
                    val fontColor = refs?.font
                    text = textBody(c, ctx, inherited.first, inherited.second, defaultSize(phType, isPh), fontColor ?: ctx.resolveScheme("tx1"))
                    if (phs != null && isPh) phs.add((phType to phIdx) to PhInfo(sp?.box, readLevelsFrom(text), lastBody, phType ?: "body"))
                }
                else -> c.skip()
            }
        }
        if (decorativeOnly && isPh) {
            if (text == null && phs != null) phs.add((phType to phIdx) to PhInfo(sp?.box, arrayOfNulls(9), null, phType ?: "body"))
            else if (text != null && phs != null && phs.none { it.first == (phType to phIdx) }) phs.add((phType to phIdx) to PhInfo(sp?.box, arrayOfNulls(9), lastBody, phType ?: "body"))
            return
        }
        // Slide-number, date and footer placeholders only show what the slide itself writes into them.
        val s = sp ?: SpPr()
        val box = s.box ?: (if (isPh) inheritBox(ctx, phType, phIdx) else null) ?: return
        val r = refs
        val fill = when { s.noFill -> 0; s.fill != null -> s.fill!!; r != null && r.fillIdx > 0 && !connector -> r.fill ?: 0; else -> 0 }
        val line = when { s.noLine -> 0; s.line != null -> s.line!!; r != null && r.lineIdx > 0 -> r.line ?: 0; connector -> 0xFF000000.toInt(); else -> 0 }
        val lineW = s.lineW ?: if (line != 0) 0.75f else 0f
        val (gx, gy, gw, gh) = place(box, group)
        val geom = if (connector) Geometry.LINE else s.geom ?: Geometry.RECT
        if (fill == 0 && line == 0 && s.image == null && (text == null || text!!.paragraphs.all { it.isEmpty() })) return
        out.add(Shape(gx, gy, gw, gh, s.rot, s.flipH, s.flipV, geom, fill, line, lineW, text?.takeIf { t -> t.paragraphs.any { !it.isEmpty() } }, s.image))
    }

    private var lastBody: BodyPr? = null

    private fun readLevelsFrom(t: TextBody?): Array<Lvl?> = lastLevels

    private var lastLevels: Array<Lvl?> = arrayOfNulls(9)

    private fun place(box: FloatArray, g: Group?): FloatArray =
        if (g == null) box else floatArrayOf(g.x(box[0]), g.y(box[1]), box[2] * g.sx, box[3] * g.sy)

    private operator fun FloatArray.component4() = this[3]

    private fun inheritBox(ctx: Ctx, type: String?, idx: String?): FloatArray? =
        ctx.layout?.let { findPh(it.placeholders, type, idx)?.box } ?: ctx.master?.let { findPh(it.placeholders, type, idx)?.box }
            ?: ctx.layout?.master?.let { findPh(it.placeholders, type, idx)?.box }

    /** The text levels and body settings a shape inherits: master text styles, then master and layout placeholders. */
    private fun inherit(ctx: Ctx, isPh: Boolean, type: String?, idx: String?): Pair<Array<Lvl?>, BodyPr?> {
        val master = ctx.master ?: ctx.layout?.master
        val base: Array<Lvl?> = when {
            !isPh -> master?.other ?: defaultText
            normType(type) == "title" -> master?.title ?: arrayOfNulls(9)
            normType(type) in setOf("body", "subTitle") -> master?.body ?: arrayOfNulls(9)
            else -> master?.other ?: arrayOfNulls(9)
        }
        var levels: Array<Lvl?> = Array<Lvl?>(9) { i -> (defaultText[i] ?: Lvl()).over(base[i]) }
        var body: BodyPr? = null
        if (isPh) {
            master?.let { m -> findPh(m.placeholders, type, idx)?.let { p -> levels = Array<Lvl?>(9) { i -> (levels[i] ?: Lvl()).over(p.levels[i]) }; body = (body ?: BodyPr()).over(p.body) } }
            ctx.layout?.let { l -> findPh(l.placeholders, type, idx)?.let { p -> levels = Array<Lvl?>(9) { i -> (levels[i] ?: Lvl()).over(p.levels[i]) }; body = (body ?: BodyPr()).over(p.body) } }
        }
        // Subtitles centre and drop bullets; titles never have them.
        if (isPh && (normType(type) == "title" || type == "subTitle")) levels = Array<Lvl?>(9) { i -> (levels[i] ?: Lvl()).over(Lvl(bullet = "")) }
        return levels to body
    }

    private fun defaultSize(type: String?, isPh: Boolean): Float = when {
        !isPh -> 18f
        normType(type) == "title" -> 44f
        type == "subTitle" -> 32f
        else -> 28f
    }

    private fun picture(x: XmlReader, ctx: Ctx, out: MutableList<Shape>, group: Group?, decorativeOnly: Boolean) {
        var sp: SpPr? = null; var image: String? = null; var crop: FloatArray? = null; var isPh = false; var phType: String? = null; var phIdx: String? = null
        var isMedia = false
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.local == "ph" -> { isPh = true; phType = c.attr("type"); phIdx = c.attr("idx"); c.skip() }
                c.local == "videoFile" || c.local == "audioFile" || c.local == "media" -> { isMedia = true; c.skip() }
                c.ns == Ns.P && (c.local == "nvPicPr" || c.local == "nvPr") -> Unit
                c.ns == Ns.P && c.local == "blipFill" -> c.children { b ->
                    if (b.event != Event.START) return@children
                    when (b.local) {
                        "blip" -> { image = blipRef(b, ctx); b.skip() }
                        "srcRect" -> {
                            fun f(n: String) = (b.attr(n)?.toFloatOrNull() ?: 0f) / 100000f
                            crop = floatArrayOf(f("l"), f("t"), f("r"), f("b")).takeIf { a -> a.any { it != 0f } }
                            b.skip()
                        }
                        else -> b.skip()
                    }
                }
                c.ns == Ns.P && c.local == "spPr" -> sp = spPr(c, ctx)
                else -> c.skip()
            }
        }
        if (decorativeOnly && isPh) return
        if (isMedia) media++
        val box = sp?.box ?: (if (isPh) inheritBox(ctx, phType, phIdx) else null) ?: return
        val (gx, gy, gw, gh) = place(box, group)
        out.add(Shape(gx, gy, gw, gh, sp?.rot ?: 0f, sp?.flipH == true, sp?.flipV == true, sp?.geom ?: Geometry.RECT, 0, sp?.line ?: 0, sp?.lineW ?: 0f, null, image, crop,
            placeholder = if (image == null) tr("Picture") else null))
    }

    private fun frame(x: XmlReader, ctx: Ctx, out: MutableList<Shape>, group: Group?, decorativeOnly: Boolean) {
        var box: FloatArray? = null
        var shape: Shape? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.ns == Ns.P && c.local == "xfrm" -> {
                    val b = FloatArray(4)
                    c.children { e ->
                        if (e.event != Event.START) return@children
                        when (e.local) {
                            "off" -> { b[0] = (e.attr("x")?.toLongOrNull() ?: 0) / EMU; b[1] = (e.attr("y")?.toLongOrNull() ?: 0) / EMU }
                            "ext" -> { b[2] = (e.attr("cx")?.toLongOrNull() ?: 0) / EMU; b[3] = (e.attr("cy")?.toLongOrNull() ?: 0) / EMU }
                        }
                        e.skip()
                    }
                    box = b
                }
                c.ns == Ns.A && c.local == "graphicData" -> {
                    val uri = c.attr("uri") ?: ""
                    val b = box?.let { place(it, group) } ?: floatArrayOf(0f, 0f, 0f, 0f)
                    shape = when {
                        uri.endsWith("/table") -> table(c, ctx, b)
                        uri.contains("chart") -> { charts++; c.skip(); Shape(b[0], b[1], b[2], b[3], lineColor = 0xFFBBBBBB.toInt(), lineWidthPt = 0.75f, placeholder = tr("Chart")) }
                        uri.endsWith("/diagram") -> { diagrams++; c.skip(); Shape(b[0], b[1], b[2], b[3], lineColor = 0xFFBBBBBB.toInt(), lineWidthPt = 0.75f, placeholder = tr("Diagram")) }
                        else -> {
                            // OLE objects carry a picture of themselves.
                            var img: String? = null
                            c.children { o -> if (o.event == Event.START && o.local == "blip") { img = blipRef(o, ctx); o.skip() } }
                            Shape(b[0], b[1], b[2], b[3], image = img, placeholder = if (img == null) tr("Embedded object") else null, lineColor = if (img == null) 0xFFBBBBBB.toInt() else 0, lineWidthPt = 0.75f)
                        }
                    }
                }
                c.ns == Ns.P && (c.local == "nvGraphicFramePr") -> c.skip()
                c.ns == Ns.A && c.local == "graphic" -> Unit
                else -> c.skip()
            }
        }
        if (decorativeOnly && shape?.table == null && shape?.image == null) return
        shape?.let { out.add(it) }
    }

    private fun table(x: XmlReader, ctx: Ctx, box: FloatArray): Shape {
        val rows = ArrayList<TableRow>()
        var firstRow = false; var bandRow = false
        val accent = ctx.theme.colors["accent1"] ?: 0xFF4472C4.toInt()
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "tbl" -> Unit
                "tblPr" -> { firstRow = c.attr("firstRow") == "1"; bandRow = c.attr("bandRow") == "1"; c.skip() }
                "tblGrid" -> c.skip()
                "tr" -> {
                    val cells = ArrayList<TableCell>()
                    val r = rows.size
                    c.children { tc ->
                        if (tc.event != Event.START) return@children
                        if (tc.local != "tc") { tc.skip(); return@children }
                        val span = tc.attr("gridSpan")?.toIntOrNull() ?: 1
                        val rspan = tc.attr("rowSpan")?.toIntOrNull() ?: 1
                        val merged = tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1"
                        var paras: List<Paragraph> = emptyList(); var fill = 0
                        val header = firstRow && r == 0
                        tc.children { k ->
                            if (k.event != Event.START) return@children
                            when (k.local) {
                                "txBody" -> paras = textBody(k, ctx, arrayOfNulls(9), null, 18f, if (header) 0xFFFFFFFF.toInt() else ctx.resolveScheme("tx1")).paragraphs
                                "tcPr" -> k.children { p -> if (p.event == Event.START) { if (p.local == "solidFill") fill = color(p, ctx) ?: 0 else p.skip() } }
                                else -> k.skip()
                            }
                        }
                        if (fill == 0) fill = when {
                            header -> accent
                            bandRow && r % 2 == (if (firstRow) 1 else 0) -> Colors.lighten(accent, 0.2f)
                            bandRow -> Colors.lighten(accent, 0.1f)
                            else -> 0
                        }
                        if (header) paras = paras.map { p -> Paragraph(p.spans.map { Span(it.text, it.style.copy(bold = true), it.link) }, p.heading, p.align, p.indentPt, p.firstLinePt, p.spaceBeforePt, p.spaceAfterPt, p.listLabel, p.listLevel) }
                        if (!merged) cells.add(TableCell(paras, span, rspan, fill))
                    }
                    rows.add(TableRow(cells, firstRow && r == 0))
                }
                else -> Unit
            }
        }
        return Shape(box[0], box[1], box[2], box[3], table = Table(rows, true))
    }

    // ------------------------------------------------------------------ text
    /** a:lvlNpPr children of a list-style element, by level. */
    private fun levels(x: XmlReader, ctx: Ctx): Array<Lvl?> {
        val out = arrayOfNulls<Lvl>(9)
        x.children { c ->
            if (c.event != Event.START) return@children
            val m = Regex("""^lvl([1-9])pPr$""").find(c.local)
            if (m != null) out[m.groupValues[1].toInt() - 1] = pPr(c, ctx)
            else if (c.local == "defPPr") { val l = pPr(c, ctx); for (i in 0 until 9) if (out[i] == null) out[i] = l }
            else c.skip()
        }
        return out
    }

    private fun pPr(x: XmlReader, ctx: Ctx): Lvl {
        val l = Lvl()
        l.align = when (x.attr("algn")) { "ctr" -> Align.CENTER; "r" -> Align.END; "just", "dist" -> Align.JUSTIFY; "l" -> Align.START; else -> null }
        l.marL = x.attr("marL")?.toLongOrNull()?.div(EMU)
        l.indent = x.attr("indent")?.toLongOrNull()?.div(EMU)
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "buNone" -> { l.bullet = ""; c.skip() }
                "buChar" -> { l.bullet = c.attr("char")?.let { ch -> ch.map { Units.symbolChar(it, null) }.joinToString("") } ?: "•"; l.autoNum = null; c.skip() }
                "buAutoNum" -> { l.autoNum = c.attr("type") ?: "arabicPeriod"; l.bullet = null; c.skip() }
                "buClr" -> l.bulletColor = color(c, ctx)
                "defRPr" -> rPr(c, ctx, l)
                "spcBef" -> c.children { s -> if (s.event == Event.START) { if (s.local == "spcPts") l.spcBefore = (s.attr("val")?.toFloatOrNull() ?: 0f) / 100f; s.skip() } }
                "spcAft" -> c.children { s -> if (s.event == Event.START) { if (s.local == "spcPts") l.spcAfter = (s.attr("val")?.toFloatOrNull() ?: 0f) / 100f; s.skip() } }
                else -> c.skip()
            }
        }
        return l
    }

    private fun rPr(x: XmlReader, ctx: Ctx, l: Lvl) {
        x.attr("sz")?.toFloatOrNull()?.let { l.sz = it / 100f }
        x.attr("b")?.let { l.bold = it == "1" || it == "true" }
        x.attr("i")?.let { l.italic = it == "1" || it == "true" }
        x.attr("u")?.let { l.underline = it != "none" }
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "solidFill" -> l.color = color(c, ctx)
                "gradFill" -> l.color = firstGradientColor(c, ctx)
                "latin" -> { l.font = c.attr("typeface"); c.skip() }
                else -> c.skip()
            }
        }
    }

    private fun textBody(x: XmlReader, ctx: Ctx, inherited: Array<Lvl?>, inheritedBody: BodyPr?, fallbackSize: Float, fallbackColor: Int): TextBody {
        var body = BodyPr().over(inheritedBody)
        var own = arrayOfNulls<Lvl>(9)
        val paragraphs = ArrayList<Paragraph>()
        val counters = IntArray(9)
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "bodyPr" -> body = body.over(bodyPr(c))
                "lstStyle" -> own = levels(c, ctx)
                "p" -> paragraphs.add(paragraph(c, ctx, Array<Lvl?>(9) { i -> (inherited[i] ?: Lvl()).over(own[i]) }, counters, fallbackSize, fallbackColor))
                else -> c.skip()
            }
        }
        lastLevels = own; lastBody = body
        return TextBody(paragraphs, body.l ?: 7.2f, body.t ?: 3.6f, body.r ?: 7.2f, body.b ?: 3.6f, body.anchor ?: Anchor.TOP, body.fontScale ?: 1f, body.wrap ?: true, fallbackSize, fallbackColor)
    }

    private fun bodyPr(x: XmlReader): BodyPr {
        val b = BodyPr(
            x.attr("lIns")?.toLongOrNull()?.div(EMU), x.attr("tIns")?.toLongOrNull()?.div(EMU), x.attr("rIns")?.toLongOrNull()?.div(EMU), x.attr("bIns")?.toLongOrNull()?.div(EMU),
            when (x.attr("anchor")) { "ctr" -> Anchor.MIDDLE; "b" -> Anchor.BOTTOM; "t" -> Anchor.TOP; else -> null },
            x.attr("wrap")?.let { it != "none" },
        )
        x.children { c ->
            if (c.event != Event.START) return@children
            if (c.local == "normAutofit") b.fontScale = (c.attr("fontScale")?.toFloatOrNull() ?: 100000f) / 100000f
            c.skip()
        }
        return b
    }

    private fun paragraph(x: XmlReader, ctx: Ctx, levels: Array<Lvl?>, counters: IntArray, fallbackSize: Float, fallbackColor: Int): Paragraph {
        var lvlIdx = 0
        var direct: Lvl? = null
        val spans = ArrayList<Span>()
        var firstRunSize: Float? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "pPr" -> { lvlIdx = (c.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8); direct = pPr(c, ctx) }
                "r", "fld" -> {
                    val eff = (levels[lvlIdx] ?: Lvl()).over(direct?.let { d -> Lvl(sz = d.sz, bold = d.bold, italic = d.italic, underline = d.underline, color = d.color, font = d.font) })
                    val run = Lvl().let { r -> var rl = eff; var text = ""
                        c.children { k ->
                            if (k.event != Event.START) return@children
                            when (k.local) {
                                "rPr" -> { val o = Lvl(); rPr(k, ctx, o); rl = rl.over(o) }
                                "t" -> text += k.readText()
                                else -> k.skip()
                            }
                        }
                        rl to text
                    }
                    val (rl, text) = run
                    if (firstRunSize == null && text.isNotBlank()) firstRunSize = rl.sz
                    if (text.isNotEmpty()) spans.add(Span(text, RunStyle(bold = rl.bold == true, italic = rl.italic == true, underline = rl.underline == true, sizePt = rl.sz ?: fallbackSize, color = rl.color ?: fallbackColor, font = rl.font, monospace = Units.isMonospace(rl.font))))
                }
                "br" -> { spans.add(Span("\n", RunStyle(sizePt = (levels[lvlIdx]?.sz ?: fallbackSize)))); c.skip() }
                else -> c.skip()
            }
        }
        val eff = (levels[lvlIdx] ?: Lvl()).over(direct)
        val hasText = spans.any { it.text.isNotBlank() }
        var label: String? = null
        if (hasText) {
            when {
                eff.autoNum != null && eff.bullet != "" -> { counters[lvlIdx]++; for (k in lvlIdx + 1 until 9) counters[k] = 0; label = autoNumber(counters[lvlIdx], eff.autoNum!!) }
                !eff.bullet.isNullOrEmpty() -> { label = eff.bullet; for (k in lvlIdx until 9) counters[k] = 0 }
                else -> for (k in lvlIdx until 9) counters[k] = 0
            }
        }
        return Paragraph(spans, 0, eff.align ?: Align.START, eff.marL ?: 0f, eff.indent ?: 0f, eff.spcBefore ?: 0f, eff.spcAfter ?: 0f, label, lvlIdx)
    }

    private fun autoNumber(n: Int, type: String): String {
        val num = when {
            type.startsWith("alphaLc") -> Units.letters(n)
            type.startsWith("alphaUc") -> Units.letters(n).uppercase()
            type.startsWith("romanLc") -> Units.roman(n).lowercase()
            type.startsWith("romanUc") -> Units.roman(n)
            else -> n.toString()
        }
        return when {
            type.endsWith("ParenBoth") -> "($num)"
            type.endsWith("ParenR") -> "$num)"
            type.endsWith("Plain") -> num
            else -> "$num."
        }
    }

    // ------------------------------------------------------------------ colours and pictures
    private fun Ctx.resolveScheme(name: String): Int {
        val mapped = clrMap[name] ?: DEFAULT_MAP[name] ?: name
        return theme.colors[mapped] ?: theme.colors[name] ?: 0xFF000000.toInt()
    }

    /** The colour inside [x] (srgbClr, schemeClr, prstClr, sysClr…) with its modifiers; null when none. */
    private fun color(x: XmlReader, ctx: Ctx): Int? {
        var out: Int? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            var base: Int? = when (c.local) {
                "srgbClr" -> Units.hexColor(c.attr("val"))
                "schemeClr" -> c.attr("val")?.let { if (it == "phClr") 0xFF808080.toInt() else ctx.resolveScheme(it) }
                "sysClr" -> Units.hexColor(c.attr("lastClr")) ?: if (c.attr("val") == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                "prstClr" -> PRESET[c.attr("val")]?.let { (0xFF000000L or it.toLong()).toInt() }
                "scrgbClr" -> {
                    fun p(n: String) = ((c.attr(n)?.toFloatOrNull() ?: 0f) / 100000f * 255).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (p("r") shl 16) or (p("g") shl 8) or p("b")
                }
                "hslClr" -> null
                else -> { c.skip(); return@children }
            }
            var lumMod = 1f; var lumOff = 0f
            c.children { m ->
                if (m.event != Event.START) return@children
                val v = (m.attr("val")?.toFloatOrNull() ?: 0f) / 100000f
                if (base != null) when (m.local) {
                    "lumMod" -> lumMod = v
                    "lumOff" -> lumOff = v
                    "shade" -> base = Colors.shade(base!!, v)
                    "tint" -> base = Colors.lighten(base!!, v)
                    "alpha" -> base = Colors.alpha(base!!, v)
                }
                m.skip()
            }
            if (base != null && (lumMod != 1f || lumOff != 0f)) base = Colors.lum(base!!, lumMod, lumOff)
            if (out == null) out = base
        }
        return out
    }

    private fun firstGradientColor(x: XmlReader, ctx: Ctx): Int? {
        var out: Int? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "gsLst" -> c.children { g -> if (g.event == Event.START) { if (g.local == "gs" && out == null) out = color(g, ctx) else g.skip() } }
                else -> c.skip()
            }
        }
        return out
    }

    private fun blip(x: XmlReader, ctx: Ctx): String? {
        var out: String? = null
        x.children { c -> if (c.event == Event.START) { if (c.local == "blip") out = blipRef(c, ctx); c.skip() } }
        return out
    }

    private fun blipRef(x: XmlReader, ctx: Ctx): String? = x.attr(Ns.R, "embed")?.let { ctx.rels[it] }?.takeIf { !it.external }?.target

    private val PRESET = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF, "yellow" to 0xFFFF00,
        "gray" to 0x808080, "grey" to 0x808080, "darkGray" to 0xA9A9A9, "lightGray" to 0xD3D3D3, "orange" to 0xFFA500, "purple" to 0x800080,
        "navy" to 0x000080, "darkBlue" to 0x00008B, "darkRed" to 0x8B0000, "darkGreen" to 0x006400, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
    )
}
