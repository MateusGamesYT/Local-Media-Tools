package com.localmediatools.ui

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.AppLanguage
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.OutputArea
import com.localmediatools.edit.Inpainters
import com.localmediatools.export.ExportManager
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.Language
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import com.localmediatools.stitch.TfliteEmbedder
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.ToolSection
import com.localmediatools.ui.editor.EditorMode
import com.localmediatools.ui.editor.EditorScreen
import com.localmediatools.ui.gallery.GalleryTab
import com.localmediatools.ui.tools.ToolScreens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens a tool: batch tools get their screen, editing tools ask for one photo first. */
object ToolLauncher {
    fun open(activity: MainActivity, t: ToolId) {
        val mode = when (t) {
            ToolId.PHOTO_EDITOR -> EditorMode.ADJUST
            ToolId.MAGIC_ERASER -> EditorMode.ERASE
            ToolId.BLUR_REDACT -> EditorMode.BLUR
            else -> null
        }
        if (t == ToolId.DUPLICATES) { activity.navigator.push(DuplicatesScreen(activity)); return }
        if (t == ToolId.TOOL_STACK) { activity.navigator.push(StackScreen(activity)); return }
        if (t == ToolId.READER) { activity.navigator.push(com.localmediatools.ui.reader.ReaderHomeScreen(activity)); return }
        if (t == ToolId.METADATA) { activity.navigator.push(com.localmediatools.ui.meta.MetadataScreen(activity)); return }
        if (mode == null) { activity.navigator.push(ToolScreens.create(activity, t)); return }
        activity.pickMedia(PickKind.IMAGES, multiple = false) { uris -> uris.firstOrNull()?.let { openEditor(activity, it, t, mode) } }
    }

    fun openEditor(activity: MainActivity, uri: Uri, t: ToolId, mode: EditorMode) {
        activity.lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { MediaProbe.describe(activity, uri) }
            val problem = ToolRules.issue(t, item)
            if (problem != null) { Toast.makeText(activity, "${item.name}: $problem", Toast.LENGTH_LONG).show(); return@launch }
            activity.navigator.push(EditorScreen(activity, item, t, mode))
        }
    }

    /** Extra words people search for. */
    private val keywords = mapOf(
        ToolId.MAGIC_ERASER to N_("remove object person people erase delete clean ai inpaint"),
        ToolId.PHOTO_EDITOR to N_("crop rotate straighten brightness contrast exposure saturation filter look edit adjust colour color warmth"),
        ToolId.BLUR_REDACT to N_("censor hide face plate privacy mosaic anonymize redact"),
        ToolId.TOOL_STACK to N_("stack chain pipeline workflow combine multiple steps several tools in a row automate batch then"),
        ToolId.BACKGROUND_REMOVER to N_("cut out cutout remove background transparent png sticker subject product ai"),
        ToolId.AUTO_ENHANCE to N_("auto fix improve brighten colour color one tap magic ai enhance"),
        ToolId.DENOISE to N_("noise noisy grain grainy denoise clean night dark low light iso raw dng cr2 nef arw video smooth"),
        ToolId.FACE_BLUR to N_("face faces blur pixelate hide anonymize people video privacy censor ai"),
        ToolId.DUPLICATES to N_("duplicate similar same copies clean storage space free gallery burst"),
        ToolId.MERGE_VIDEOS to N_("join combine concatenate clips append video"),
        ToolId.HIGHLIGHT_VIDEO to N_("highlight reel montage movie film slideshow memories trip holiday vacation event music recap edit auto"),
        ToolId.VIDEO_SPEED to N_("fast slow motion timelapse time lapse speed up hyperlapse"),
        ToolId.TRIM_VIDEO to N_("cut shorten clip rotate sideways"),
        ToolId.SPLIT_VIDEO to N_("cut parts segments whatsapp status"),
        ToolId.COMPRESS_VIDEO to N_("shrink smaller reduce size mp4 h264"),
        ToolId.COMPRESS_IMAGES to N_("shrink smaller reduce size jpg jpeg webp resize"),
        ToolId.CONVERT_IMAGES to N_("heic avif jpg jpeg png webp tiff format"),
        ToolId.MERGE_IMAGES to N_("collage combine join stack"),
        ToolId.STITCH to N_("panorama join overlap screenshots"),
        ToolId.WATERMARK to N_("logo brand copyright text"),
        ToolId.REMOVE_METADATA to N_("exif gps location privacy clean strip"),
        ToolId.METADATA to N_("exif xmp iptc id3 tags properties details info information inspect check gps location camera author privacy"),
        ToolId.EXTRACT_AUDIO to N_("mp3 m4a aac sound music video soundtrack rip"),
        ToolId.TRIM_AUDIO to N_("cut shorten ringtone clip song mp3 fade in out sound music voice"),
        ToolId.MERGE_AUDIO to N_("join combine concatenate songs mp3 crossfade mix playlist sound music"),
        ToolId.CONVERT_AUDIO to N_("mp3 m4a aac opus ogg flac wav format bitrate sound music"),
        ToolId.AUDIO_VOLUME to N_("normalize normalise loudness louder quieter gain lufs replaygain level boost sound music"),
        ToolId.AUDIO_SPEED to N_("tempo faster slower pitch key semitone transpose practice sound music voice"),
        ToolId.SPLIT_AUDIO to N_("cut parts tracks chapters album recording sound music"),
        ToolId.REMOVE_SILENCE to N_("silent pauses gaps trim voice note podcast lecture recording sound"),
        ToolId.REMOVE_AUDIO to N_("mute silent sound"),
        ToolId.PDF_SCANNER to N_("scan document camera paper"),
        ToolId.READER to N_("open view read viewer document pdf word docx doc excel xlsx xls spreadsheet powerpoint pptx ppt slides odt ods odp rtf epub ebook book csv txt text markdown"),
        ToolId.EXTRACT_PDF_PAGES to N_("split pdf pages select"),
        ToolId.VIDEO_TO_GIF to N_("animation meme"),
    )

    fun matches(t: ToolId, q: String): Boolean {
        val words = q.lowercase(I18n.locale).split(' ').filter { it.isNotBlank() }
        val kw = keywords[t] ?: ""
        // In the app's language and in English (the tool names people know may be the English ones).
        val hay = "${t.title} ${t.shortDescription} ${t.longDescription} ${t.section.title} ${if (kw.isEmpty()) "" else tr(kw)} ${t.englishTitle} $kw".lowercase(I18n.locale)
        return words.all { hay.contains(it) }
    }
}

/** App root: Tools / Gallery / Activity / Settings with a floating bottom bar. Tool screens open on top of it. */
class MainShell(activity: MainActivity) : Screen(activity) {
    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNav
    private val tabViews = HashMap<Int, View>()
    private var gallery: GalleryTab? = null
    var tab = 0; private set

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        content = FrameLayout(ctx)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        nav = BottomNav(ctx, listOf(
            Triple(tr("Tools"), R.drawable.ic_grid, tr("Tools")),
            Triple(tr("Gallery"), R.drawable.ic_gallery, tr("Gallery")),
            Triple(tr("Activity"), R.drawable.ic_activity, tr("Activity")),
            Triple(tr("Settings"), R.drawable.ic_settings, tr("Settings")),
        )) { show(it) }
        root.addView(nav, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(20), 0, ctx.dp(20), ctx.dp(14)) })
        show(0)
        scope.launch { ExportManager.state.collect { nav.setBadge(TAB_ACTIVITY, it.busy) } }
        return root
    }

    fun show(i: Int) {
        tab = i
        if (nav.selected != i) nav.select(i, notify = false)
        content.removeAllViews()
        val v = tabViews.getOrPut(i) {
            when (i) {
                TAB_GALLERY -> GalleryTab(this).also { gallery = it }.build()
                TAB_ACTIVITY -> ActivityTab(this).build()
                TAB_SETTINGS -> SettingsTab(this).build()
                else -> HomeTab(this).build()
            }
        }
        (v.parent as? FrameLayout)?.removeView(v)
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(160).start()
        if (i == TAB_GALLERY) gallery?.onShow()
    }

    override fun onShow() {
        // Back from a pushed screen, or from the system's permission settings.
        if (tab == TAB_GALLERY) gallery?.onShow()
    }

    override fun onBack(): Boolean {
        if (tab == TAB_GALLERY && gallery?.onBack() == true) return true
        if (tab != 0) { show(0); return true }
        return false
    }

    companion object {
        const val TAB_TOOLS = 0
        const val TAB_GALLERY = 1
        const val TAB_ACTIVITY = 2
        const val TAB_SETTINGS = 3
    }
}

/** Scrollable tab page with room for the floating bar. */
private fun tabPage(s: Screen): Pair<ScrollView, LinearLayout> {
    val ctx = s.ctx
    val scroll = ScrollView(ctx).apply { setBackgroundColor(Palette.BG); clipToPadding = false; isVerticalScrollBarEnabled = false }
    val col = UI.vertical(ctx)
    col.setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(120))
    // Take initial focus ourselves so the search field doesn't open the keyboard on launch.
    col.isFocusableInTouchMode = true
    col.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
    scroll.addView(col)
    return scroll to col
}

class HomeTab(private val shell: MainShell) {
    private val ctx = shell.ctx
    private lateinit var results: LinearLayout
    private lateinit var featured: View
    private var query = ""
    private var category: ToolSection? = null

    fun build(): View {
        val (scroll, col) = tabPage(shell)
        // Soft brand glow behind the header.
        scroll.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Palette.mix(Palette.BG, Palette.BRAND[0], 0.16f), Palette.BG, Palette.BG))

        val header = UI.horizontal(ctx)
        header.addView(ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        header.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(12), 0, 0, 0)
            addView(UI.text(ctx, "Local Media Tools", TextStyle.SUBTITLE).apply { isAccessibilityHeading = true })
            addView(UI.text(ctx, tr("Private · offline · on-device"), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(2), 0, 0) })
        }, lp(0, WRAP, 1f))
        header.addView(UI.horizontal(ctx).apply {
            background = Shapes.pill(ctx, Palette.withAlpha(Palette.SUCCESS, 0x1F))
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            addView(UI.iconView(ctx, R.drawable.ic_shield, Palette.SUCCESS, 15))
            addView(UI.text(ctx, tr("No uploads"), TextStyle.CAPTION, Palette.TEXT).apply { setPadding(ctx.dp(6), 0, 0, 0) })
        })
        col.addView(header)

        col.addView(UI.text(ctx, tr("Edit, convert and\nclean up — privately."), TextStyle.HERO), lp().apply { topMargin = ctx.dp(26) })
        col.addView(UI.text(ctx, trn(ToolId.toolCount, "{0} tool that runs entirely on this phone. No account, no cloud, no limits.", "{0} tools that run entirely on this phone. No account, no cloud, no limits."), TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(10) })

        col.addView(SearchField(ctx, trn(ToolId.toolCount, "Search {0} tool", "Search {0} tools")) { query = it.trim(); refresh() }, lp().apply { topMargin = ctx.dp(22) })
        val cats: List<ToolSection?> = listOf(null) + ToolSection.entries
        // Chips scroll edge to edge.
        col.addView(ChipRow(ctx, cats, { it?.title ?: tr("All") }, null) { category = it; refresh() }.apply {
            setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        }, lp().apply { topMargin = ctx.dp(14); leftMargin = -ctx.dp(18); rightMargin = -ctx.dp(18) })

        col.addView(ExportStatusCard(shell, null), lp().apply { topMargin = ctx.dp(16) })

        // "Edit & AI" block: two big cards plus a wide one (shown instead of that section in the full list).
        val block = UI.vertical(ctx)
        block.addView(sectionHeader(ctx, ToolSection.STACKS.title, ToolSection.STACKS.subtitle, Palette.section(ToolSection.STACKS)), lp().apply { bottomMargin = ctx.dp(12) })
        // Cards are at least their design height and grow when a language needs more lines.
        block.addView(FeatureCard(ctx, ToolId.TOOL_STACK, Palette.sectionGradient(ToolSection.STACKS), Badges.newTool) { ToolLauncher.open(shell.activity, ToolId.TOOL_STACK) }
            .apply { minimumHeight = ctx.dp(150) }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(28) })
        block.addView(sectionHeader(ctx, ToolSection.EDIT.title, ToolSection.EDIT.subtitle, Palette.section(ToolSection.EDIT)), lp().apply { bottomMargin = ctx.dp(12) })
        fun cards(a: Triple<ToolId, IntArray, String?>, b: Triple<ToolId, IntArray, String?>): View = UI.horizontal(ctx, Gravity.TOP).apply {
            // Both cards of a row take the taller one's height (match parent in a wrap-content row).
            addView(FeatureCard(ctx, a.first, a.second, a.third) { ToolLauncher.open(shell.activity, a.first) }.apply { minimumHeight = ctx.dp(176) },
                LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = ctx.dp(6) })
            addView(FeatureCard(ctx, b.first, b.second, b.third) { ToolLauncher.open(shell.activity, b.first) }.apply { minimumHeight = ctx.dp(176) },
                LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = ctx.dp(6) })
        }
        block.addView(cards(Triple(ToolId.MAGIC_ERASER, Palette.AI, Badges.ai), Triple(ToolId.BACKGROUND_REMOVER, Palette.BRAND, Badges.newTool)))
        block.addView(cards(Triple(ToolId.FACE_BLUR, Palette.TEAL, Badges.newTool), Triple(ToolId.AUTO_ENHANCE, Palette.SUNSET, Badges.newTool)), lp().apply { topMargin = ctx.dp(12) })
        block.addView(wideTile(ToolId.PHOTO_EDITOR), lp().apply { topMargin = ctx.dp(12) })
        block.addView(wideTile(ToolId.BLUR_REDACT), lp().apply { topMargin = ctx.dp(12) })
        block.addView(wideTile(ToolId.DENOISE), lp().apply { topMargin = ctx.dp(12) })
        featured = block
        col.addView(block, lp().apply { topMargin = ctx.dp(26) })

        results = UI.vertical(ctx)
        col.addView(results, lp())
        refresh()

        col.addView(UI.note(ctx, tr("Your photos, videos and documents are processed on this phone and never uploaded — the app never connects to servers on the internet (printing talks only to your printer, on your own network). Results are saved in LocalMediaTools folders inside Pictures, Movies, Music and Documents."), UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(26) })
        return scroll
    }

    /** Full-width compact tile (icon, text, chevron). */
    private fun wideTile(t: ToolId): View = UI.horizontal(ctx).apply {
        background = Shapes.clickable(ctx, Palette.SURFACE, 22f, Palette.STROKE)
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(12), ctx.dp(14))
        addView(UI.toolTile(ctx, t, 44, 24))
        addView(UI.titled(ctx, t.title, t.shortDescription).apply { setPadding(ctx.dp(12), 0, ctx.dp(8), 0) }, lp(0, WRAP, 1f))
        addView(UI.iconView(ctx, R.drawable.ic_chevron, Palette.TEXT_3, 18))
        isClickable = true; isFocusable = true
        contentDescription = "${t.title}. ${t.shortDescription}"
        setOnClickListener { ToolLauncher.open(shell.activity, t) }
    }

    private fun refresh() {
        results.removeAllViews()
        val filtering = query.isNotEmpty() || category != null
        featured.visibility = if (filtering) View.GONE else View.VISIBLE
        val tools = ToolId.entries.filter { (category == null || it.section == category) && (query.isEmpty() || ToolLauncher.matches(it, query)) }
        if (tools.isEmpty()) {
            results.addView(UI.vertical(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ctx.dp(24), ctx.dp(32), ctx.dp(24), ctx.dp(32))
                addView(UI.iconTile(ctx, R.drawable.ic_search, Palette.TEXT_2, 48, 24))
                addView(UI.text(ctx, tr("No tool matches \"{0}\"", query), TextStyle.SUBTITLE).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(12), 0, 0) })
                addView(UI.text(ctx, tr("Try words like crop, gps, mp3, heic or panorama."), TextStyle.BODY_2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0) })
            }, lp().apply { topMargin = ctx.dp(12) })
            return
        }
        val groups: List<ToolSection?> = if (query.isNotEmpty()) listOf(null)
            else tools.map { it.section }.distinct().filter { filtering || (it != ToolSection.EDIT && it != ToolSection.STACKS) }
        for (sec in groups) {
            val list = if (sec == null) tools else tools.filter { it.section == sec }
            if (sec != null) results.addView(sectionHeader(ctx, sec.title, sec.subtitle, Palette.section(sec)), lp().apply { topMargin = ctx.dp(28); bottomMargin = ctx.dp(12) })
            else results.addView(UI.label(ctx, trn(list.size, "{0} result", "{0} results")), lp().apply { topMargin = ctx.dp(22); bottomMargin = ctx.dp(10) })
            for (pair in list.chunked(2)) {
                val row = UI.horizontal(ctx, Gravity.TOP)
                for ((k, t) in pair.withIndex()) {
                    row.addView(ToolTile(ctx, t) { ToolLauncher.open(shell.activity, t) }, lp(0, MATCH, 1f).apply { if (k == 0) rightMargin = ctx.dp(6) else leftMargin = ctx.dp(6) })
                }
                // The filler must also be MATCH_PARENT: a WRAP child makes LinearLayout collapse the row to 0 height.
                if (pair.size == 1) row.addView(View(ctx), lp(0, MATCH, 1f).apply { leftMargin = ctx.dp(6) })
                results.addView(row, lp().apply { bottomMargin = ctx.dp(12) })
            }
        }
    }
}

class ActivityTab(private val shell: MainShell) {
    private val ctx = shell.ctx

    fun build(): View {
        val (scroll, col) = tabPage(shell)
        val head = UI.horizontal(ctx)
        head.addView(UI.vertical(ctx).apply {
            addView(UI.text(ctx, tr("Activity"), TextStyle.DISPLAY).apply { isAccessibilityHeading = true })
            addView(UI.text(ctx, tr("Exports keep running when you leave the app."), TextStyle.BODY_2).apply { setPadding(0, ctx.dp(6), 0, 0) })
        }, lp(0, WRAP, 1f))
        head.addView(UI.ghostButton(ctx, trc("empty the list", "Clear")) {
            AlertDialog.Builder(shell.activity).setTitle(tr("Clear export history?"))
                .setMessage(tr("Only the list is cleared; saved files stay on your phone."))
                .setPositiveButton(trc("empty the list", "Clear")) { _, _ -> ExportManager.clearHistory() }
                .setNegativeButton(tr("Cancel"), null).show()
        })
        col.addView(head, lp().apply { topMargin = ctx.dp(18) })
        col.addView(ExportStatusCard(shell, null), lp().apply { topMargin = ctx.dp(18) })
        col.addView(UI.label(ctx, tr("Recent exports")), lp().apply { topMargin = ctx.dp(22); bottomMargin = ctx.dp(10) })
        val list = UI.vertical(ctx)
        col.addView(list, lp())
        shell.scope.launch { ExportManager.state.collect { HistoryList.render(shell, list, it, tr("Start a tool and its results will be listed here, with files you can open or share.")) } }
        return scroll
    }
}

class SettingsTab(private val shell: MainShell) {
    private val ctx = shell.ctx

    private fun group(col: LinearLayout, title: String): LinearLayout {
        col.addView(UI.label(ctx, title), lp().apply { topMargin = ctx.dp(24); bottomMargin = ctx.dp(10) })
        val card = UI.vertical(ctx).apply { background = Shapes.rounded(ctx, Palette.SURFACE, 22f, Palette.STROKE); setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4)) }
        col.addView(card, lp())
        return card
    }

    fun build(): View {
        val (scroll, col) = tabPage(shell)
        col.addView(UI.text(ctx, tr("Settings"), TextStyle.DISPLAY).apply { isAccessibilityHeading = true }, lp().apply { topMargin = ctx.dp(18) })

        val language = group(col, tr("Language"))
        language.addView(listRow(ctx, R.drawable.ic_language, Palette.ACCENT, tr("App language"), languageName(AppLanguage.chosen(ctx))) { pickLanguage() })

        val perf = group(col, tr("Performance"))
        perf.addView(UI.vertical(ctx, 14, 12).apply {
            addView(UI.text(ctx, tr("How hard exports may work the phone. Lower keeps it cool and responsive; higher finishes sooner."), TextStyle.CAPTION))
            addView(WorkloadDialog.content(ctx, withNote = false), lp().apply { topMargin = ctx.dp(10) })
        })

        val ai = group(col, tr("On-device AI"))
        val (eraserOk, eraserWhy) = Inpainters.aiAvailability(ctx)
        ai.addView(listRow(ctx, R.drawable.ic_wand, Palette.section(ToolSection.EDIT), tr("Magic eraser model"),
            if (eraserOk) tr("MI-GAN · runs on this phone · ready") else tr("Unavailable: {0} Basic fill is used instead.", eraserWhy)))
        val (stitchOk, stitchWhy) = TfliteEmbedder.availability(ctx)
        ai.addView(listRow(ctx, R.drawable.ic_sparkle, Palette.section(ToolSection.IMAGES), tr("Stitcher alignment assist"),
            if (stitchOk) tr("MobileNet-V3 · runs on this phone · ready") else stitchWhy))

        val gal = group(col, tr("Gallery"))
        gal.addView(UI.vertical(ctx, 14, 4).apply {
            addView(ToggleRow(ctx, tr("Organise in the background"), tr("Finds people and things in new photos on this phone so you can search them. Slows down when the phone is warm; pauses when it's very hot or the battery is low."),
                !com.localmediatools.gallery.GalleryIndex.isPausedByUser(ctx)) { on -> com.localmediatools.gallery.GalleryIndex.setPaused(ctx, !on) })
        })
        gal.addView(listRow(ctx, R.drawable.ic_sparkle, Palette.ACCENT, tr("Recognition models"),
            tr("EfficientDet-Lite2 and EfficientNetV2 (things and places), YuNet and ResNet-50 (faces and people) · all on this phone")))

        val storage = group(col, tr("Storage"))
        val folders = OutputArea.entries.map { it.displayPath.substringBefore("/LocalMediaTools") }.distinct().joinToString(", ")
        storage.addView(listRow(ctx, R.drawable.ic_folder, Palette.section(ToolSection.DOCUMENTS), tr("Where results go"), tr("LocalMediaTools folders in {0}", folders)))
        val cacheRow = listRow(ctx, R.drawable.ic_trash, Palette.WARNING, tr("Clear temporary files"), tr("Calculating…")) { clearCache() }
        storage.addView(cacheRow)
        shell.scope.launch {
            val size = withContext(Dispatchers.IO) { dirSize(ctx.cacheDir) }
            ((cacheRow.getChildAt(1) as LinearLayout).getChildAt(1) as? TextView)?.text = tr("{0} in the app's cache · your files are not affected", Format.bytes(size))
        }

        val privacy = group(col, tr("Privacy"))
        privacy.addView(listRow(ctx, R.drawable.ic_shield, Palette.SUCCESS, tr("Nothing goes to the internet"), tr("The app never connects to servers on the internet; printing talks only to printers on your own network.")))
        privacy.addView(listRow(ctx, R.drawable.ic_lock, Palette.SUCCESS, tr("Originals are never changed"), tr("Every tool writes new files and checks them before saving.")))

        val about = group(col, tr("About"))
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" }
        about.addView(listRow(ctx, R.drawable.ic_info, Palette.ACCENT, "Local Media Tools $version", trn(ToolId.toolCount, "{0} tool", "{0} tools") + " · Android ${android.os.Build.VERSION.RELEASE}"))
        about.addView(listRow(ctx, R.drawable.ic_file, Palette.ACCENT, tr("Open-source licences"), "OpenCV, TensorFlow Lite, MI-GAN, YuNet, InsightFace, EfficientDet, EfficientNetV2…") { shell.push(LicensesScreen(shell.activity)) })
        return scroll
    }

    private fun languageName(l: Language?): String = l?.nativeName ?: tr("Phone's language ({0})", AppLanguage.phoneLanguage().nativeName)

    private fun pickLanguage() {
        val chosen = AppLanguage.chosen(ctx)
        val options: List<Language?> = listOf(null) + Language.entries
        AlertDialog.Builder(shell.activity).setTitle(tr("App language"))
            .setSingleChoiceItems(options.map { languageName(it) }.toTypedArray(), options.indexOf(chosen)) { d, which ->
                d.dismiss()
                if (options[which] == chosen) return@setSingleChoiceItems
                AppLanguage.set(ctx, options[which])
                shell.activity.restartForLanguage()
            }
            .setNegativeButton(tr("Cancel"), null).show()
    }

    private fun dirSize(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) } else f.length()

    private fun clearCache() {
        AlertDialog.Builder(shell.activity).setTitle(tr("Clear temporary files?"))
            .setMessage(if (ExportManager.state.value.busy) tr("An export is running; temporary files will be cleared after it finishes. Try again then.") else tr("Thumbnails and leftovers from interrupted exports are removed. Your saved files are not affected."))
            .setPositiveButton(trc("delete files", "Clear")) { _, _ ->
                if (ExportManager.state.value.busy) return@setPositiveButton
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                Toast.makeText(ctx, tr("Temporary files cleared"), Toast.LENGTH_SHORT).show()
                shell.show(MainShell.TAB_SETTINGS)
            }
            .setNegativeButton(tr("Cancel"), null).show()
    }
}

/** Third-party components and their licences. */
class LicensesScreen(activity: MainActivity) : Screen(activity) {
    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        root.addView(TopBar(this, tr("Open-source licences")))
        val body = UI.vertical(ctx, 16, 4)
        val items = listOf(
            Triple("OpenCV 4.12", "Apache License 2.0", tr("Image alignment for the stitcher and basic fill.")),
            Triple("PdfBox-Android 2.0.27", "Apache License 2.0", tr("Merging PDFs and extracting pages.")),
            Triple("TensorFlow Lite 2.16", "Apache License 2.0", tr("Runs the on-device AI models.")),
            Triple("MI-GAN (Picsart AI Research)", "MIT License", tr("Magic eraser model, converted to TensorFlow Lite.")),
            Triple("MobileNet-V3 image embedder (MediaPipe)", "Apache License 2.0", tr("Stitcher alignment assist and similar-photo detection.")),
            Triple("YuNet face detector (OpenCV Zoo)", "MIT License", tr("Finding faces for face blur, the Gallery and auto enhance.")),
            Triple("ResNet-50 face recognition (InsightFace, w600k_r50)", tr("Non-commercial research use only (code MIT)"), tr("Recognising the people in the Gallery.")),
            Triple("MobileFaceNet face recognition (InsightFace, w600k_mbf)", tr("Non-commercial research use only (code MIT)"), tr("Recognising the same person across video frames and files for face blur.")),
            Triple("U²-Net-p (Qin et al.) via rembg", "Apache License 2.0 · MIT License", tr("Background remover.")),
            Triple("EfficientDet-Lite2 (MediaPipe)", "Apache License 2.0", tr("Finding people, animals, vehicles and objects in the gallery.")),
            Triple("EfficientNetV2-B3, ImageNet-21k (Google AutoML)", "Apache License 2.0", tr("Recognising scenes and kinds of things in the gallery and for auto enhance.")),
            Triple("MusicVAE trio model (Magenta, Google)", "Apache License 2.0", tr("Writing the instrumental background music for highlight videos, on the phone.")),
            Triple("WordNet 3.0 (Princeton University)", "WordNet License", tr("Grouping the classifier's classes into searchable categories.")),
            Triple("Open Images V7 annotations (Google)", "CC BY 4.0", tr("Human-verified labels used to calibrate and train the category recognisers.")),
            Triple("Kotlin & kotlinx.coroutines", "Apache License 2.0", tr("Language runtime.")),
            Triple("Inter typeface", "SIL Open Font License 1.1", tr("App typography.")),
            Triple("Carlito, Caladea and Liberation fonts", "SIL Open Font License 1.1", tr("Showing and printing documents with the widths of Calibri, Cambria, Arial, Times New Roman and Courier New, so lines break as on a computer.")),
        )
        for ((name, lic, what) in items) {
            body.addView(UI.card(ctx, 14).apply {
                addView(UI.text(ctx, name, TextStyle.SUBTITLE))
                addView(UI.text(ctx, lic, TextStyle.CAPTION, Palette.ACCENT).apply { setPadding(0, ctx.dp(3), 0, 0) })
                addView(UI.text(ctx, what, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(3), 0, 0) })
            }, lp().apply { bottomMargin = ctx.dp(10) })
        }
        for (f in listOf("licenses/MI-GAN-MIT.txt", "licenses/YuNet-MIT.txt", "licenses/InsightFace-models.txt", "licenses/U2Net-Apache-2.0.txt", "licenses/rembg-MIT.txt",
                "licenses/EfficientDet-Apache-2.0.txt", "licenses/EfficientNetV2-Apache-2.0.txt", "licenses/MusicVAE-Apache-2.0.txt", "licenses/WordNet.txt", "licenses/OpenImages-CC-BY-4.0.txt", "licenses/Inter-OFL.txt", "licenses/Document-fonts-OFL.txt")) {
            val text = try { ctx.assets.open(f).bufferedReader().readText() } catch (_: Exception) { continue }
            body.addView(UI.label(ctx, f.substringAfter('/').removeSuffix(".txt")), lp().apply { topMargin = ctx.dp(16); bottomMargin = ctx.dp(6) })
            body.addView(UI.text(ctx, text, TextStyle.CAPTION).apply { setTextIsSelectable(true) })
        }
        body.addView(UI.spacer(ctx, 32))
        root.addView(ScrollView(ctx).apply { addView(body) }, lp(MATCH, 0, 1f))
        return root
    }
}
