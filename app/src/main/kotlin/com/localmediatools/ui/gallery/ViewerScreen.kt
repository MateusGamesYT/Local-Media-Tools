package com.localmediatools.ui.gallery

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.gallery.GFace
import com.localmediatools.gallery.GMedia
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.GalleryRepo
import com.localmediatools.gallery.core.Taxonomy
import com.localmediatools.image.ImageSource
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.ui.typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

/**
 * Full-screen viewer: swipe between photos and videos, pinch or double-tap to zoom, play videos,
 * and see who is in a photo — faces are outlined with their names, and tapping one lets you name
 * them, correct them or say who they are not.
 */
class ViewerScreen(activity: MainActivity, items: List<GMedia>, start: Int, private val highlightFace: Long? = null) : Screen(activity) {
    private var items: MutableList<GMedia> = items.toMutableList()
    private var index = start.coerceIn(0, maxOf(0, items.size - 1))
    private lateinit var pager: Pager
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var faceStrip: LinearLayout
    private lateinit var faceToggle: ImageView
    private var chrome = true
    private var showFaces = highlightFace != null || ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE).getBoolean("show_faces", false)
    private var faces: List<GFace> = emptyList()
    private var names: Map<Long, String> = emptyMap()
    private var facesJob: Job? = null

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        pager = Pager(ctx)
        root.addView(pager, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        top = UI.horizontal(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
            setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(6), ctx.dp(18))
            addView(UI.iconButton(ctx, R.drawable.ic_back, "Back", Color.WHITE) { pop() })
            addView(UI.vertical(ctx).apply {
                title = UI.text(ctx, "", TextStyle.SUBTITLE, Color.WHITE)
                subtitle = UI.text(ctx, "", TextStyle.CAPTION, 0xCCFFFFFF.toInt())
                addView(title); addView(subtitle)
            }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(4) })
            faceToggle = UI.iconButton(ctx, R.drawable.ic_tool_faceblur, "Show faces", Color.WHITE) { toggleFaces() }
            addView(faceToggle)
            addView(UI.iconButton(ctx, R.drawable.ic_info, "Details", Color.WHITE) { info() })
        }
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        bottom = UI.vertical(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0x00000000))
            setPadding(ctx.dp(8), ctx.dp(24), ctx.dp(8), ctx.dp(10))
        }
        faceStrip = UI.horizontal(ctx).apply { setPadding(ctx.dp(8), 0, ctx.dp(8), ctx.dp(8)) }
        bottom.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(faceStrip) })
        bottom.addView(UI.horizontal(ctx).apply {
            fun action(icon: Int, label: String, f: () -> Unit) = UI.vertical(ctx).apply {
                gravity = Gravity.CENTER
                addView(UI.iconView(ctx, icon, Color.WHITE, 22))
                addView(UI.text(ctx, label, TextStyle.CAPTION, Color.WHITE).apply { setPadding(0, ctx.dp(3), 0, 0) })
                isClickable = true; isFocusable = true
                background = Shapes.clickable(ctx, 0, 14f)
                setPadding(0, ctx.dp(6), 0, ctx.dp(6))
                contentDescription = label
                setOnClickListener { f() }
            }
            addView(action(R.drawable.ic_share, "Share") { current()?.let { GalleryActions.share(activity, listOf(it)) } }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action(R.drawable.ic_tool_editor, "Edit") { edit() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action(R.drawable.ic_wand, "Tools") { current()?.let { GalleryActions.chooseTool(activity, listOf(it)) } }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action(R.drawable.ic_tool_print, "Print") { current()?.let { GalleryActions.print(activity, listOf(it)) } }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action(R.drawable.ic_trash, "Delete") { delete() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        pager.setup()
        onPage()
        watch.start()
        return root
    }

    private fun current(): GMedia? = items.getOrNull(index)

    private fun onPage() {
        val m = current() ?: return
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
        title.text = df.format(Date(m.taken))
        subtitle.text = tf.format(Date(m.taken)) + " · " + m.bucket
        loadFaces()
    }

    private val watch = IndexWatch(this, 0) { loadFaces() }

    override fun onShow() { watch.shown() }

    private fun loadFaces() {
        val m = current() ?: return
        facesJob?.cancel()
        facesJob = scope.launch {
            val (f, n) = withContext(Dispatchers.IO) {
                val db = GalleryDb.get(ctx)
                val list = db.facesOf(m.id).filter { !it.ignored }
                list to db.people(includeHidden = true).filter { it.named }.associate { it.id to it.name!! }
            }
            if (current()?.id != m.id) return@launch
            faces = f; names = n
            renderFaceStrip()
            faceToggle.alpha = if (showFaces) 1f else 0.55f
            faceToggle.visibility = if (faces.isEmpty()) View.GONE else View.VISIBLE
            pager.currentPage()?.invalidateOverlay()
        }
    }

    private fun renderFaceStrip() {
        faceStrip.removeAllViews()
        if (faces.isEmpty() || !showFaces) { faceStrip.visibility = View.GONE; return }
        faceStrip.visibility = View.VISIBLE
        for (f in faces.sortedBy { it.x }) {
            val name = f.personId?.let { names[it] }
            faceStrip.addView(UI.vertical(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                val img = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Shapes.circle(Palette.SURFACE_2); clipToOutline = true
                }
                GalleryThumbs.face(ctx, f, 160, img)
                addView(img, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
                addView(UI.text(ctx, name ?: "Add name", TextStyle.CAPTION, if (name != null) Color.WHITE else Palette.ACCENT).apply {
                    maxLines = 1; setPadding(0, ctx.dp(4), 0, 0); maxWidth = ctx.dp(72); ellipsize = android.text.TextUtils.TruncateAt.END
                })
                isClickable = true; isFocusable = true
                contentDescription = name ?: "Unnamed face, tap to name"
                setOnClickListener { openFace(f) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dp(14) })
        }
    }

    private fun toggleFaces() {
        showFaces = !showFaces
        ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putBoolean("show_faces", showFaces).apply()
        faceToggle.alpha = if (showFaces) 1f else 0.55f
        faceToggle.contentDescription = if (showFaces) "Hide faces" else "Show faces"
        renderFaceStrip()
        pager.currentPage()?.invalidateOverlay()
    }

    private fun openFace(f: GFace) = FaceSheet.show(activity, f) { loadFaces() }

    private fun toggleChrome() {
        chrome = !chrome
        for (v in listOf(top, bottom)) v.animate().alpha(if (chrome) 1f else 0f).setDuration(160).withEndAction { v.visibility = if (chrome) View.VISIBLE else View.INVISIBLE }.start()
        if (chrome) { top.visibility = View.VISIBLE; bottom.visibility = View.VISIBLE }
    }

    private fun edit() {
        val m = current() ?: return
        if (m.video) GalleryActions.chooseTool(activity, listOf(m))
        else GalleryActions.openIn(activity, com.localmediatools.tools.ToolId.PHOTO_EDITOR, listOf(m))
    }

    private fun delete() {
        val m = current() ?: return
        GalleryActions.trash(activity, listOf(m)) {
            items.removeAt(index)
            if (items.isEmpty()) { pop(); return@trash }
            index = index.coerceAtMost(items.size - 1)
            pager.rebind()
            onPage()
        }
    }

    private fun info() {
        val m = current() ?: return
        scope.launch {
            val (tags, people) = withContext(Dispatchers.IO) {
                val db = GalleryDb.get(ctx)
                val t = db.tagsOf(m.id).filter { it.value >= Taxonomy.TAGGED }.keys.mapNotNull { GalleryRepo.category(ctx, it)?.name }
                val ppl = db.facesOf(m.id).filter { !it.ignored }.map { f -> f.personId?.let { db.person(it)?.name } }
                t to ppl
            }
            val df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
            val sb = StringBuilder()
            sb.append(m.name).append("\n\n")
            sb.append(df.format(Date(m.taken))).append('\n')
            sb.append("${m.width} × ${m.height}").append(" · ").append(Format.bytes(m.size))
            if (m.video) sb.append(" · ").append(Format.duration(m.durationMs))
            sb.append('\n').append(m.path.ifBlank { m.bucket })
            if (tags.isNotEmpty()) sb.append("\n\nFound in it: ").append(tags.joinToString(", "))
            if (people.isNotEmpty()) {
                val named = people.filterNotNull().distinct()
                val unnamed = people.count { it == null }
                sb.append("\nPeople: ").append((named + if (unnamed > 0) listOf("$unnamed unnamed") else emptyList()).joinToString(", "))
            }
            val st = GalleryIndex.state.value
            if (tags.isEmpty() && people.isEmpty() && st.working) sb.append("\n\nThis item hasn't been analysed yet.")
            AlertDialog.Builder(activity).setTitle("Details").setMessage(sb.toString()).setPositiveButton("OK", null)
                .setNeutralButton("Open with…") { _, _ -> GalleryActions.openExternally(activity, m) }.show()
        }
    }

    override fun onHide() { pager.pauseVideos() }

    override fun onDestroy() {
        pager.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ pages
    private inner class Page(ctx: Context) : FrameLayout(ctx) {
        val zoom = ZoomImageView(ctx)
        private val play = FrameLayout(ctx)
        private var video: VideoView? = null
        var media: GMedia? = null; private set
        private var job: Job? = null
        private var bmp: Bitmap? = null
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dp(12).toFloat(); typeface = typeface(600) }

        init {
            addView(zoom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            play.background = Shapes.circle(0x99000000.toInt())
            play.addView(UI.iconView(ctx, R.drawable.ic_play, Color.WHITE, 30), LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER))
            play.contentDescription = "Play video"
            play.isClickable = true
            play.setOnClickListener { startVideo() }
            addView(play, LayoutParams(ctx.dp(72), ctx.dp(72), Gravity.CENTER))
            zoom.onTap = { x, y -> if (!tapFace(x, y)) toggleChrome() }
            zoom.overlay = { c, r -> drawFaces(c, r) }
        }

        fun bind(m: GMedia?) {
            if (m != null && media?.id == m.id && bmp != null) return
            stopVideo()
            job?.cancel()
            media = m
            zoom.bitmap = null
            bmp = null
            play.visibility = if (m?.video == true) VISIBLE else GONE
            if (m == null) return
            // Quick thumbnail first, then a sharp screen-sized picture.
            GalleryThumbs.get(context, m, 512) { t -> if (media?.id == m.id && bmp == null && t != null) zoom.bitmap = t }
            job = scope.launch {
                val side = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).coerceAtMost(2560) * 3 / 2
                val full = withContext(Dispatchers.IO) {
                    try {
                        if (m.video) activity.contentResolver.loadThumbnail(m.uri, android.util.Size(side, side), null)
                        else ImageSource.open(ctx, m.uri, m.name).use { it.preview(side.coerceAtMost(3072)) }
                    } catch (_: Throwable) { null }
                }
                if (media?.id == m.id && full != null) { bmp = full; zoom.bitmap = full }
            }
        }

        fun invalidateOverlay() = zoom.invalidate()

        private fun faceRect(f: GFace, r: RectF) = RectF(r.left + f.x * r.width(), r.top + f.y * r.height(), r.left + (f.x + f.w) * r.width(), r.top + (f.y + f.h) * r.height())

        private fun drawFaces(c: Canvas, r: RectF) {
            val m = media ?: return
            if (!showFaces || m.id != current()?.id || m.video) return
            for (f in faces) {
                val fr = faceRect(f, r)
                val name = f.personId?.let { names[it] }
                val highlighted = f.id == highlightFace
                box.color = when { highlighted -> Palette.WARNING; name != null -> Palette.ACCENT; else -> Color.WHITE }
                val rad = context.dp(10).toFloat()
                c.drawRoundRect(fr, rad, rad, box)
                val label = name ?: "Add name"
                val tw = labelText.measureText(label)
                val pad = context.dp(6).toFloat(); val h = labelText.textSize + pad * 1.4f
                val lx = (fr.centerX() - tw / 2 - pad).coerceIn(r.left, maxOf(r.left, r.right - tw - 2 * pad))
                val ly = if (fr.bottom + h + context.dp(4) < r.bottom) fr.bottom + context.dp(4) else fr.top - h - context.dp(4)
                labelBg.color = if (name != null) Palette.withAlpha(Palette.ACCENT_DARK, 0xE6) else 0xCC000000.toInt()
                c.drawRoundRect(lx, ly, lx + tw + 2 * pad, ly + h, h / 2, h / 2, labelBg)
                c.drawText(label, lx + pad, ly + h / 2 + labelText.textSize / 3, labelText)
            }
        }

        private fun tapFace(x: Float, y: Float): Boolean {
            val m = media ?: return false
            if (!showFaces || m.video) return false
            val r = zoom.photoBounds()
            val hit = faces.firstOrNull { val fr = faceRect(it, r); fr.inset(-context.dp(8).toFloat(), -context.dp(8).toFloat()); fr.contains(x, y) } ?: return false
            openFace(hit)
            return true
        }

        private fun startVideo() {
            val m = media ?: return
            val v = VideoView(context)
            video = v
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val mc = MediaController(context)
            mc.setAnchorView(v)
            v.setMediaController(mc)
            v.setVideoURI(m.uri)
            v.setOnPreparedListener { play.visibility = GONE; zoom.visibility = INVISIBLE; v.start() }
            v.setOnCompletionListener { stopVideo() }
            v.setOnErrorListener { _, _, _ -> stopVideo(); GalleryActions.openExternally(activity, m); true }
        }

        fun stopVideo() {
            video?.let { it.stopPlayback(); removeView(it) }
            video = null
            zoom.visibility = VISIBLE
            play.visibility = if (media?.video == true) VISIBLE else GONE
        }

        fun canPan(dx: Float) = zoom.canPan(dx)
        fun resetZoom() = zoom.reset()
    }

    /** Horizontal pager with three recycled pages. */
    private inner class Pager(ctx: Context) : FrameLayout(ctx) {
        private val pages = Array(3) { Page(ctx) }   // previous, current, next
        private var offset = 0f
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var vt: VelocityTracker? = null
        private var anim: ValueAnimator? = null

        fun setup() {
            for (p in pages) addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            rebind()
        }

        fun currentPage(): Page? = pages[1]

        fun rebind() {
            pages[0].bind(items.getOrNull(index - 1))
            pages[1].bind(items.getOrNull(index))
            pages[2].bind(items.getOrNull(index + 1))
            offset = 0f
            layoutPages()
        }

        private fun layoutPages() {
            val w = width.toFloat()
            pages[0].translationX = -w - gapPx() + offset
            pages[1].translationX = offset
            pages[2].translationX = w + gapPx() + offset
        }

        private fun gapPx() = context.dp(16).toFloat()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutPages() }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; anim?.cancel() }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount > 1) return false
                    val dx = e.x - downX; val dy = e.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f && !pages[1].canPan(dx)) {
                        dragging = true; downX = e.x
                        vt = VelocityTracker.obtain()
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    var dx = e.x - downX
                    // Resist at the ends.
                    if ((index == 0 && dx > 0) || (index == items.size - 1 && dx < 0)) dx *= 0.3f
                    offset = dx; layoutPages()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    vt?.recycle(); vt = null
                    val w = width.toFloat()
                    val dir = when {
                        (offset < -w / 4 || v < -1200) && index < items.size - 1 -> 1
                        (offset > w / 4 || v > 1200) && index > 0 -> -1
                        else -> 0
                    }
                    settle(dir)
                }
            }
            return true
        }

        private fun settle(dir: Int) {
            val w = width.toFloat() + gapPx()
            val target = -dir * w
            anim = ValueAnimator.ofFloat(offset, target).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { offset = it.animatedValue as Float; layoutPages() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        if (dir == 0) return
                        pages[1].stopVideo(); pages[1].resetZoom()
                        index += dir
                        if (dir > 0) { val p = pages[0]; pages[0] = pages[1]; pages[1] = pages[2]; pages[2] = p; pages[2].bind(items.getOrNull(index + 1)) }
                        else { val p = pages[2]; pages[2] = pages[1]; pages[1] = pages[0]; pages[0] = p; pages[0].bind(items.getOrNull(index - 1)) }
                        offset = 0f
                        layoutPages()
                        onPage()
                    }
                })
                start()
            }
        }

        fun pauseVideos() = pages.forEach { it.stopVideo() }

        fun release() { anim?.cancel(); pages.forEach { it.stopVideo() } }
    }
}
