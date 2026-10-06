package com.localmediatools.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Workload
import com.localmediatools.core.WorkloadProfile
import com.localmediatools.export.ExportManager
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.ToolSection
import com.localmediatools.ui.tools.ToolScreens
import kotlinx.coroutines.launch

class HomeScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var workloadValue: TextView
    private lateinit var workloadDesc: TextView
    private lateinit var activityLine: TextView

    override fun createView(): View {
        val scroll = ScrollView(ctx).apply { setBackgroundColor(Palette.BG); clipToPadding = false }
        val col = UI.vertical(ctx, 16, 12)
        col.setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(40))
        scroll.addView(col)

        // Header.
        val header = UI.horizontal(ctx)
        header.addView(FrameLayout(ctx).apply {
            background = Shapes.rounded(ctx, 0xFF0F1722.toInt(), 16f, Palette.STROKE)
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_launcher_foreground); scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(ctx.dp(80), ctx.dp(80), Gravity.CENTER))
            clipToOutline = true
        }, LinearLayout.LayoutParams(ctx.dp(54), ctx.dp(54)))
        header.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(14), 0, 0, 0)
            addView(UI.text(ctx, "Local Media Tools", TextStyle.DISPLAY).apply { isAccessibilityHeading = true })
            addView(UI.text(ctx, "Your private media toolbox — everything stays on this phone", TextStyle.BODY_2).apply { setPadding(0, ctx.dp(4), 0, 0) })
        }, lp(0, WRAP, 1f))
        val history = UI.iconButton(ctx, R.drawable.ic_history, "Recent exports", Palette.TEXT_2) { push(HistoryScreen(activity)) }
        header.addView(history)
        col.addView(header, lp().apply { topMargin = ctx.dp(8) })

        // Feature chips.
        val chips = FlowLayout(ctx)
        chips.addView(feature(R.drawable.ic_shield, "On-device & private", Palette.SUCCESS))
        chips.addView(feature(R.drawable.ic_stack, "Batch processing", Palette.ACCENT))
        chips.addView(feature(R.drawable.ic_clock, "Background exports", 0xFFA78BFA.toInt()))
        col.addView(chips, lp().apply { topMargin = ctx.dp(16) })

        // Status: workload + export activity.
        val status = UI.card(ctx)
        val wrow = UI.horizontal(ctx)
        wrow.addView(UI.iconTile(ctx, R.drawable.ic_bolt, Palette.WARNING, 40, 22))
        workloadValue = UI.text(ctx, "", TextStyle.SUBTITLE)
        workloadDesc = UI.text(ctx, "", TextStyle.CAPTION)
        wrow.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(12), 0, ctx.dp(8), 0)
            addView(UI.text(ctx, "Export workload", TextStyle.CAPTION, Palette.TEXT_3))
            addView(workloadValue.apply { setPadding(0, ctx.dp(2), 0, 0) })
            addView(workloadDesc.apply { setPadding(0, ctx.dp(2), 0, 0) })
        }, lp(0, WRAP, 1f))
        wrow.addView(UI.secondaryButton(ctx, "Adjust", R.drawable.ic_tune) { WorkloadDialog.show(activity) })
        status.addView(wrow)
        status.addView(UI.divider(ctx), lp(MATCH, maxOf(1, ctx.dp(1) / 2 + 1)).apply { topMargin = ctx.dp(14); bottomMargin = ctx.dp(12) })
        val arow = UI.horizontal(ctx)
        arow.addView(UI.iconView(ctx, R.drawable.ic_clock, Palette.TEXT_2, 20))
        activityLine = UI.text(ctx, "", TextStyle.BODY_2).apply { setPadding(ctx.dp(10), 0, 0, 0) }
        arow.addView(activityLine, lp(0, WRAP, 1f))
        arow.addView(UI.ghostButton(ctx, "History") { push(HistoryScreen(activity)) })
        status.addView(arow)
        col.addView(status, lp().apply { topMargin = ctx.dp(16) })
        col.addView(ExportStatusCard(this, null), lp().apply { topMargin = ctx.dp(12) })

        // Tool sections.
        for (sec in ToolSection.entries) {
            val color = Palette.section(sec)
            val sh = UI.horizontal(ctx)
            sh.addView(View(ctx).apply { background = Shapes.rounded(ctx, color, 3f) }, LinearLayout.LayoutParams(ctx.dp(4), ctx.dp(22)))
            sh.addView(UI.vertical(ctx).apply {
                setPadding(ctx.dp(10), 0, 0, 0)
                addView(UI.text(ctx, sec.title, TextStyle.TITLE).apply { isAccessibilityHeading = true })
                addView(UI.text(ctx, sec.subtitle, TextStyle.CAPTION))
            })
            col.addView(sh, lp().apply { topMargin = ctx.dp(28); bottomMargin = ctx.dp(12) })
            val tools = ToolId.entries.filter { it.section == sec }
            for (pair in tools.chunked(2)) {
                val row = UI.horizontal(ctx, Gravity.TOP)
                for ((k, t) in pair.withIndex()) {
                    row.addView(toolCard(t), lp(0, MATCH, 1f).apply { if (k == 0) rightMargin = ctx.dp(6) else leftMargin = ctx.dp(6) })
                }
                if (pair.size == 1) row.addView(View(ctx), lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(6) })
                col.addView(row, lp().apply { bottomMargin = ctx.dp(12) })
            }
        }

        col.addView(UI.note(ctx, "Local Media Tools works fully offline. Your photos, videos and documents are processed on this phone and never uploaded. Results are saved in LocalMediaTools folders inside Pictures, Movies, Music and Documents.", UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(20) })

        scope.launch { Workload.percent.collect { updateWorkload(it) } }
        scope.launch { ExportManager.state.collect { updateActivity(it) } }
        return scroll
    }

    private fun feature(icon: Int, text: String, color: Int): View = UI.horizontal(ctx).apply {
        background = Shapes.pill(ctx, Palette.withAlpha(color, 0x1F))
        setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        addView(UI.iconView(ctx, icon, color, 16))
        addView(UI.text(ctx, text, TextStyle.CAPTION, Palette.TEXT).apply { setPadding(ctx.dp(7), 0, 0, 0) })
    }

    private fun toolCard(t: ToolId): View {
        val color = Palette.section(t.section)
        val card = UI.vertical(ctx, 14, 14)
        card.background = Shapes.clickable(ctx, Palette.SURFACE, 20f, Palette.STROKE)
        card.minimumHeight = ctx.dp(138)
        card.addView(UI.iconTile(ctx, Icons.tool(t), color, 42, 23))
        card.addView(UI.text(ctx, t.title, TextStyle.SUBTITLE).apply { setPadding(0, ctx.dp(12), 0, 0) })
        card.addView(UI.text(ctx, t.shortDescription, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(4), 0, 0) })
        card.isClickable = true; card.isFocusable = true
        card.contentDescription = "${t.title}. ${t.shortDescription}"
        card.setOnClickListener { push(ToolScreens.create(activity, t)) }
        return card
    }

    private fun updateWorkload(p: Int) {
        val prof = WorkloadProfile(p)
        workloadValue.text = "${prof.label} · $p%"
        workloadDesc.text = Workload.presetFor(p)?.summary ?: prof.explain()
    }

    private fun updateActivity(s: ExportManager.State) {
        val a = s.active
        activityLine.text = when {
            a != null -> "Running: ${a.title} · ${(a.fraction * 100).toInt()}%" + if (s.queued.isNotEmpty()) " · ${s.queued.size} queued" else ""
            s.queued.isNotEmpty() -> "${s.queued.size} export(s) queued"
            else -> "No export running" + (s.history.firstOrNull()?.let { " · last: ${it.tool.title}" } ?: "")
        }
        activityLine.setTextColor(if (a != null) Palette.ACCENT else Palette.TEXT_2)
    }
}
