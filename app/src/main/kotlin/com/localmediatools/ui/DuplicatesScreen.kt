package com.localmediatools.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.tools.ToolId
import com.localmediatools.vision.DupResultGroup
import com.localmediatools.vision.DupScanState
import com.localmediatools.vision.DuplicateScanner
import com.localmediatools.vision.core.DupKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Duplicate finder: scans the photo library (with permission), shows groups of copies and
 * similar shots with the best photo marked, and moves the chosen extras to the system trash.
 */
class DuplicatesScreen(activity: MainActivity) : Screen(activity) {
    private val tool = ToolId.DUPLICATES
    private lateinit var statusCard: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var trashButton: ButtonView
    private lateinit var barText: TextView
    private val remove = LinkedHashSet<Uri>()
    private var shownGroups: List<DupResultGroup>? = null
    private var pages = 1
    private val thumbs = object : LruCache<Uri, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: Uri, value: Bitmap) = value.allocationByteCount }

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false }
        val content = UI.vertical(ctx, 16, 0)
        content.setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(150))
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(TopBar(this, tool.title).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })

        val hero = UI.card(ctx)
        val row = UI.horizontal(ctx, Gravity.TOP)
        row.addView(UI.toolTile(ctx, tool, 52, 28))
        row.addView(UI.text(ctx, tool.longDescription, TextStyle.BODY_2).apply { setPadding(ctx.dp(14), 0, 0, 0) }, lp(0, WRAP, 1f))
        hero.addView(row)
        hero.addView(UI.note(ctx, "Photos are compared on this phone. Nothing is deleted until you confirm, and removed photos go to the system trash, where they can be restored for 30 days.", UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(12) })
        content.addView(hero)

        statusCard = UI.card(ctx)
        content.addView(statusCard, lp().apply { topMargin = ctx.dp(14) })
        list = UI.vertical(ctx)
        content.addView(list, lp().apply { topMargin = ctx.dp(6) })

        bar = UI.vertical(ctx, 16, 12)
        bar.background = Shapes.rounded(ctx, Palette.SURFACE, 24f, Palette.STROKE)
        bar.elevation = ctx.dp(8).toFloat()
        barText = UI.text(ctx, "", TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ctx.dp(8)) }
        bar.addView(barText)
        trashButton = UI.primaryButton(ctx, "Move to trash") { trash() }
        bar.addView(trashButton, lp())
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, ctx.dp(10), ctx.dp(10)) })
        bar.visibility = View.GONE

        scope.launch { DuplicateScanner.state.collect { render(it) } }
        return root
    }

    private fun render(s: DupScanState) {
        statusCard.removeAllViews()
        when (s) {
            is DupScanState.Idle -> {
                statusCard.addView(UI.text(ctx, "Find duplicates in your photos", TextStyle.SUBTITLE))
                statusCard.addView(UI.text(ctx, if (activity.hasPhotoAccess()) "Scans every photo this app can see. The first scan takes a while (about a minute per thousand photos); later scans only look at new photos."
                    else "The app needs permission to see your photos for this. It never uploads them and has no internet access.", TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(6) })
                statusCard.addView(UI.primaryButton(ctx, "Scan my photos") { start() }, lp().apply { topMargin = ctx.dp(14) })
                showGroups(null)
            }
            is DupScanState.Running -> {
                statusCard.addView(UI.text(ctx, s.phase, TextStyle.SUBTITLE))
                statusCard.addView(UI.text(ctx, if (s.total > 0) "${s.done} of ${s.total} photos" else "Starting…", TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(6) })
                statusCard.addView(ProgressBarView(ctx).apply { if (s.total > 0) setProgress(s.done.toFloat() / s.total) else indeterminate = true }, LinearLayout.LayoutParams(MATCH, ctx.dp(8)).apply { topMargin = ctx.dp(12) })
                statusCard.addView(UI.ghostButton(ctx, "Stop") { DuplicateScanner.cancel() }, lp().apply { topMargin = ctx.dp(8) })
                showGroups(null)
            }
            is DupScanState.Failed -> {
                statusCard.addView(UI.note(ctx, "The scan stopped: ${s.message}", UI.NoteKind.WARN))
                statusCard.addView(UI.secondaryButton(ctx, "Try again") { start() }, lp().apply { topMargin = ctx.dp(12) })
                showGroups(null)
            }
            is DupScanState.Done -> {
                val extra = s.groups.sumOf { it.photos.size - 1 }
                val bytes = s.groups.sumOf { it.extraBytes }
                statusCard.addView(UI.text(ctx, if (s.groups.isEmpty()) "No duplicates found" else "${s.groups.size} groups · $extra photos you could remove", TextStyle.SUBTITLE))
                statusCard.addView(UI.text(ctx, if (s.groups.isEmpty()) "Checked ${s.scanned} photos. Nice and tidy!"
                    else "Checked ${s.scanned} photos · up to ${Format.bytes(bytes)} to free. The best photo of each group (sharpest, largest) is marked; copies are pre-selected, similar shots are up to you.", TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(6) })
                if (!s.aiUsed) statusCard.addView(UI.note(ctx, "Similar-shot detection needs the on-device AI model, which isn't available on this phone; exact and re-saved copies are still found.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(10) })
                statusCard.addView(UI.ghostButton(ctx, "Scan again", R.drawable.ic_history) { start() }, lp().apply { topMargin = ctx.dp(8) })
                showGroups(s.groups)
            }
        }
    }

    private fun start() {
        activity.ensurePhotoAccess { ok ->
            if (ok) DuplicateScanner.start(ctx)
            else Toast.makeText(ctx, "Without access to your photos the duplicate finder can't look for copies.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showGroups(groups: List<DupResultGroup>?) {
        if (groups == null) { shownGroups = null; list.removeAllViews(); remove.clear(); updateBar(); return }
        if (groups !== shownGroups) {
            // Keep earlier choices that still apply; pre-select copies in new groups.
            val known = shownGroups?.flatMap { g -> g.photos.map { it.uri } }?.toHashSet() ?: hashSetOf()
            val all = groups.flatMap { g -> g.photos.map { it.uri } }.toHashSet()
            remove.retainAll(all)
            for (g in groups) if (g.kind != DupKind.SIMILAR) for (p in g.photos) if (p !== g.best && p.uri !in known) remove.add(p.uri)
            shownGroups = groups
        }
        list.removeAllViews()
        val limit = pages * 30
        for (g in groups.take(limit)) list.addView(groupCard(g), lp().apply { topMargin = ctx.dp(12) })
        if (groups.size > limit) list.addView(UI.secondaryButton(ctx, "Show ${minOf(30, groups.size - limit)} more groups") { pages++; showGroups(groups) }, lp().apply { topMargin = ctx.dp(12) })
        updateBar()
    }

    private fun groupCard(g: DupResultGroup): View {
        val card = UI.card(ctx, 14)
        val head = UI.horizontal(ctx)
        head.addView(UI.titled(ctx, g.kind.label, "${g.photos.size} photos · ${g.kind.explain}"), lp(0, WRAP, 1f))
        val all = g.photos.filter { it !== g.best }.all { it.uri in remove }
        head.addView(UI.ghostButton(ctx, if (all) "Keep all" else "Keep best only") {
            if (all) g.photos.forEach { remove.remove(it.uri) } else g.photos.forEach { if (it !== g.best) remove.add(it.uri) else remove.remove(it.uri) }
            showGroups(shownGroups)
        })
        card.addView(head)
        val strip = LinearLayout(ctx)
        for (p in g.photos) {
            val marked = p.uri in remove
            val cell = FrameLayout(ctx).apply {
                background = Shapes.rounded(ctx, Palette.SURFACE_2, 14f, if (marked) Palette.DANGER else if (p === g.best) Palette.SUCCESS else Palette.STROKE, if (marked || p === g.best) 2.5f else 1f)
                setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            }
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Shapes.rounded(ctx, Palette.SURFACE_3, 11f)
                clipToOutline = true
                alpha = if (marked) 0.55f else 1f
            }
            cell.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            loadThumb(p.uri, iv)
            if (p === g.best) cell.addView(badge(ctx, "BEST", intArrayOf(Palette.SUCCESS, 0xFF119E6E.toInt())), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(ctx.dp(6), ctx.dp(6), 0, 0) })
            if (marked) cell.addView(UI.iconView(ctx, R.drawable.ic_trash, Color.WHITE, 16).apply {
                background = Shapes.circle(Palette.DANGER); setPadding(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
            }, FrameLayout.LayoutParams(ctx.dp(28), ctx.dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, ctx.dp(6), ctx.dp(6), 0) })
            val mp = p.width.toLong() * p.height / 1_000_000.0
            cell.addView(UI.text(ctx, "${if (mp >= 1) String.format(java.util.Locale.US, "%.0f MP", mp) else "${p.width}×${p.height}"} · ${Format.bytes(p.size)}", TextStyle.CAPTION, Color.WHITE).apply {
                textSize = 10.5f; maxLines = 1
                setBackgroundColor(0x99000000.toInt()); setPadding(ctx.dp(6), ctx.dp(3), ctx.dp(6), ctx.dp(3))
            }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            cell.isClickable = true; cell.isFocusable = true
            cell.contentDescription = "${p.name}, ${if (marked) "will be moved to the trash" else "kept"}${if (p === g.best) ", best photo" else ""}"
            cell.setOnClickListener {
                if (marked) remove.remove(p.uri)
                else if (g.photos.all { it === p || it.uri in remove }) {
                    Toast.makeText(ctx, "Keep at least one photo of each group.", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                } else remove.add(p.uri)
                showGroups(shownGroups)
            }
            cell.setOnLongClickListener {
                try { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(p.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) { }
                true
            }
            strip.addView(cell, LinearLayout.LayoutParams(ctx.dp(112), ctx.dp(112)).apply { rightMargin = ctx.dp(8) })
        }
        card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, lp().apply { topMargin = ctx.dp(12) })
        card.addView(UI.text(ctx, "Tap a photo to keep or remove it · long-press to view it", TextStyle.CAPTION, Palette.TEXT_3), lp().apply { topMargin = ctx.dp(8) })
        return card
    }

    private fun loadThumb(uri: Uri, iv: ImageView) {
        thumbs.get(uri)?.let { iv.setImageBitmap(it); return }
        iv.tag = uri
        scope.launch {
            val b = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.loadThumbnail(uri, Size(240, 240), null) } catch (_: Exception) {
                    // Some providers have no thumbnails: decode a small copy instead.
                    try {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                        var sample = 1
                        while (o.outWidth / (sample * 2) >= 240 && o.outHeight / (sample * 2) >= 240) sample *= 2
                        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                    } catch (_: Exception) { null }
                }
            }
            if (b != null) thumbs.put(uri, b)
            if (iv.tag == uri) iv.setImageBitmap(b)
        }
    }

    private fun updateBar() {
        val groups = shownGroups
        if (groups == null || remove.isEmpty()) { bar.visibility = View.GONE; return }
        val photos = groups.flatMap { it.photos }.filter { it.uri in remove }
        bar.visibility = View.VISIBLE
        barText.text = "${photos.size} selected · frees ${Format.bytes(photos.sumOf { it.size })}"
        trashButton.label = "Move ${photos.size} to trash"
    }

    private fun trash() {
        val uris = remove.toList()
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(ctx, "Moving photos to the trash needs Android 11 or newer. Long-press a photo to open it in your gallery and delete it there.", Toast.LENGTH_LONG).show()
            return
        }
        val pi = try { MediaStore.createTrashRequest(ctx.contentResolver, uris, true) } catch (e: Exception) {
            Toast.makeText(ctx, "These photos can't be moved to the trash: ${e.message}", Toast.LENGTH_LONG).show(); return
        }
        activity.launchIntentSender(pi.intentSender) { rc ->
            if (rc == android.app.Activity.RESULT_OK) {
                val set = uris.toHashSet()
                remove.removeAll(set)
                DuplicateScanner.removed(set)
                Toast.makeText(ctx, "Moved ${uris.size} ${if (uris.size == 1) "photo" else "photos"} to the trash. You can restore them from your gallery's trash for 30 days.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onShow() {
        // Permission may have been granted from settings meanwhile.
        if (::statusCard.isInitialized && DuplicateScanner.state.value is DupScanState.Idle) render(DuplicateScanner.state.value)
    }
}
