package com.localmediatools.ui.tools

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Errors
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.edit.EditRenderer
import com.localmediatools.edit.EditState
import com.localmediatools.export.ExportJob
import com.localmediatools.image.ImageSource
import com.localmediatools.tools.AutoEnhanceJob
import com.localmediatools.tools.CutoutBackground
import com.localmediatools.tools.EnhanceStrength
import com.localmediatools.tools.FaceBlurJob
import com.localmediatools.tools.FaceBlurPlan
import com.localmediatools.tools.RemoveBackgroundJob
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.FlowLayout
import com.localmediatools.ui.MATCH
import com.localmediatools.ui.Palette
import com.localmediatools.ui.ProgressBarView
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.WRAP
import com.localmediatools.ui.badge
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.vision.FaceScanResult
import com.localmediatools.vision.FaceScanner
import com.localmediatools.vision.VisionOps
import com.localmediatools.vision.core.FaceIdentity
import com.localmediatools.vision.core.Matting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A round copy of a (square) face picture. */
private fun circle(b: Bitmap): Bitmap {
    val s = minOf(b.width, b.height)
    val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    p.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    android.graphics.Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, p)
    return out
}

/** A light/dark checkerboard behind transparent previews. */
private fun checker(x: Int, y: Int) = if (((x / 12) + (y / 12)) % 2 == 0) 0xFFDADDE3.toInt() else 0xFFF4F5F7.toInt()

private fun previewBox(ctx: android.content.Context): ImageView = ImageView(ctx).apply {
    scaleType = ImageView.ScaleType.FIT_CENTER
    background = Shapes.rounded(ctx, Palette.SURFACE_2, 16f)
    clipToOutline = true
}

// =========================================================================== Background remover
object CutoutPrefs {
    var background = CutoutBackground.TRANSPARENT
    var crop = true
}

class BackgroundRemoverScreen(a: MainActivity) : ToolScreen(a, ToolId.BACKGROUND_REMOVER) {
    private lateinit var preview: ImageView
    private lateinit var note: TextView
    private var previewKey: String? = null
    private var previewJob: Job? = null
    private var base: Bitmap? = null
    private var mask: Triple<FloatArray, Int, Int>? = null

    override fun buildOptions(container: LinearLayout) {
        val head = UI.horizontal(ctx)
        head.addView(UI.text(ctx, "Preview", TextStyle.SUBTITLE), lp(0, WRAP, 1f))
        head.addView(badge(ctx, "AI"))
        container.addView(head, lp().apply { topMargin = ctx.dp(4) })
        preview = previewBox(ctx)
        container.addView(preview, LinearLayout.LayoutParams(MATCH, ctx.dp(240)).apply { topMargin = ctx.dp(10) })
        note = UI.text(ctx, "Select a photo to see the cut-out.", TextStyle.CAPTION)
        container.addView(note, lp().apply { topMargin = ctx.dp(6) })
        section(container, "Background", "What goes behind the subject")
        container.addView(ChoiceGroup(ctx, CutoutBackground.entries, { it.label }, CutoutPrefs.background) { CutoutPrefs.background = it; render() }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(ToggleRow(ctx, "Crop to the subject", "Trims the empty space around it", CutoutPrefs.crop) { CutoutPrefs.crop = it; render() }, lp().apply { topMargin = ctx.dp(8) })
        container.addView(UI.note(ctx, "Works best with one clear subject: a person, pet, product or object. Fine hair and see-through things can keep a little of the background.", UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(10) })
        onSelectionChanged()
    }

    override fun onSelectionChanged() {
        if (!::preview.isInitialized) return
        val item = selection.usable.firstOrNull()
        if (item == null) { previewKey = null; base = null; mask = null; preview.setImageDrawable(null); note.text = "Select a photo to see the cut-out."; return }
        if (item.key == previewKey) { render(); return }
        previewKey = item.key
        note.text = "Finding the subject in ${item.name}…"
        previewJob?.cancel()
        previewJob = scope.launch {
            val r = withContext(Dispatchers.Default) {
                try {
                    ImageSource.open(ctx, item).use { src ->
                        val b = src.preview(640)
                        Result.success(b to VisionOps.subjectMask(ctx, b, 640))
                    }
                } catch (e: Throwable) { Result.failure(e) }
            }
            if (previewKey != item.key) return@launch
            r.onSuccess { (b, m) -> base = b; mask = m; render() }
            r.onFailure { e -> base = null; mask = null; preview.setImageDrawable(null); note.text = "Preview not available: ${Errors.describe(e)}" }
        }
    }

    private fun render() {
        val b = base ?: return
        val (m, mw, mh) = mask ?: return
        if (mw != b.width || mh != b.height) return
        val px = IntArray(mw * mh)
        b.getPixels(px, 0, mw, 0, 0, mw, mh)
        val bg = CutoutPrefs.background.color
        for (i in px.indices) {
            val a = m[i]; val c = px[i]
            val under = bg ?: checker(i % mw, i / mw)
            fun mix(sh: Int) = (((c shr sh) and 255) * a + ((under shr sh) and 255) * (1 - a)).roundToInt().coerceIn(0, 255) shl sh
            px[i] = (0xFF shl 24) or mix(16) or mix(8) or mix(0)
        }
        var out = Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888)
        val box = Matting.bounds(m, mw, mh, 0.25f)
        if (box == null) { note.text = "No clear subject found in this photo."; preview.setImageBitmap(out); return }
        if (CutoutPrefs.crop) {
            val mg = (0.03 * maxOf(mw, mh)).roundToInt()
            val l = (box[0] - mg).coerceAtLeast(0); val t = (box[1] - mg).coerceAtLeast(0)
            val r = (box[2] + mg).coerceAtMost(mw); val btm = (box[3] + mg).coerceAtMost(mh)
            out = Bitmap.createBitmap(out, l, t, r - l, btm - t)
        }
        preview.setImageBitmap(out)
        val more = selection.usable.size - 1
        note.text = "Preview of ${selection.usable.first().name}" + (if (more > 0) " · the other $more ${if (more == 1) "photo is" else "photos are"} done the same way" else "") + " · saved at full resolution"
    }

    override fun outputNaming() = "name_cutout.png (transparent) · name_cutout.jpg (with a colour)"
    override fun createJob(items: List<MediaItem>): ExportJob = RemoveBackgroundJob(items, CutoutPrefs.background, CutoutPrefs.crop)
}

// =========================================================================== Auto enhance
object EnhancePrefs { var strength = EnhanceStrength.NATURAL }

class AutoEnhanceScreen(a: MainActivity) : ToolScreen(a, ToolId.AUTO_ENHANCE) {
    private lateinit var before: ImageView
    private lateinit var after: ImageView
    private lateinit var note: TextView
    private var key: String? = null
    private var job: Job? = null

    override fun buildOptions(container: LinearLayout) {
        val head = UI.horizontal(ctx)
        head.addView(UI.text(ctx, "Preview", TextStyle.SUBTITLE), lp(0, WRAP, 1f))
        head.addView(badge(ctx, "AI"))
        container.addView(head, lp().apply { topMargin = ctx.dp(4) })
        val row = UI.horizontal(ctx, Gravity.TOP)
        fun col(label: String): Pair<LinearLayout, ImageView> {
            val c = UI.vertical(ctx)
            val iv = previewBox(ctx)
            c.addView(iv, LinearLayout.LayoutParams(MATCH, ctx.dp(170)))
            c.addView(UI.text(ctx, label, TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0) }, lp())
            return c to iv
        }
        val (c1, i1) = col("Before"); val (c2, i2) = col("After")
        before = i1; after = i2
        row.addView(c1, lp(0, WRAP, 1f).apply { rightMargin = ctx.dp(6) })
        row.addView(c2, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(6) })
        container.addView(row, lp().apply { topMargin = ctx.dp(10) })
        note = UI.text(ctx, "Select photos to see what auto enhance does.", TextStyle.CAPTION)
        container.addView(note, lp().apply { topMargin = ctx.dp(8) })
        section(container, "Strength")
        container.addView(ChoiceGroup(ctx, EnhanceStrength.entries, { it.label }, EnhancePrefs.strength) { EnhancePrefs.strength = it; key = null; onSelectionChanged() }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "Each photo gets its own settings: faces are brightened gently, food gets warmer and richer, landscapes more depth, documents cleaner. Want to fine-tune? Open the photo in the Photo editor and tap Auto enhance there.", UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(12) })
        onSelectionChanged()
    }

    override fun onSelectionChanged() {
        if (!::before.isInitialized) return
        val item = selection.usable.firstOrNull()
        if (item == null) { key = null; before.setImageDrawable(null); after.setImageDrawable(null); note.text = "Select photos to see what auto enhance does."; return }
        val k = item.key + EnhancePrefs.strength
        if (k == key) return
        key = k
        note.text = "Looking at ${item.name}…"
        job?.cancel()
        job = scope.launch {
            val r = withContext(Dispatchers.Default) {
                try {
                    ImageSource.open(ctx, item).use { src ->
                        val b = src.preview(720)
                        val e = VisionOps.enhance(ctx, b, EnhancePrefs.strength.value)
                        val out = b.copy(Bitmap.Config.ARGB_8888, true)
                        EditRenderer.applyColors(out, EditState(adjust = e.adjust), sharpenScale = 720.0 / maxOf(src.width, src.height))
                        Result.success(Triple(b, out, e))
                    }
                } catch (e: Throwable) { Result.failure(e) }
            }
            if (key != k) return@launch
            r.onSuccess { (b, out, e) ->
                before.setImageBitmap(b); after.setImageBitmap(out)
                note.text = "${e.scene.label}: " + (e.notes.joinToString(", ").ifBlank { "already well balanced, only small touches" }) + "."
            }
            r.onFailure { e -> note.text = "Preview not available: ${Errors.describe(e)}" }
        }
    }

    override fun outputNaming() = "name_enhanced.jpg (PNG stays PNG)"
    override fun createJob(items: List<MediaItem>): ExportJob = AutoEnhanceJob(items, EnhancePrefs.strength)
}

// =========================================================================== Face blur
object FaceBlurPrefs {
    var pixelate = false
    var strength = 0.7f
}

class FaceBlurScreen(a: MainActivity) : ToolScreen(a, ToolId.FACE_BLUR) {
    private var result: FaceScanResult? = null
    private var scannedKeys: List<String>? = null
    private val chosen = HashSet<Int>()
    private val round = HashMap<Int, Bitmap?>()
    private var scanJob: Job? = null
    private var scanning = false
    @Volatile private var cancelScan = false
    private lateinit var status: TextView
    private lateinit var progress: ProgressBarView
    private lateinit var people: FlowLayout
    private lateinit var findButton: ButtonView
    private lateinit var bulk: LinearLayout

    override val startLabel: String get() = if (FaceBlurPrefs.pixelate) "Pixelate chosen faces" else "Blur chosen faces"

    override fun buildOptions(container: LinearLayout) {
        val head = UI.horizontal(ctx)
        head.addView(UI.text(ctx, "People found", TextStyle.SUBTITLE), lp(0, WRAP, 1f))
        head.addView(badge(ctx, "AI"))
        container.addView(head, lp().apply { topMargin = ctx.dp(4) })
        status = UI.text(ctx, "Select photos or videos; faces are found automatically.", TextStyle.BODY_2)
        container.addView(status, lp().apply { topMargin = ctx.dp(8) })
        progress = ProgressBarView(ctx).apply { visibility = View.GONE }
        container.addView(progress, LinearLayout.LayoutParams(MATCH, ctx.dp(8)).apply { topMargin = ctx.dp(10) })
        findButton = UI.secondaryButton(ctx, "Find faces", R.drawable.ic_search) { if (scanning) stopScan() else startScan() }
        container.addView(findButton, lp().apply { topMargin = ctx.dp(10) })
        people = FlowLayout(ctx, 10, 12)
        container.addView(people, lp().apply { topMargin = ctx.dp(14) })
        bulk = UI.horizontal(ctx).apply { visibility = View.GONE }
        bulk.addView(UI.ghostButton(ctx, "Hide everyone") { result?.people?.forEach { chosen.add(it.id) }; showPeople(); refreshValidation() })
        bulk.addView(UI.ghostButton(ctx, "Hide no one") { chosen.clear(); showPeople(); refreshValidation() })
        container.addView(bulk, lp().apply { topMargin = ctx.dp(6) })

        section(container, "Style")
        container.addView(ChoiceGroup(ctx, listOf(false, true), { if (it) "Pixelate" else "Blur" }, FaceBlurPrefs.pixelate) { FaceBlurPrefs.pixelate = it; refreshValidation() }, lp().apply { topMargin = ctx.dp(10) })
        val strengths = listOf(0.4f to "Light", 0.7f to "Medium", 1f to "Strong")
        container.addView(ChoiceGroup(ctx, strengths, { it.second }, strengths.minByOrNull { kotlin.math.abs(it.first - FaceBlurPrefs.strength) }) { FaceBlurPrefs.strength = it.first }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "Faces are followed from frame to frame and matched across all selected files, so each person is listed once. Videos are re-encoded as H.264 MP4 at close to their original quality; the sound is kept.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        onSelectionChanged()
    }

    private fun keys() = selection.usable.map { it.key }

    override fun onSelectionChanged() {
        if (!::status.isInitialized) return
        if (keys() == scannedKeys && result != null) return
        if (scanning) stopScan()
        clearResult()
        if (selection.usable.isEmpty()) { status.text = "Select photos or videos; faces are found automatically."; findButton.visibility = View.VISIBLE; return }
        // Start looking automatically once the selection settles.
        scope.launch {
            val k = keys()
            delay(500)
            if (k == keys() && !scanning && result == null) startScan()
        }
    }

    private fun clearResult() {
        result?.release(); result = null; scannedKeys = null; chosen.clear(); round.clear()
        people.removeAllViews(); bulk.visibility = View.GONE
        refreshValidation()
    }

    private fun startScan() {
        val items = selection.usable
        if (items.isEmpty()) return
        clearResult()
        cancelScan = false
        scanning = true
        progress.visibility = View.VISIBLE; progress.setProgress(0f)
        findButton.label = "Stop"
        val videos = items.count { it.kind == MediaKind.VIDEO }
        status.text = if (videos > 0) "Watching ${if (videos == 1) "the video" else "$videos videos"} for faces…" else "Looking for faces…"
        scanJob = scope.launch {
            val k = items.map { it.key }
            val r = withContext(Dispatchers.Default) {
                try {
                    Result.success(FaceScanner.scan(ctx, items, { cancelScan }) { f, text ->
                        scope.launch { if (!cancelScan) { progress.setProgress(f.toFloat()); status.text = text } }
                    })
                } catch (e: Throwable) { Result.failure(e) }
            }
            scanning = false
            progress.visibility = View.GONE
            findButton.label = "Find faces again"
            if (k != keys()) {
                // The selection changed meanwhile: look at the new one instead.
                r.onSuccess { it.release() }
                if (selection.usable.isNotEmpty()) startScan() else status.text = "Select photos or videos; faces are found automatically."
                return@launch
            }
            r.onSuccess { res ->
                result = res; scannedKeys = k
                chosen.clear(); res.people.forEach { chosen.add(it.id) }
                val n = res.people.size
                status.text = when {
                    n == 0 -> "No faces were found." + problems(res)
                    else -> "Found $n ${if (n == 1) "person" else "different people"}. Tap a face to keep it visible; highlighted faces will be hidden." + problems(res)
                }
                showPeople()
            }
            r.onFailure { e ->
                status.text = if (e is FaceScanner.Cancelled) "Stopped. Tap Find faces to look again." else "Faces couldn't be found: ${Errors.describe(e)}"
            }
            refreshValidation()
        }
        refreshValidation()
    }

    private fun problems(r: FaceScanResult) = if (r.problems.isEmpty()) "" else " ${r.problems.size} file(s) couldn't be read: ${r.problems.joinToString { it.first }}."

    private fun stopScan() {
        cancelScan = true
        status.text = "Stopping…"
    }

    private fun showPeople() {
        people.removeAllViews()
        val res = result ?: return
        bulk.visibility = if (res.people.size > 1) View.VISIBLE else View.GONE
        for (p in res.people) people.addView(personView(res, p))
    }

    private fun personView(res: FaceScanResult, p: FaceIdentity): View {
        val on = p.id in chosen
        val col = UI.vertical(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val frame = FrameLayout(ctx)
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Shapes.circle(Palette.SURFACE_3)
            clipToOutline = true
            setImageBitmap(round.getOrPut(p.id) { res.thumb(p)?.let(::circle) })
            alpha = if (on) 1f else 0.5f
        }
        frame.addView(img, FrameLayout.LayoutParams(ctx.dp(72), ctx.dp(72), Gravity.CENTER))
        frame.background = Shapes.rounded(ctx, 0, 100f, if (on) Palette.ACCENT else Palette.STROKE_2, if (on) 3f else 1f)
        frame.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        if (on) frame.addView(UI.iconView(ctx, R.drawable.ic_tool_faceblur, Color.WHITE, 16).apply {
            background = Shapes.circle(Palette.ACCENT_DARK)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        }, FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.END or Gravity.BOTTOM))
        col.addView(frame, LinearLayout.LayoutParams(ctx.dp(84), ctx.dp(84)))
        val files = res.itemsOf(p)
        val vids = files.count { it.kind == MediaKind.VIDEO }; val pics = files.size - vids
        val where = listOfNotNull(if (pics > 0) "$pics photo${if (pics > 1) "s" else ""}" else null, if (vids > 0) "$vids video${if (vids > 1) "s" else ""}" else null).joinToString(" · ")
        col.addView(UI.text(ctx, if (on) "Hidden" else "Visible", TextStyle.CAPTION, if (on) Palette.ACCENT else Palette.TEXT_2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(4), 0, 0) })
        col.addView(UI.text(ctx, where, TextStyle.CAPTION, Palette.TEXT_3).apply { gravity = Gravity.CENTER; textSize = 11f })
        col.isClickable = true; col.isFocusable = true
        col.contentDescription = "Person ${p.id + 1}, in $where, ${if (on) "will be hidden" else "stays visible"}"
        col.setOnClickListener {
            if (!chosen.remove(p.id)) chosen.add(p.id)
            showPeople(); refreshValidation()
        }
        return col
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (scanning) return "Finding faces…"
        val r = result
        if (r == null || scannedKeys != keys()) return "Tap Find faces first"
        if (r.people.isEmpty()) return "No faces to hide in these files"
        if (chosen.isEmpty()) return "Tap the people you want to hide"
        return null
    }

    override fun outputNaming() = "name_blurred.jpg / .png (Pictures) · name_blurred.mp4 (Movies)"

    override fun createJob(items: List<MediaItem>): ExportJob {
        val r = result ?: throw IllegalArgumentException("Find faces first")
        val plan = r.plan(r.people.filter { it.id in chosen })
        val todo = items.filter { plan.containsKey(it.key) }
        if (todo.isEmpty()) throw IllegalArgumentException("The chosen people don't appear in these files")
        return FaceBlurJob(todo, FaceBlurPlan(plan, FaceBlurPrefs.pixelate, FaceBlurPrefs.strength))
    }

    override fun onDestroy() {
        cancelScan = true
        result?.release()
        super.onDestroy()
    }
}
