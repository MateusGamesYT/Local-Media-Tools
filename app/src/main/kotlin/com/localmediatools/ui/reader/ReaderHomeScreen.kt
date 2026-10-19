package com.localmediatools.ui.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.i18n.tr
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.TopBar
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The file reader's first screen: open a file, or one opened recently. */
class ReaderHomeScreen(activity: MainActivity) : Screen(activity) {
    private val tool = ToolId.READER
    private lateinit var recents: LinearLayout

    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        root.addView(TopBar(this, tool.title))
        val col = UI.vertical(ctx, 16, 4)
        val hero = UI.card(ctx)
        val row = UI.horizontal(ctx, Gravity.TOP)
        row.addView(UI.toolTile(ctx, tool, 52, 28))
        row.addView(UI.text(ctx, tool.longDescription, TextStyle.BODY_2).apply { setPadding(ctx.dp(14), 0, 0, 0) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(row)
        hero.addView(UI.primaryButton(ctx, tr("Open a file")) { pick() }, lp().apply { topMargin = ctx.dp(16) })
        hero.addView(UI.text(ctx, tr("Also in other apps: choose “Open with” and then Local Media Tools."), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(10), 0, 0) })
        col.addView(hero)
        col.addView(UI.label(ctx, tr("Recent files")), lp().apply { topMargin = ctx.dp(24); bottomMargin = ctx.dp(10) })
        recents = UI.vertical(ctx)
        col.addView(recents, lp())
        col.addView(UI.note(ctx, tr("Documents are read on this phone and never uploaded. The reader only shows files: it doesn't change them."), UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(20); bottomMargin = ctx.dp(24) })
        root.addView(ScrollView(ctx).apply { addView(col); isFillViewport = true }, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onShow() { refresh() }

    private fun pick() {
        activity.pickAnyFile { uri ->
            uri ?: return@pickAnyFile
            scope.launch {
                val persistent = RecentFiles.persist(ctx, uri)
                val name = withContext(Dispatchers.IO) { try { ReaderFiles.describe(ctx, uri).first } catch (_: Exception) { uri.lastPathSegment ?: "file" } }
                push(ReaderScreen(activity, ReaderSource(uri, name), persistent))
            }
        }
    }

    private fun refresh() {
        if (!::recents.isInitialized) return
        scope.launch {
            val items = withContext(Dispatchers.IO) { ReaderFiles.cleanUp(ctx); RecentFiles.list(ctx) }
            recents.removeAllViews()
            if (items.isEmpty()) {
                recents.addView(UI.text(ctx, tr("Files you open show up here."), TextStyle.BODY_2).apply { setPadding(ctx.dp(4), 0, 0, 0) })
                return@launch
            }
            val card = UI.vertical(ctx).apply { background = Shapes.rounded(ctx, Palette.SURFACE, 22f, Palette.STROKE); setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4)) }
            for (r in items) card.addView(row(r))
            recents.addView(card, lp())
            recents.addView(UI.ghostButton(ctx, tr("Clear the list"), R.drawable.ic_trash) {
                RecentFiles.clear(ctx); refresh()
            }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        }
    }

    private fun row(r: RecentFile): View {
        val fmt = FormatLook.parse(r.format)
        val line = UI.horizontal(ctx).apply {
            background = Shapes.clickable(ctx, Color.TRANSPARENT, 18f)
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(4), ctx.dp(10))
            isClickable = true; isFocusable = true
        }
        line.addView(FileBadge(ctx, FormatLook.tag(fmt, r.name), FormatLook.color(fmt)), lp(ctx.dp(40), ctx.dp(46)))
        val when_ = DateUtils.getRelativeTimeSpanString(r.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        line.addView(UI.titled(ctx, r.name, "${FormatLook.label(fmt)} · ${Format.bytes(r.size)} · $when_").apply {
            setPadding(ctx.dp(12), 0, ctx.dp(4), 0)
        }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(UI.iconButton(ctx, R.drawable.ic_close, tr("Remove {0} from the list", r.name), Palette.TEXT_3) { RecentFiles.remove(ctx, r); refresh() })
        line.contentDescription = "${r.name}. ${FormatLook.label(fmt)}"
        line.setOnClickListener { push(ReaderScreen(activity, r.source, r.uri != null)) }
        return line
    }

    /** A page with a folded corner and the format written on it. */
    private class FileBadge(ctx: android.content.Context, private val tag: String, private val color: Int) : View(ctx) {
        private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Palette.SURFACE_3 }
        private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = w * 0.14f
            c.drawRoundRect(RectF(0f, 0f, w, h), r, r, paper)
            c.drawRoundRect(RectF(0f, h * 0.52f, w, h * 0.86f), r * 0.6f, r * 0.6f, band)
            text.textSize = (w * (if (tag.length > 3) 0.27f else 0.32f))
            c.drawText(tag, w / 2, h * 0.69f + text.textSize * 0.35f, text)
        }
    }
}
