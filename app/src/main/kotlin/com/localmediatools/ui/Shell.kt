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
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.OutputArea
import com.localmediatools.edit.Inpainters
import com.localmediatools.export.ExportManager
import com.localmediatools.stitch.TfliteEmbedder
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.ToolSection
import com.localmediatools.ui.editor.EditorMode
import com.localmediatools.ui.editor.EditorScreen
import com.localmediatools.ui.gallery.GalleryTab
import com.localmediatools.ui.tools.ToolScreens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        ToolId.MAGIC_ERASER to "remove object person people erase delete clean ai inpaint",
        ToolId.PHOTO_EDITOR to "crop rotate straighten brightness contrast exposure saturation filter look edit adjust colour color warmth",
        ToolId.BLUR_REDACT to "censor hide face plate privacy mosaic anonymize redact",
        ToolId.TOOL_STACK to "stack chain pipeline workflow combine multiple steps several tools in a row automate batch then",
        ToolId.BACKGROUND_REMOVER to "cut out cutout remove background transparent png sticker subject product ai",
        ToolId.AUTO_ENHANCE to "auto fix improve brighten colour color one tap magic ai enhance",
        ToolId.FACE_BLUR to "face faces blur pixelate hide anonymize people video privacy censor ai",
        ToolId.DUPLICATES to "duplicate similar same copies clean storage space free gallery burst",
        ToolId.MERGE_VIDEOS to "join combine concatenate clips append video",
        ToolId.VIDEO_SPEED to "fast slow motion timelapse time lapse speed up hyperlapse",
        ToolId.TRIM_VIDEO to "cut shorten clip rotate sideways",
        ToolId.SPLIT_VIDEO to "cut parts segments whatsapp status",
        ToolId.COMPRESS_VIDEO to "shrink smaller reduce size mp4 h264",
        ToolId.COMPRESS_IMAGES to "shrink smaller reduce size jpg jpeg webp resize",
        ToolId.CONVERT_IMAGES to "heic avif jpg jpeg png webp tiff format",
        ToolId.MERGE_IMAGES to "collage combine join stack",
        ToolId.STITCH to "panorama join overlap screenshots",
        ToolId.WATERMARK to "logo brand copyright text",
        ToolId.REMOVE_METADATA to "exif gps location privacy clean strip",
        ToolId.EXTRACT_AUDIO to "mp3 m4a sound music",
        ToolId.REMOVE_AUDIO to "mute silent sound",
        ToolId.PDF_SCANNER to "scan document camera paper",
        ToolId.EXTRACT_PDF_PAGES to "split pdf pages select",
        ToolId.VIDEO_TO_GIF to "animation meme",
    )

    fun matches(t: ToolId, q: String): Boolean {
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        val hay = "${t.title} ${t.shortDescription} ${t.longDescription} ${t.section.title} ${keywords[t] ?: ""}".lowercase()
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
            Triple("Tools", R.drawable.ic_grid, "Tools"),
            Triple("Gallery", R.drawable.ic_gallery, "Gallery"),
            Triple("Activity", R.drawable.ic_activity, "Activity"),
            Triple("Settings", R.drawable.ic_settings, "Settings"),
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
            addView(UI.text(ctx, "Private · offline · on-device", TextStyle.CAPTION).apply { setPadding(0, ctx.dp(2), 0, 0) })
        }, lp(0, WRAP, 1f))
        header.addView(UI.horizontal(ctx).apply {
            background = Shapes.pill(ctx, Palette.withAlpha(Palette.SUCCESS, 0x1F))
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            addView(UI.iconView(ctx, R.drawable.ic_shield, Palette.SUCCESS, 15))
            addView(UI.text(ctx, "No uploads", TextStyle.CAPTION, Palette.TEXT).apply { setPadding(ctx.dp(6), 0, 0, 0) })
        })
        col.addView(header)

        col.addView(UI.text(ctx, "Edit, convert and\nclean up — privately.", TextStyle.HERO), lp().apply { topMargin = ctx.dp(26) })
        col.addView(UI.text(ctx, "${ToolId.toolCount} tools that run entirely on this phone. No account, no internet, no limits.", TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(10) })

        col.addView(SearchField(ctx, "Search ${ToolId.toolCount} tools") { query = it.trim(); refresh() }, lp().apply { topMargin = ctx.dp(22) })
        val cats: List<ToolSection?> = listOf(null) + ToolSection.entries
        // Chips scroll edge to edge.
        col.addView(ChipRow(ctx, cats, { it?.title ?: "All" }, null) { category = it; refresh() }.apply {
            setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        }, lp().apply { topMargin = ctx.dp(14); leftMargin = -ctx.dp(18); rightMargin = -ctx.dp(18) })

        col.addView(ExportStatusCard(shell, null), lp().apply { topMargin = ctx.dp(16) })

        // "Edit & AI" block: two big cards plus a wide one (shown instead of that section in the full list).
        val block = UI.vertical(ctx)
        block.addView(sectionHeader(ctx, ToolSection.STACKS.title, ToolSection.STACKS.subtitle, Palette.section(ToolSection.STACKS)), lp().apply { bottomMargin = ctx.dp(12) })
        block.addView(FeatureCard(ctx, ToolId.TOOL_STACK, Palette.sectionGradient(ToolSection.STACKS), "NEW") { ToolLauncher.open(shell.activity, ToolId.TOOL_STACK) },
            LinearLayout.LayoutParams(MATCH, ctx.dp(150)).apply { bottomMargin = ctx.dp(28) })
        block.addView(sectionHeader(ctx, ToolSection.EDIT.title, ToolSection.EDIT.subtitle, Palette.section(ToolSection.EDIT)), lp().apply { bottomMargin = ctx.dp(12) })
        fun cards(a: Triple<ToolId, IntArray, String?>, b: Triple<ToolId, IntArray, String?>): View = UI.horizontal(ctx, Gravity.TOP).apply {
            addView(FeatureCard(ctx, a.first, a.second, a.third) { ToolLauncher.open(shell.activity, a.first) }, LinearLayout.LayoutParams(0, ctx.dp(176), 1f).apply { rightMargin = ctx.dp(6) })
            addView(FeatureCard(ctx, b.first, b.second, b.third) { ToolLauncher.open(shell.activity, b.first) }, LinearLayout.LayoutParams(0, ctx.dp(176), 1f).apply { leftMargin = ctx.dp(6) })
        }
        block.addView(cards(Triple(ToolId.MAGIC_ERASER, Palette.AI, "AI"), Triple(ToolId.BACKGROUND_REMOVER, Palette.BRAND, "NEW")))
        block.addView(cards(Triple(ToolId.FACE_BLUR, Palette.TEAL, "NEW"), Triple(ToolId.AUTO_ENHANCE, Palette.SUNSET, "NEW")), lp().apply { topMargin = ctx.dp(12) })
        block.addView(wideTile(ToolId.PHOTO_EDITOR), lp().apply { topMargin = ctx.dp(12) })
        block.addView(wideTile(ToolId.BLUR_REDACT), lp().apply { topMargin = ctx.dp(12) })
        featured = block
        col.addView(block, lp().apply { topMargin = ctx.dp(26) })

        results = UI.vertical(ctx)
        col.addView(results, lp())
        refresh()

        col.addView(UI.note(ctx, "Your photos, videos and documents are processed on this phone and never uploaded — the app doesn't even have internet permission. Results are saved in LocalMediaTools folders inside Pictures, Movies, Music and Documents.", UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(26) })
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
                addView(UI.text(ctx, "No tool matches \"$query\"", TextStyle.SUBTITLE).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(12), 0, 0) })
                addView(UI.text(ctx, "Try words like crop, gps, mp3, heic or panorama.", TextStyle.BODY_2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0) })
            }, lp().apply { topMargin = ctx.dp(12) })
            return
        }
        val groups: List<ToolSection?> = if (query.isNotEmpty()) listOf(null)
            else tools.map { it.section }.distinct().filter { filtering || (it != ToolSection.EDIT && it != ToolSection.STACKS) }
        for (sec in groups) {
            val list = if (sec == null) tools else tools.filter { it.section == sec }
            if (sec != null) results.addView(sectionHeader(ctx, sec.title, sec.subtitle, Palette.section(sec)), lp().apply { topMargin = ctx.dp(28); bottomMargin = ctx.dp(12) })
            else results.addView(UI.label(ctx, "${list.size} result${if (list.size == 1) "" else "s"}"), lp().apply { topMargin = ctx.dp(22); bottomMargin = ctx.dp(10) })
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
            addView(UI.text(ctx, "Activity", TextStyle.DISPLAY).apply { isAccessibilityHeading = true })
            addView(UI.text(ctx, "Exports keep running when you leave the app.", TextStyle.BODY_2).apply { setPadding(0, ctx.dp(6), 0, 0) })
        }, lp(0, WRAP, 1f))
        head.addView(UI.ghostButton(ctx, "Clear") {
            AlertDialog.Builder(shell.activity).setTitle("Clear export history?")
                .setMessage("Only the list is cleared; saved files stay on your phone.")
                .setPositiveButton("Clear") { _, _ -> ExportManager.clearHistory() }
                .setNegativeButton("Cancel", null).show()
        })
        col.addView(head, lp().apply { topMargin = ctx.dp(18) })
        col.addView(ExportStatusCard(shell, null), lp().apply { topMargin = ctx.dp(18) })
        col.addView(UI.label(ctx, "Recent exports"), lp().apply { topMargin = ctx.dp(22); bottomMargin = ctx.dp(10) })
        val list = UI.vertical(ctx)
        col.addView(list, lp())
        shell.scope.launch { ExportManager.state.collect { HistoryList.render(shell, list, it, "Start a tool and its results will be listed here, with files you can open or share.") } }
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
        col.addView(UI.text(ctx, "Settings", TextStyle.DISPLAY).apply { isAccessibilityHeading = true }, lp().apply { topMargin = ctx.dp(18) })

        val perf = group(col, "Performance")
        perf.addView(UI.vertical(ctx, 14, 12).apply {
            addView(UI.text(ctx, "How hard exports may work the phone. Lower keeps it cool and responsive; higher finishes sooner.", TextStyle.CAPTION))
            addView(WorkloadDialog.content(ctx, withNote = false), lp().apply { topMargin = ctx.dp(10) })
        })

        val ai = group(col, "On-device AI")
        val (eraserOk, eraserWhy) = Inpainters.aiAvailability(ctx)
        ai.addView(listRow(ctx, R.drawable.ic_wand, Palette.section(ToolSection.EDIT), "Magic eraser model",
            if (eraserOk) "MI-GAN · runs on this phone · ready" else "Unavailable: $eraserWhy Basic fill is used instead."))
        val (stitchOk, stitchWhy) = TfliteEmbedder.availability(ctx)
        ai.addView(listRow(ctx, R.drawable.ic_sparkle, Palette.section(ToolSection.IMAGES), "Stitcher alignment assist",
            if (stitchOk) "MobileNet-V3 · runs on this phone · ready" else stitchWhy))

        val gal = group(col, "Gallery")
        gal.addView(UI.vertical(ctx, 14, 4).apply {
            addView(ToggleRow(ctx, "Organise in the background", "Finds people and things in new photos on this phone so you can search them. Pauses on low battery or when the phone is hot.",
                !com.localmediatools.gallery.GalleryIndex.isPausedByUser(ctx)) { on -> com.localmediatools.gallery.GalleryIndex.setPaused(ctx, !on) })
        })
        gal.addView(listRow(ctx, R.drawable.ic_sparkle, Palette.ACCENT, "Recognition models",
            "EfficientDet-Lite2 and EfficientNetV2 (things and places), YuNet and SFace (faces) · all on this phone"))

        val storage = group(col, "Storage")
        val folders = OutputArea.entries.map { it.displayPath.substringBefore("/LocalMediaTools") }.distinct().joinToString(", ")
        storage.addView(listRow(ctx, R.drawable.ic_folder, Palette.section(ToolSection.DOCUMENTS), "Where results go", "LocalMediaTools folders in $folders"))
        val cacheRow = listRow(ctx, R.drawable.ic_trash, Palette.WARNING, "Clear temporary files", "Calculating…") { clearCache() }
        storage.addView(cacheRow)
        shell.scope.launch {
            val size = withContext(Dispatchers.IO) { dirSize(ctx.cacheDir) }
            ((cacheRow.getChildAt(1) as LinearLayout).getChildAt(1) as? TextView)?.text = "${Format.bytes(size)} in the app's cache · your files are not affected"
        }

        val privacy = group(col, "Privacy")
        privacy.addView(listRow(ctx, R.drawable.ic_shield, Palette.SUCCESS, "No internet access", "The app has no internet permission, so nothing can be uploaded."))
        privacy.addView(listRow(ctx, R.drawable.ic_lock, Palette.SUCCESS, "Originals are never changed", "Every tool writes new files and checks them before saving."))

        val about = group(col, "About")
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" }
        about.addView(listRow(ctx, R.drawable.ic_info, Palette.ACCENT, "Local Media Tools $version", "${ToolId.toolCount} tools · Android ${android.os.Build.VERSION.RELEASE}"))
        about.addView(listRow(ctx, R.drawable.ic_file, Palette.ACCENT, "Open-source licences", "OpenCV, TensorFlow Lite, MI-GAN, YuNet, SFace, EfficientDet, EfficientNetV2…") { shell.push(LicensesScreen(shell.activity)) })
        return scroll
    }

    private fun dirSize(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) } else f.length()

    private fun clearCache() {
        AlertDialog.Builder(shell.activity).setTitle("Clear temporary files?")
            .setMessage(if (ExportManager.state.value.busy) "An export is running; temporary files will be cleared after it finishes. Try again then." else "Thumbnails and leftovers from interrupted exports are removed. Your saved files are not affected.")
            .setPositiveButton("Clear") { _, _ ->
                if (ExportManager.state.value.busy) return@setPositiveButton
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                Toast.makeText(ctx, "Temporary files cleared", Toast.LENGTH_SHORT).show()
                shell.show(MainShell.TAB_SETTINGS)
            }
            .setNegativeButton("Cancel", null).show()
    }
}

/** Third-party components and their licences. */
class LicensesScreen(activity: MainActivity) : Screen(activity) {
    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        root.addView(TopBar(this, "Open-source licences"))
        val body = UI.vertical(ctx, 16, 4)
        val items = listOf(
            Triple("OpenCV 4.12", "Apache License 2.0", "Image alignment for the stitcher and basic fill."),
            Triple("PdfBox-Android 2.0.27", "Apache License 2.0", "Merging PDFs and extracting pages."),
            Triple("TensorFlow Lite 2.16", "Apache License 2.0", "Runs the on-device AI models."),
            Triple("MI-GAN (Picsart AI Research)", "MIT License", "Magic eraser model, converted to TensorFlow Lite."),
            Triple("MobileNet-V3 image embedder (MediaPipe)", "Apache License 2.0", "Stitcher alignment assist and similar-photo detection."),
            Triple("YuNet face detector (OpenCV Zoo)", "MIT License", "Finding faces for face blur and auto enhance."),
            Triple("SFace face recognition (OpenCV Zoo)", "Apache License 2.0", "Recognising the same person across frames and files."),
            Triple("U²-Net-p (Qin et al.) via rembg", "Apache License 2.0 · MIT License", "Background remover."),
            Triple("EfficientDet-Lite2 (MediaPipe)", "Apache License 2.0", "Finding people, animals, vehicles and objects in the gallery."),
            Triple("EfficientNetV2-B3, ImageNet-21k (Google AutoML)", "Apache License 2.0", "Recognising scenes and kinds of things in the gallery and for auto enhance."),
            Triple("WordNet 3.0 (Princeton University)", "WordNet License", "Grouping the classifier's classes into searchable categories."),
            Triple("Open Images V7 annotations (Google)", "CC BY 4.0", "Human-verified labels used to calibrate and train the category recognisers."),
            Triple("Kotlin & kotlinx.coroutines", "Apache License 2.0", "Language runtime."),
            Triple("Inter typeface", "SIL Open Font License 1.1", "App typography."),
        )
        for ((name, lic, what) in items) {
            body.addView(UI.card(ctx, 14).apply {
                addView(UI.text(ctx, name, TextStyle.SUBTITLE))
                addView(UI.text(ctx, lic, TextStyle.CAPTION, Palette.ACCENT).apply { setPadding(0, ctx.dp(3), 0, 0) })
                addView(UI.text(ctx, what, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(3), 0, 0) })
            }, lp().apply { bottomMargin = ctx.dp(10) })
        }
        for (f in listOf("licenses/MI-GAN-MIT.txt", "licenses/YuNet-MIT.txt", "licenses/SFace-Apache-2.0.txt", "licenses/U2Net-Apache-2.0.txt", "licenses/rembg-MIT.txt",
                "licenses/EfficientDet-Apache-2.0.txt", "licenses/EfficientNetV2-Apache-2.0.txt", "licenses/WordNet.txt", "licenses/OpenImages-CC-BY-4.0.txt", "licenses/Inter-OFL.txt")) {
            val text = try { ctx.assets.open(f).bufferedReader().readText() } catch (_: Exception) { continue }
            body.addView(UI.label(ctx, f.substringAfter('/').removeSuffix(".txt")), lp().apply { topMargin = ctx.dp(16); bottomMargin = ctx.dp(6) })
            body.addView(UI.text(ctx, text, TextStyle.CAPTION).apply { setTextIsSelectable(true) })
        }
        body.addView(UI.spacer(ctx, 32))
        root.addView(ScrollView(ctx).apply { addView(body) }, lp(MATCH, 0, 1f))
        return root
    }
}
