package com.localmediatools.ui.meta

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.meta.core.MetaField
import com.localmediatools.meta.core.MetaInput
import com.localmediatools.meta.core.MetaReader
import com.localmediatools.meta.core.MetaReport
import com.localmediatools.meta.core.Privacy
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Screen
import com.localmediatools.ui.SearchField
import com.localmediatools.ui.Selection
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.TopBar
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.ui.reader.ReaderFiles
import com.localmediatools.ui.reader.ReaderSource
import com.localmediatools.ui.reader.onReaderThread
import com.localmediatools.ui.tools.ToolScreens
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The metadata viewer: what a file says about itself. First a summary of what it reveals about the
 * person who made it (place, people, device, times, programs, hidden pictures), then every field,
 * grouped as the file stores them, searchable and copyable. Files are only read.
 */
class MetadataScreen(activity: MainActivity, private val start: Source? = null) : Screen(activity) {
    /** The file to read: a content address, or a file the app already has ([file]), and its name. */
    class Source(val uri: Uri?, val file: File?, val name: String)

    private val tool = ToolId.METADATA
    private lateinit var body: LinearLayout
    private lateinit var fieldsBox: LinearLayout
    private var source: Source? = null
    private var report: MetaReport? = null
    private var query = ""
    private var loading: Job? = null

    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        val more = UI.iconButton(ctx, R.drawable.ic_more, tr("More")) { }
        more.setOnClickListener { menu(more) }
        root.addView(TopBar(this, tool.title, more))
        body = UI.vertical(ctx, 16, 4)
        root.addView(ScrollView(ctx).apply { addView(body); isFillViewport = true }, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (start != null) load(start) else showStart()
        return root
    }

    // ------------------------------------------------------------------ choosing and reading
    private fun showStart() {
        body.removeAllViews()
        val hero = UI.card(ctx)
        val row = UI.horizontal(ctx, Gravity.TOP)
        row.addView(UI.toolTile(ctx, tool, 52, 28))
        row.addView(UI.text(ctx, tool.longDescription, TextStyle.BODY_2).apply { setPadding(ctx.dp(14), 0, 0, 0) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(row)
        hero.addView(UI.primaryButton(ctx, tr("Choose a file")) { pick() }, lp().apply { topMargin = ctx.dp(16) })
        hero.addView(UI.text(ctx, tr("Also from the gallery (Open in a tool) and from the file reader's menu."), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(10), 0, 0) })
        body.addView(hero)
        body.addView(UI.note(ctx, tr("Files are read on this phone and never uploaded. The viewer only reads them: nothing is changed."), UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(20); bottomMargin = ctx.dp(24) })
    }

    private fun pick() {
        activity.pickAnyFile { uri ->
            uri ?: return@pickAnyFile
            val name = try { ReaderFiles.describe(ctx, uri).first } catch (_: Exception) { uri.lastPathSegment ?: tr("File") }
            load(Source(uri, null, name))
        }
    }

    private fun load(s: Source) {
        source = s
        report = null
        query = ""
        body.removeAllViews()
        body.addView(UI.text(ctx, tr("Reading {0}…", s.name), TextStyle.BODY_2).apply { setPadding(ctx.dp(4), ctx.dp(24), ctx.dp(4), 0) })
        loading?.cancel()
        loading = scope.launch {
            val r = try { onReaderThread { read(ctx, s) } } catch (e: Exception) {
                body.removeAllViews()
                body.addView(UI.note(ctx, tr("This file can't be read: {0}", e.message ?: e.javaClass.simpleName), UI.NoteKind.WARN), lp().apply { topMargin = ctx.dp(16) })
                body.addView(UI.secondaryButton(ctx, tr("Choose another file")) { pick() }, lp().apply { topMargin = ctx.dp(16) })
                return@launch
            }
            report = r
            show(r)
        }
    }

    // ------------------------------------------------------------------ showing
    private fun show(r: MetaReport) {
        body.removeAllViews()
        body.addView(header(r), lp())
        body.addView(summary(r), lp().apply { topMargin = ctx.dp(14) })
        for (n in r.notes) body.addView(UI.note(ctx, n, UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(10) })
        body.addView(SearchField(ctx, tr("Search the fields")) { q -> query = q.trim(); fillFields() }, lp().apply { topMargin = ctx.dp(20) })
        fieldsBox = UI.vertical(ctx)
        body.addView(fieldsBox, lp().apply { topMargin = ctx.dp(6); bottomMargin = ctx.dp(28) })
        fillFields()
    }

    private fun header(r: MetaReport): View {
        val card = UI.card(ctx)
        val row = UI.horizontal(ctx)
        row.addView(UI.iconTile(ctx, R.drawable.ic_file, Palette.section(tool.section), 44, 24))
        val t = UI.vertical(ctx).apply { setPadding(ctx.dp(12), 0, 0, 0) }
        t.addView(UI.text(ctx, source?.name ?: "", TextStyle.SUBTITLE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE })
        val size = r.get("FileSize")?.value
        val dims = r.get("ImageSize")?.value
        val length = r.fields.firstOrNull { it.name == "Duration" }?.value
        t.addView(UI.text(ctx, listOfNotNull(MetaLabels.kind(r.format), size, dims, length).joinToString("  ·  "), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(2), 0, 0) })
        row.addView(t, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    /** What the file tells others: one row per kind of personal detail found. */
    private fun summary(r: MetaReport): View {
        val card = UI.card(ctx)
        // The file system's own details (name, size, date on this phone) aren't inside the file.
        val personal = r.fields.filter { it.privacy != null && !(it.group == "File" && it.name in FILE_SYSTEM) }
        val byKind = Privacy.entries.associateWith { k -> personal.filter { it.privacy == k } }.filterValues { it.isNotEmpty() }
        card.addView(UI.text(ctx, tr("What this file tells others"), TextStyle.SUBTITLE))
        if (byKind.isEmpty()) {
            card.addView(UI.text(ctx, tr("Nothing personal: no place, names, device, dates or hidden pictures were found, only technical details."), TextStyle.BODY_2).apply { setPadding(0, ctx.dp(8), 0, 0) })
            return card
        }
        card.addView(UI.text(ctx, trn(byKind.size, "{0} kind of personal detail. Anyone you send this file to can read it.",
            "{0} kinds of personal details. Anyone you send this file to can read them."), TextStyle.BODY_2).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(4)) })
        for ((kind, fields) in byKind) {
            val row = UI.horizontal(ctx, Gravity.TOP).apply { setPadding(0, ctx.dp(12), 0, 0) }
            row.addView(UI.iconTile(ctx, MetaLabels.icon(kind), MetaLabels.color(kind), 36, 20))
            val col = UI.vertical(ctx).apply { setPadding(ctx.dp(12), 0, 0, 0) }
            col.addView(UI.text(ctx, MetaLabels.privacy(kind), TextStyle.BODY))
            val values = summaryValues(r, kind, fields)
            val shown = values.take(3).joinToString("\n") { it.take(120) }
            col.addView(UI.text(ctx, shown + if (values.size > 3) "\n" + tr("and {0} more", values.size - 3) else "", TextStyle.CAPTION).apply { setPadding(0, ctx.dp(2), 0, 0) })
            if (kind == Privacy.LOCATION) r.location?.let { (lat, lon) ->
                col.addView(UI.ghostButton(ctx, tr("Open in a maps app"), R.drawable.ic_pin) { openMap(lat, lon) }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
            }
            row.addView(col, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
        }
        // Photos and videos can be cleaned by the Remove metadata tool.
        val mime = r.get("MIMEType")?.value ?: ""
        val uri = source?.uri
        if (uri != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
            card.addView(UI.secondaryButton(ctx, tr("Make a copy without them"), R.drawable.ic_tool_metadata) {
                val sel = Selection.of(ToolId.REMOVE_METADATA); sel.clear(); sel.addUris(activity, listOf(uri))
                push(ToolScreens.create(activity, ToolId.REMOVE_METADATA))
            }, lp().apply { topMargin = ctx.dp(16) })
        }
        return card
    }

    /** The values worth showing in a summary row: the position first, dates before durations, no bare numbers. */
    private fun summaryValues(r: MetaReport, kind: Privacy, fields: List<MetaField>): List<String> {
        var list = fields
        if (kind == Privacy.LOCATION) {
            val parts = setOf("GPSLatitude", "GPSLongitude", "GPSPosition", "GPSCoordinates", "GPSLatitudeRef", "GPSLongitudeRef")
            val position = fields.firstOrNull { it.group == "Composite" && it.name == "GPSPosition" } ?: fields.firstOrNull { it.name == "GPSCoordinates" }
            list = listOfNotNull(position) + fields.filter { it.name !in parts }
        }
        if (kind == Privacy.TIME) list = list.filter { !it.name.startsWith("OffsetTime") }.sortedBy { if (MetaLabels.date(it.value) != null) 0 else 1 }
        return list.map { MetaLabels.value(it) }.filter { v -> !v.all { it.isDigit() || it == '.' } }.distinct()
    }

    private fun fillFields() {
        val r = report ?: return
        fieldsBox.removeAllViews()
        val q = query.lowercase(I18n.locale)
        val shown = if (q.isEmpty()) r.fields else r.fields.filter { f ->
            listOf(MetaLabels.field(f), f.name, f.value, MetaLabels.group(f.group)).any { it.lowercase(I18n.locale).contains(q) }
        }
        if (shown.isEmpty()) {
            fieldsBox.addView(UI.text(ctx, tr("No field matches “{0}”.", query), TextStyle.BODY_2).apply { setPadding(ctx.dp(4), ctx.dp(16), 0, 0) })
            return
        }
        // Sections in file order, fields of the same kind together (all colour-profile groups in one).
        val sections = LinkedHashMap<String, MutableList<MetaField>>()
        for (f in shown) sections.getOrPut(MetaLabels.group(f.group)) { ArrayList() }.add(f)
        var rows = 0
        for ((title, fields) in sections) {
            fieldsBox.addView(UI.label(ctx, "$title · ${fields.size}"), lp().apply { topMargin = ctx.dp(18); bottomMargin = ctx.dp(8) })
            val card = UI.vertical(ctx).apply { background = Shapes.rounded(ctx, Palette.SURFACE, 18f, Palette.STROKE); setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(4)) }
            for ((i, f) in fields.withIndex()) {
                if (rows >= MAX_ROWS) break
                if (i > 0) card.addView(UI.divider(ctx))
                card.addView(fieldRow(f))
                rows++
            }
            fieldsBox.addView(card, lp())
            if (rows >= MAX_ROWS) {
                fieldsBox.addView(UI.text(ctx, tr("Only the first {0} fields are listed: search to find the others.", MAX_ROWS), TextStyle.CAPTION).apply { setPadding(ctx.dp(4), ctx.dp(10), 0, 0) })
                break
            }
        }
    }

    private fun fieldRow(f: MetaField): View {
        val row = UI.vertical(ctx).apply { setPadding(0, ctx.dp(10), 0, ctx.dp(10)); isClickable = true; isFocusable = true; background = Shapes.clickable(ctx, Palette.SURFACE, 10f) }
        val top = UI.horizontal(ctx)
        if (f.privacy != null) top.addView(View(ctx).apply { background = Shapes.pill(ctx, MetaLabels.color(f.privacy)); contentDescription = MetaLabels.privacy(f.privacy) },
            lp(ctx.dp(7), ctx.dp(7)).apply { rightMargin = ctx.dp(8) })
        top.addView(UI.text(ctx, MetaLabels.field(f), TextStyle.CAPTION), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(top)
        row.addView(UI.text(ctx, MetaLabels.value(f), TextStyle.BODY).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadding(0, ctx.dp(2), 0, 0) })
        row.setOnClickListener { details(f) }
        return row
    }

    private fun details(f: MetaField) {
        val text = buildString {
            append(MetaLabels.value(f))
            append("\n\n")
            append(tr("Stored as {0}", "${f.group}:${f.name}"))
            if (f.raw != f.value) { append("\n"); append(tr("Bare value: {0}", f.raw)) }
            f.privacy?.let { append("\n"); append(MetaLabels.privacy(it)) }
        }
        AlertDialog.Builder(activity)
            .setTitle(MetaLabels.field(f))
            .setView(ScrollView(ctx).apply { addView(UI.text(ctx, text, TextStyle.BODY).apply { setTextIsSelectable(true); setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), ctx.dp(8)) }) })
            .setPositiveButton(tr("Copy")) { _, _ -> copy(MetaLabels.field(f), f.value); toast(tr("Copied.")) }
            .setNegativeButton(tr("Close"), null)
            .show()
    }

    private fun openMap(lat: Double, lon: Double) {
        val c = "%.6f,%.6f".format(java.util.Locale.ROOT, lat, lon)
        AlertDialog.Builder(activity)
            .setTitle(tr("Where this file was made"))
            .setMessage(c + "\n\n" + tr("Opening it starts your maps app; this app itself never connects to the internet."))
            .setPositiveButton(tr("Copy")) { _, _ -> copy(tr("Position"), c); toast(tr("Copied.")) }
            .setNeutralButton(tr("Open")) { _, _ ->
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$c?q=$c"))) } catch (_: Exception) { toast(tr("No app on this phone can handle this action.")) }
            }
            .setNegativeButton(tr("Close"), null)
            .show()
    }

    private fun menu(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) { m.menu.add(0, actions.size, actions.size, label); actions.add(action) }
        report?.let { r ->
            add(tr("Copy all fields")) { copy(source?.name ?: "", asText(r)); toast(tr("Copied.")) }
            add(tr("Share as text")) {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, asText(r)).putExtra(Intent.EXTRA_SUBJECT, source?.name ?: "")
                try { activity.startActivity(Intent.createChooser(i, tr("Share as text"))) } catch (_: Exception) { toast(tr("No app on this phone can handle this action.")) }
            }
        }
        add(tr("Choose another file")) { pick() }
        m.setOnMenuItemClickListener { item -> actions.getOrNull(item.itemId)?.invoke(); true }
        m.show()
    }

    private fun asText(r: MetaReport): String = buildString {
        append(source?.name ?: "").append('\n')
        var group = ""
        for (f in r.fields) {
            val g = MetaLabels.group(f.group)
            if (g != group) { group = g; append("\n[").append(g).append("]\n") }
            append(MetaLabels.field(f)).append(": ").append(f.value).append('\n')
        }
        if (r.notes.isNotEmpty()) { append('\n'); r.notes.forEach { append("• ").append(it).append('\n') } }
    }

    private fun copy(label: String, text: String) =
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))

    private fun toast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val MAX_ROWS = 3000
        private val FILE_SYSTEM = setOf("FileName", "FileSize", "FileModifyDate")

        /**
         * Reads [s]: a file the app has is read directly; a shared file is read through its open
         * descriptor (no copy, even for a long video), and copied only when a document needs a real file
         * or the descriptor can't seek (some cloud providers stream).
         */
        fun read(ctx: Context, s: Source): MetaReport {
            s.file?.let { return MetaReader.read(it, s.name, it.lastModified()) }
            val uri = s.uri ?: throw java.io.FileNotFoundException()
            if (uri.scheme == "file") uri.path?.let { p -> File(p).takeIf { it.isFile && it.canRead() }?.let { return MetaReader.read(it, s.name, it.lastModified()) } }
            val modified = modified(ctx, uri)
            val copyIt = {
                ReaderFiles.copy(ctx, ReaderSource(uri, s.name)) { }
            }
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException()
            pfd.use {
                if (pfd.statSize >= 0) {
                    val stream = FileInputStream(pfd.fileDescriptor)
                    val input = MetaInput(stream.channel, pfd.statSize, null, stream)
                    return input.use { MetaReader.read(it, s.name, modified, copyIt) }
                }
            }
            val copy = copyIt()
            try { return MetaReader.read(copy, s.name, modified) } finally { if (ReaderFiles.isTemporary(ctx, copy)) copy.delete() }
        }

        /** When the file was last changed, as the providing app says (0 when it doesn't). */
        private fun modified(ctx: Context, uri: Uri): Long = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use 0L
                val d = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val m = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                when {
                    d >= 0 && !c.isNull(d) -> c.getLong(d)
                    m >= 0 && !c.isNull(m) -> c.getLong(m) * 1000
                    else -> 0L
                }
            } ?: 0L
        } catch (_: Exception) { 0L }
    }
}
