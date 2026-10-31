package com.localmediatools.ui.reader

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.docs.core.Deck
import com.localmediatools.docs.core.DocFormat
import com.localmediatools.docs.core.DocFormats
import com.localmediatools.docs.core.FlowDocument
import com.localmediatools.docs.core.Opened
import com.localmediatools.docs.core.UnreadableException
import com.localmediatools.docs.core.Workbook
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Shows one document: copies it where the readers can seek in it, works out what it is from its
 * contents, reads it on a thread of its own and shows it as text, a spreadsheet or pages. Files are
 * only read; nothing is changed or sent anywhere.
 */
class ReaderScreen(activity: MainActivity, private val source: ReaderSource, private val persistent: Boolean = false) : Screen(activity), ReaderHost {
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var bar: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchCount: TextView
    private lateinit var body: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var contentsButton: View
    private lateinit var searchButton: View
    private var copy: File? = null
    private var opened: Opened? = null
    var content: ReaderContent? = null; private set
    var format: DocFormat? = null; private set
    private var pdfText: PdfText? = null
    private var deckPages: DeckPages? = null
    private var destroyed = false
    private var loadJob: Job? = null
    /** Set once the document is on screen (or failed), for tests. */
    var state = "opening"; private set

    override fun createView(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG) }
        // Top bar: back, name, search, contents, more.
        bar = UI.horizontal(ctx).apply { setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(6)) }
        bar.addView(UI.iconButton(ctx, R.drawable.ic_back, tr("Back")) { activity.navigator.back() })
        val names = UI.vertical(ctx).apply { setPadding(ctx.dp(4), 0, ctx.dp(4), 0) }
        title = UI.text(ctx, source.name, TextStyle.SUBTITLE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; isAccessibilityHeading = true }
        subtitle = UI.text(ctx, "", TextStyle.CAPTION).apply { maxLines = 1 }
        names.addView(title); names.addView(subtitle)
        bar.addView(names, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchButton = UI.iconButton(ctx, R.drawable.ic_search, tr("Search")) { openSearch() }.apply { visibility = View.GONE }
        contentsButton = UI.iconButton(ctx, R.drawable.ic_contents, tr("Contents")) { showContents() }.apply { visibility = View.GONE }
        bar.addView(searchButton); bar.addView(contentsButton)
        lateinit var moreButton: View
        moreButton = UI.iconButton(ctx, R.drawable.ic_more, tr("More")) { menu(moreButton) }
        bar.addView(moreButton)
        root.addView(bar, lp())
        // Search bar, shown instead of the top bar.
        searchBar = UI.horizontal(ctx).apply { setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(6)); visibility = View.GONE }
        searchBar.addView(UI.iconButton(ctx, R.drawable.ic_back, tr("Close search")) { closeSearch() })
        searchField = EditText(ctx).apply {
            hint = tr("Find in document")
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Palette.TEXT); setHintTextColor(Palette.TEXT_3)
            background = Shapes.rounded(ctx, Palette.SURFACE_2, 12f)
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            textSize = 15f
        }
        searchBar.addView(searchField, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchCount = UI.text(ctx, "", TextStyle.CAPTION).apply { setPadding(ctx.dp(8), 0, ctx.dp(4), 0); minWidth = ctx.dp(48); gravity = Gravity.CENTER }
        searchBar.addView(searchCount)
        searchBar.addView(UI.iconButton(ctx, R.drawable.ic_up, tr("Previous match")) { content?.searchStep(false) })
        searchBar.addView(UI.iconButton(ctx, R.drawable.ic_down, tr("Next match")) { content?.searchStep(true) })
        root.addView(searchBar, lp())
        banner = UI.vertical(ctx).apply { visibility = View.GONE }
        root.addView(banner, lp())
        body = FrameLayout(ctx)
        root.addView(body, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        wireSearch()
        loading(tr("Opening…"))
        load(null)
        return root
    }

    // ------------------------------------------------------------------ loading
    private fun loading(text: String) {
        body.removeAllViews()
        val col = UI.vertical(ctx).apply { gravity = Gravity.CENTER }
        col.addView(ProgressBar(ctx).apply { isIndeterminate = true }, lp(ctx.dp(40), ctx.dp(40)))
        col.addView(UI.text(ctx, text, TextStyle.BODY_2).apply { gravity = Gravity.CENTER; setPadding(ctx.dp(24), ctx.dp(14), ctx.dp(24), 0); tag = "status" }, lp())
        body.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun status(text: String) { body.findViewWithTag<TextView>("status")?.text = text }

    private fun failed(message: String) {
        state = "failed"
        body.removeAllViews()
        val col = UI.vertical(ctx, 24, 24).apply { gravity = Gravity.CENTER }
        col.addView(UI.iconTile(ctx, R.drawable.ic_warning, Palette.WARNING, 52, 26))
        col.addView(UI.text(ctx, tr("This file can't be shown"), TextStyle.TITLE).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(14), 0, 0) }, lp())
        col.addView(UI.text(ctx, message, TextStyle.BODY_2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, 0); tag = "error" }, lp())
        if (source.uri?.scheme == "content") col.addView(UI.secondaryButton(ctx, tr("Open in another app"), R.drawable.ic_open) { openElsewhere() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(18) })
        body.addView(ScrollView(ctx).apply { isFillViewport = true; addView(col) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun load(password: String?) {
        loadJob = scope.launch {
            try {
                val f = copy ?: withContext(Dispatchers.IO) {
                    ReaderFiles.copy(ctx, source) { done -> scope.launch { status(tr("Copying the file… {0}", Format.bytes(done))) } }
                }.also { copy = it }
                if (destroyed) return@launch
                val fmt = format ?: withContext(Dispatchers.IO) { DocFormats.detect(f, source.name) }
                if (fmt == null) { failed(tr("This kind of file isn't one the reader opens. It reads PDF, Word, Excel, PowerPoint, OpenDocument, RTF, EPUB, CSV and text files.")); return@launch }
                format = fmt
                subtitle.text = "${FormatLook.label(fmt)} · ${Format.bytes(f.length())}"
                withContext(Dispatchers.IO) { RecentFiles.opened(ctx, source, f, fmt, persistent) }
                status(tr("Reading…"))
                val view: ReaderContent = if (fmt == DocFormat.PDF) {
                    val (pages, search) = try {
                        onReaderThread { PdfOpen.open(ctx, f, password, ReaderFiles.openDir(ctx)) }
                    } catch (e: PasswordNeeded) {
                        askPassword(e.wrong)?.let { load(it) } ?: activity.navigator.back()
                        return@launch
                    }
                    if (destroyed) { search.close(); pages.close(); return@launch }
                    pdfText = search as? PdfText
                    PagesView(ctx, pages, search, this@ReaderScreen, PagesView.PageNoun.PAGE)
                } else {
                    val doc = onReaderThread { DocFormats.open(f, fmt, I18n.locale) }
                    if (destroyed) { doc.close(); return@launch }
                    opened = doc
                    when (doc) {
                        is FlowDocument -> FlowView(ctx, doc, this@ReaderScreen)
                        is Workbook -> SheetView(ctx, doc, this@ReaderScreen)
                        is Deck -> DeckPages(doc).let { d -> deckPages = d; PagesView(ctx, d, d, this@ReaderScreen, PagesView.PageNoun.SLIDE) }
                    }
                }
                show(view)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UnreadableException) {
                failed(e.message ?: tr("The file is damaged."))
            } catch (e: SecurityException) {
                failed(tr("This file is no longer available. Open it again from the app it came from."))
            } catch (e: java.io.FileNotFoundException) {
                failed(tr("This file is no longer available. Open it again from the app it came from."))
            } catch (e: Throwable) {
                failed(com.localmediatools.core.Errors.describe(e))
            }
        }
    }

    private fun show(v: ReaderContent) {
        content = v
        body.removeAllViews()
        body.addView(v.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        searchButton.visibility = View.VISIBLE
        contentsButton.visibility = if (v.contents().size > 1) View.VISIBLE else View.GONE
        (contentsButton as? android.widget.ImageView)?.contentDescription = v.contentsTitle
        opened?.title?.takeIf { it.isNotBlank() }?.let { t -> subtitle.text = "${t.take(80)} · ${subtitle.text}" }
        val notes = opened?.notes.orEmpty()
        banner.removeAllViews()
        if (notes.isNotEmpty()) {
            banner.visibility = View.VISIBLE
            val note = UI.note(ctx, notes.joinToString("\n"), UI.NoteKind.INFO)
            note.setOnClickListener { banner.visibility = View.GONE }
            banner.addView(note, lp().apply { setMargins(ctx.dp(12), 0, ctx.dp(12), ctx.dp(8)) })
            scope.launch { delay(9000); banner.visibility = View.GONE }
        }
        state = "shown"
    }

    private suspend fun askPassword(wrong: Boolean): String? = suspendCancellableCoroutine { c ->
        val field = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = tr("Password")
        }
        val box = UI.vertical(ctx, 20, 8).apply { addView(field, lp()) }
        var answered = false
        val d = AlertDialog.Builder(activity)
            .setTitle(if (wrong) tr("Wrong password") else tr("This PDF is locked"))
            .setMessage(if (wrong) tr("That password didn't open it. Try again.") else tr("Enter its password to read it. The password is used only to open the file on this phone."))
            .setView(box)
            .setPositiveButton(tr("Open")) { _, _ -> answered = true; c.resume(field.text.toString()) }
            .setNegativeButton(tr("Cancel")) { _, _ -> answered = true; c.resume(null) }
            .setOnDismissListener { if (!answered && c.isActive) c.resume(null) }
            .show()
        c.invokeOnCancellation { d.dismiss() }
    }

    // ------------------------------------------------------------------ search
    private var searchDelay: Job? = null

    private fun wireSearch() {
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDelay?.cancel()
                searchDelay = scope.launch { delay(350); runSearch() }
            }
        })
        searchField.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                searchDelay?.cancel()
                if (lastQuery == searchField.text.toString()) content?.searchStep(true) else runSearch()
                true
            } else false
        }
    }

    private var lastQuery = ""

    private fun runSearch() {
        val q = searchField.text.toString()
        lastQuery = q
        val c = content ?: return
        c.search(q) { st -> searchCount.text = when {
            st.busy -> st.progress ?: "…"
            q.isBlank() -> ""
            st.total == 0 -> tr("No matches")
            else -> tr("{0} of {1}", st.current, st.total)
        } }
    }

    fun openSearch() {
        if (content == null) return
        bar.visibility = View.GONE; searchBar.visibility = View.VISIBLE
        searchField.requestFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchField, 0)
    }

    private fun closeSearch() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchField.windowToken, 0)
        searchBar.visibility = View.GONE; bar.visibility = View.VISIBLE
        searchField.setText(""); lastQuery = ""; searchCount.text = ""
        content?.clearSearch()
    }

    /** Runs a search straight away (for tests). */
    fun searchNow(q: String) { openSearch(); searchField.setText(q); searchDelay?.cancel(); runSearch() }
    val searchStatus: CharSequence get() = searchCount.text

    override fun onBack(): Boolean {
        if (searchBar.visibility == View.VISIBLE) { closeSearch(); return true }
        return false
    }

    // ------------------------------------------------------------------ menu and dialogs
    private fun showContents() {
        val c = content ?: return
        val items = c.contents()
        if (items.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle(c.contentsTitle)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> c.goTo(items[which].second) }
            .setNegativeButton(tr("Close"), null)
            .show()
    }

    private fun menu(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        val c = content
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) { m.menu.add(0, actions.size, actions.size, label); actions.add(action) }
        if (c != null) {
            add(tr("Search")) { openSearch() }
            if (c.contents().size > 1) add(c.contentsTitle) { showContents() }
            add(tr("Zoom in")) { c.zoomBy(1.25f) }
            add(tr("Zoom out")) { c.zoomBy(0.8f) }
            deckPages?.let { d -> add(tr("Speaker notes")) { speakerNotes(d) } }
            pdfText?.let { t -> add(tr("Copy this page's text")) { copyPageText(t) } }
            add(tr("Print")) { print() }
        }
        if (source.uri?.scheme == "content") add(tr("Open in another app")) { openElsewhere() }
        add(tr("File information")) { info() }
        add(tr("All metadata")) { push(com.localmediatools.ui.meta.MetadataScreen(activity, com.localmediatools.ui.meta.MetadataScreen.Source(source.uri, copy ?: source.kept, source.name))) }
        m.setOnMenuItemClickListener { item -> actions.getOrNull(item.itemId)?.invoke(); true }
        m.show()
    }

    private fun currentPage(): Int = ((content as? PagesView)?.currentPage) ?: 0

    private fun speakerNotes(d: DeckPages) {
        val i = currentPage()
        val text = d.notes(i).ifBlank { tr("This slide has no speaker notes.") }
        AlertDialog.Builder(activity)
            .setTitle(tr("Speaker notes · slide {0}", i + 1))
            .setView(ScrollView(ctx).apply { addView(UI.text(ctx, text, TextStyle.BODY).apply { setTextIsSelectable(true); setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), ctx.dp(8)) }) })
            .setPositiveButton(tr("Close"), null)
            .show()
    }

    private fun copyPageText(t: PdfText) {
        val i = currentPage()
        scope.launch {
            val text = withContext(Dispatchers.IO) { t.pageText(i) }
            if (text.isBlank()) { toast(tr("This page has no text to copy (it may be a scanned picture).")); return@launch }
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(source.name, text))
            toast(tr("Text of page {0} copied.", i + 1))
        }
    }

    private fun print() {
        val f = copy ?: return
        // Files known by their name only (old Office files, text) keep a name that says what they are.
        val ext = when (format) {
            DocFormat.DOC -> "doc"; DocFormat.XLS -> "xls"; DocFormat.PPT -> "ppt"; DocFormat.CSV -> "csv"; DocFormat.TSV -> "tsv"
            DocFormat.MARKDOWN -> "md"; DocFormat.HTML -> "html"; DocFormat.JSON -> "json"; DocFormat.XML -> "xml"; DocFormat.TEXT -> "txt"
            else -> null
        }
        val name = Format.safeFileName(source.name).let { n -> if (ext != null && !n.lowercase(java.util.Locale.ROOT).endsWith(".$ext")) "$n.$ext" else n }
        scope.launch {
            // Printing goes on after this screen closes, so it gets a copy of its own.
            val shared = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                File(dir, "${System.currentTimeMillis()}-$name").also { f.copyTo(it, overwrite = true) }
            }
            val sel = com.localmediatools.ui.Selection.of(ToolId.PRINT)
            sel.clear(); sel.addUris(activity, listOf(Uri.fromFile(shared)))
            push(com.localmediatools.ui.print.PrintScreen(activity))
        }
    }

    private fun openElsewhere() {
        val uri = source.uri ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(Intent.createChooser(i, tr("Open with"))) } catch (_: Exception) { toast(tr("No app on this phone can handle this action.")) }
    }

    private fun info() {
        val f = copy
        val lines = ArrayList<String>()
        lines.add(source.name)
        format?.let { lines.add(FormatLook.label(it)) }
        f?.let { lines.add(Format.bytes(it.length())) }
        opened?.title?.takeIf { it.isNotBlank() }?.let { lines.add(tr("Title: {0}", it)) }
        content?.facts()?.let { lines.addAll(it) }
        val notes = opened?.notes.orEmpty()
        val text = lines.joinToString("\n") + if (notes.isNotEmpty()) "\n\n" + tr("Not shown:") + "\n" + notes.joinToString("\n") { "• $it" } else ""
        AlertDialog.Builder(activity)
            .setTitle(tr("File information"))
            .setMessage(text + "\n\n" + tr("Files are only read on this phone: nothing is changed or sent anywhere."))
            .setPositiveButton(tr("Close"), null)
            .show()
    }

    // ------------------------------------------------------------------ ReaderHost
    override fun link(target: String) {
        val t = target.trim()
        val web = t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("www.", true)
        AlertDialog.Builder(activity)
            .setTitle(tr("Link"))
            .setMessage(t + "\n\n" + if (web) tr("Opening it starts your browser; this app itself never connects to the internet.") else "")
            .setPositiveButton(tr("Copy")) { _, _ ->
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(tr("Link"), t))
                toast(tr("Link copied."))
            }
            .setNeutralButton(tr("Open")) { _, _ ->
                val uri = Uri.parse(if (t.startsWith("www.", true)) "https://$t" else t)
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast(tr("No app on this phone can handle this action.")) }
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    override fun toast(message: String) { Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        destroyed = true
        loadJob?.cancel()
        try { content?.close() } catch (_: Throwable) { }
        try { opened?.close() } catch (_: Throwable) { }
        copy?.let { f -> if (ReaderFiles.isTemporary(ctx, f)) f.delete() }
        super.onDestroy()
    }
}
