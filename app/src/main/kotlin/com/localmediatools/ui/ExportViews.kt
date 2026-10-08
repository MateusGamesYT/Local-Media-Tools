package com.localmediatools.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.core.OutputFile
import com.localmediatools.core.Workload
import com.localmediatools.core.WorkloadProfile
import com.localmediatools.export.ExportManager
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.savedTo
import com.localmediatools.export.JobSnapshot
import com.localmediatools.export.JobStatus
import com.localmediatools.tools.ToolId
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Back arrow + title (+ optional trailing view). */
class TopBar(screen: Screen, title: String, trailing: View? = null) : LinearLayout(screen.ctx) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(6), dp(8), dp(6))
        addView(UI.iconButton(context, R.drawable.ic_back, "Back") { screen.activity.navigator.back() })
        addView(UI.text(context, title, TextStyle.TITLE).apply {
            setPadding(dp(4), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isFocusable = true
            isAccessibilityHeading = true
        }, lp(0, WRAP, 1f))
        trailing?.let { addView(it) }
    }
}

object OutputActions {
    fun open(activity: MainActivity, f: OutputFile) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(i) } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "No installed app can open ${f.mime} files.", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(activity, "This file is no longer available.", Toast.LENGTH_LONG).show()
        }
    }

    fun share(activity: MainActivity, files: List<OutputFile>) {
        if (files.isEmpty()) return
        val i = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(files[0].mime).putExtra(Intent.EXTRA_STREAM, files[0].uri)
        } else {
            val mime = files.map { it.mime }.distinct().singleOrNull() ?: (files.map { it.mime.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*")
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(files.map { it.uri }))
        }
        val clip = ClipData.newRawUri("", files[0].uri)
        files.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(Intent.createChooser(i, "Share")) } catch (_: Exception) { }
    }
}

/** Live status of the latest export for a tool (or all tools on the home screen). */
class ExportStatusCard(private val screen: Screen, private val tool: ToolId?) : LinearLayout(screen.ctx) {
    private val iconHolder = android.widget.FrameLayout(context)
    private val title = UI.text(context, "", TextStyle.SUBTITLE)
    private val status = UI.text(context, "", TextStyle.CAPTION)
    private val bar = ProgressBarView(context)
    private val pct = UI.text(context, "", TextStyle.CAPTION, Palette.TEXT)
    private val workloadLine = UI.text(context, "", TextStyle.CAPTION, Palette.TEXT_3)
    private val actions = FlowLayout(context)
    private var shownId = -1L
    private var shownStatus: JobStatus? = null

    init {
        orientation = VERTICAL
        background = Shapes.rounded(context, Palette.SURFACE, 20f, Palette.STROKE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        val row = UI.horizontal(context)
        row.addView(iconHolder, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(UI.vertical(context).apply { setPadding(dp(12), 0, 0, 0); addView(title); addView(status.apply { setPadding(0, dp(3), 0, 0) }) }, lp(0, WRAP, 1f))
        addView(row)
        val barRow = UI.horizontal(context)
        barRow.addView(bar, lp(0, WRAP, 1f))
        barRow.addView(pct.apply { setPadding(dp(10), 0, 0, 0) })
        addView(barRow, lp().apply { topMargin = dp(12) })
        addView(workloadLine, lp().apply { topMargin = dp(8) })
        addView(actions, lp().apply { topMargin = dp(10) })
        visibility = GONE
        screen.scope.launch { ExportManager.state.collect { render(it) } }
        screen.scope.launch { Workload.percent.collect { render(ExportManager.state.value) } }
    }

    private fun render(s: ExportManager.State) {
        val snap: JobSnapshot? = if (tool == null) s.active ?: s.queued.firstOrNull() else s.latestFor(tool)
        if (snap == null) { visibility = GONE; return }
        visibility = VISIBLE
        val running = snap.status == JobStatus.RUNNING
        val queued = snap.status == JobStatus.QUEUED
        title.text = snap.title
        val color = when (snap.status) {
            JobStatus.SUCCEEDED -> Palette.SUCCESS
            JobStatus.PARTIAL -> Palette.WARNING
            JobStatus.FAILED -> Palette.DANGER
            JobStatus.CANCELLED -> Palette.TEXT_3
            else -> Palette.ACCENT
        }
        val iconRes = when (snap.status) {
            JobStatus.SUCCEEDED -> R.drawable.ic_check
            JobStatus.PARTIAL, JobStatus.FAILED -> R.drawable.ic_warning
            JobStatus.CANCELLED -> R.drawable.ic_close
            else -> R.drawable.ic_clock
        }
        iconHolder.removeAllViews()
        iconHolder.addView(UI.iconTile(context, iconRes, color, 40, 22))
        status.text = when {
            running -> buildString {
                append(snap.statusText)
                snap.currentName?.let { append(" · ").append(it) }
            }
            queued -> "Waiting for the current export to finish"
            else -> snap.summary() + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snap.finishedAt))
        }
        bar.visibility = if (running || queued) VISIBLE else GONE
        pct.visibility = bar.visibility
        bar.indeterminate = queued || (running && snap.fraction <= 0.0)
        if (running) bar.setProgress(snap.fraction.toFloat(), color)
        pct.text = if (running) "${(snap.fraction * 100).toInt()}%" else ""
        val wl = WorkloadProfile(Workload.percent.value)
        workloadLine.visibility = if (running || queued) VISIBLE else GONE
        workloadLine.text = "Export workload: ${wl.label} (${wl.percent}%) · ${if (snap.unitCount > 1) "${snap.unitsDone} of ${snap.unitCount} done" else "1 job"}"
        if (shownId != snap.id || shownStatus != snap.status) {
            shownId = snap.id; shownStatus = snap.status
            actions.removeAllViews()
            if (running || queued) {
                actions.addView(UI.secondaryButton(context, "Cancel", R.drawable.ic_close) { confirmCancel(snap) })
                actions.addView(UI.ghostButton(context, "Workload", R.drawable.ic_tune) { WorkloadDialog.show(screen.activity) })
            } else {
                actions.addView(UI.secondaryButton(context, "View results", R.drawable.ic_chevron) { screen.push(ResultsScreen(screen.activity, snap.id)) })
                val outs = snap.outputs
                if (outs.size == 1) actions.addView(UI.ghostButton(context, "Open", R.drawable.ic_open) { OutputActions.open(screen.activity, outs[0]) })
                if (outs.isNotEmpty()) actions.addView(UI.ghostButton(context, "Share", R.drawable.ic_share) { OutputActions.share(screen.activity, outs) })
            }
        }
        contentDescription = "${title.text}. ${status.text}. ${pct.text}"
    }

    private fun confirmCancel(snap: JobSnapshot) {
        AlertDialog.Builder(screen.activity)
            .setTitle("Cancel this export?")
            .setMessage("Files already saved are kept. The file being processed is discarded, never left half-written.")
            .setPositiveButton("Cancel export") { _, _ -> ExportManager.cancel(snap.id) }
            .setNegativeButton("Keep going", null)
            .show()
    }
}

/** Global workload control: continuous slider plus presets. */
object WorkloadDialog {
    /** Presets, slider and explanation; changes apply immediately (also to running exports). */
    fun content(ctx: android.content.Context, withNote: Boolean = true): LinearLayout {
        val root = UI.vertical(ctx)
        val explain = UI.text(ctx, "", TextStyle.CAPTION)
        var slider: SliderField? = null
        val presets = ChoiceGroup(ctx, Workload.Preset.entries, { "${it.label} ${it.percent}%" }, Workload.presetFor(Workload.percent.value)) { p ->
            slider?.setValue(p.percent, fromField = false)
        }
        fun update(v: Int) {
            Workload.set(ctx, v)
            val p = WorkloadProfile(v)
            explain.text = (Workload.presetFor(v)?.summary ?: "Custom setting.") + "\n" + p.explain()
            Workload.presetFor(v)?.let { presets.select(it, notify = false) }
        }
        slider = SliderField(ctx, "Export workload", Workload.MIN, Workload.MAX, Workload.percent.value, "%", "How hard exports may work the phone") { update(it) }
        root.addView(presets, lp().apply { topMargin = ctx.dp(4) })
        root.addView(slider, lp().apply { topMargin = ctx.dp(14) })
        root.addView(explain, lp().apply { topMargin = ctx.dp(8) })
        if (withNote) root.addView(UI.note(ctx, "This is not an exact CPU-percentage limit. It sets parallel work, thread priority, pauses between steps and how much free memory an export may use. Changes apply to running exports within seconds.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        update(Workload.percent.value)
        return root
    }

    fun show(activity: MainActivity) {
        val root = content(activity).apply { setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(8)) }
        val scroll = ScrollView(activity).apply { addView(root) }
        AlertDialog.Builder(activity)
            .setTitle("Export workload")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }
}

/** Detailed results of one export: every input, its outcome and its outputs. */
class ResultsScreen(activity: MainActivity, private val jobId: Long) : Screen(activity) {
    private lateinit var body: LinearLayout

    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        root.addView(TopBar(this, "Export results"))
        body = UI.vertical(ctx, 16, 4)
        root.addView(ScrollView(ctx).apply { addView(body); isFillViewport = true }, lp(MATCH, 0, 1f))
        scope.launch { ExportManager.state.collect { render(it) } }
        return root
    }

    private var lastKey = ""

    private fun render(s: ExportManager.State) {
        val snap = (listOfNotNull(s.active) + s.queued + s.history).firstOrNull { it.id == jobId }
        val key = "${snap?.status}:${snap?.results?.size}"
        if (key == lastKey && snap?.status != JobStatus.RUNNING) return
        lastKey = key
        body.removeAllViews()
        if (snap == null) { body.addView(UI.text(ctx, "This export is no longer in the history.", TextStyle.BODY_2)); return }
        body.addView(ExportStatusCardStatic(snap))
        val outs = snap.outputs
        if (outs.isNotEmpty()) {
            val row = FlowLayout(ctx)
            row.addView(UI.secondaryButton(ctx, "Share ${if (outs.size == 1) "file" else "all ${outs.size}"}", R.drawable.ic_share) { OutputActions.share(activity, outs) })
            body.addView(row, lp().apply { topMargin = ctx.dp(12) })
            body.addView(UI.note(ctx, "Saved to ${snap.savedTo}. Find them in your gallery, Files app or the folder named LocalMediaTools.", UI.NoteKind.SUCCESS), lp().apply { topMargin = ctx.dp(12) })
        }
        val groups = listOf(ItemOutcome.FAILED, ItemOutcome.SKIPPED, ItemOutcome.SUCCESS)
        for (g in groups) {
            val items = snap.results.filter { it.outcome == g }
            if (items.isEmpty()) continue
            val label = when (g) { ItemOutcome.FAILED -> "Failed (${items.size})"; ItemOutcome.SKIPPED -> "Skipped (${items.size})"; else -> "Saved (${items.size})" }
            body.addView(UI.label(ctx, label), lp().apply { topMargin = ctx.dp(20); bottomMargin = ctx.dp(8) })
            for (r in items.take(300)) body.addView(resultRow(r), lp().apply { bottomMargin = ctx.dp(8) })
            if (items.size > 300) body.addView(UI.text(ctx, "…and ${items.size - 300} more", TextStyle.CAPTION))
        }
        body.addView(UI.spacer(ctx, 24))
    }

    @Suppress("FunctionName")
    private fun ExportStatusCardStatic(s: JobSnapshot): View {
        val c = UI.card(ctx)
        val color = when (s.status) { JobStatus.SUCCEEDED -> Palette.SUCCESS; JobStatus.PARTIAL -> Palette.WARNING; JobStatus.FAILED -> Palette.DANGER; else -> Palette.ACCENT }
        val row = UI.horizontal(ctx)
        row.addView(UI.toolTile(ctx, s.tool, 44, 24))
        row.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(12), 0, 0, 0)
            addView(UI.text(ctx, s.title, TextStyle.SUBTITLE))
            addView(UI.text(ctx, s.summary(), TextStyle.CAPTION, color).apply { setPadding(0, ctx.dp(3), 0, 0) })
        }, lp(0, WRAP, 1f))
        c.addView(row)
        if (s.status == JobStatus.RUNNING) {
            val bar = ProgressBarView(ctx); bar.setProgress(s.fraction.toFloat())
            c.addView(bar, lp().apply { topMargin = ctx.dp(12) })
            c.addView(UI.text(ctx, "${(s.fraction * 100).toInt()}% · ${s.statusText}", TextStyle.CAPTION).apply { setPadding(0, ctx.dp(6), 0, 0) })
        } else if (s.finishedAt > 0) {
            val secs = ((s.finishedAt - s.startedAt) / 1000).coerceAtLeast(0)
            c.addView(UI.text(ctx, "Finished ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.finishedAt))}" +
                (if (s.startedAt > 0) " · took ${Format.duration(secs * 1000)}" else "") + " · workload ${s.workloadPercent}%", TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(0, ctx.dp(10), 0, 0) })
        }
        return c
    }

    private fun resultRow(r: ItemResult): View {
        val c = UI.card(ctx, 14, Palette.SURFACE)
        val color = when (r.outcome) { ItemOutcome.SUCCESS -> Palette.SUCCESS; ItemOutcome.SKIPPED -> Palette.WARNING; ItemOutcome.FAILED -> Palette.DANGER }
        val icon = when (r.outcome) { ItemOutcome.SUCCESS -> R.drawable.ic_check; ItemOutcome.SKIPPED -> R.drawable.ic_info; ItemOutcome.FAILED -> R.drawable.ic_warning }
        val head = UI.horizontal(ctx, Gravity.TOP)
        head.addView(UI.iconView(ctx, icon, color, 20))
        head.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(10), 0, 0, 0)
            addView(UI.text(ctx, r.inputName, TextStyle.BODY).apply { maxLines = 2 })
            r.details?.let { addView(UI.text(ctx, it, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(3), 0, 0) }) }
            r.message?.let { addView(UI.text(ctx, it, TextStyle.CAPTION, if (r.outcome == ItemOutcome.SUCCESS) Palette.TEXT_2 else color).apply { setPadding(0, ctx.dp(4), 0, 0) }) }
        }, lp(0, WRAP, 1f))
        c.addView(head)
        for (f in r.outputs.take(50)) {
            val row = UI.horizontal(ctx)
            row.setPadding(ctx.dp(30), ctx.dp(8), 0, 0)
            row.addView(UI.text(ctx, "${f.displayName} · ${Format.bytes(f.size)}", TextStyle.CAPTION, Palette.TEXT).apply { maxLines = 2 }, lp(0, WRAP, 1f))
            row.addView(UI.iconButton(ctx, R.drawable.ic_open, "Open ${f.displayName}", Palette.ACCENT) { OutputActions.open(activity, f) })
            row.addView(UI.iconButton(ctx, R.drawable.ic_share, "Share ${f.displayName}", Palette.ACCENT) { OutputActions.share(activity, listOf(f)) })
            c.addView(row)
        }
        if (r.outputs.size > 50) c.addView(UI.text(ctx, "…and ${r.outputs.size - 50} more files", TextStyle.CAPTION).apply { setPadding(ctx.dp(30), ctx.dp(6), 0, 0) })
        return c
    }
}

/** Recent exports, newest first. */
class HistoryScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var body: LinearLayout

    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        val clear = UI.ghostButton(ctx, "Clear") {
            AlertDialog.Builder(activity).setTitle("Clear export history?")
                .setMessage("Only the list is cleared; saved files stay on your phone.")
                .setPositiveButton("Clear") { _, _ -> ExportManager.clearHistory() }
                .setNegativeButton("Cancel", null).show()
        }
        root.addView(TopBar(this, "Recent exports", clear))
        body = UI.vertical(ctx, 16, 4)
        root.addView(ScrollView(ctx).apply { addView(body) }, lp(MATCH, 0, 1f))
        scope.launch { ExportManager.state.collect { render(it) } }
        return root
    }

    private fun render(s: ExportManager.State) = HistoryList.render(this, body, s)
}

/** The list of exports (running, queued, finished), shared by the history screen and the Activity tab. */
object HistoryList {
    fun render(screen: Screen, body: LinearLayout, s: ExportManager.State, emptyText: String = "No exports yet. Results of every export appear here.") {
        val ctx = screen.ctx
        body.removeAllViews()
        val all = listOfNotNull(s.active) + s.queued + s.history
        if (all.isEmpty()) {
            body.addView(UI.vertical(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ctx.dp(24), ctx.dp(36), ctx.dp(24), ctx.dp(36))
                background = Shapes.rounded(ctx, Palette.SURFACE, 22f, Palette.STROKE)
                addView(UI.iconTile(ctx, R.drawable.ic_activity, Palette.ACCENT, 52, 26))
                addView(UI.text(ctx, "Nothing here yet", TextStyle.SUBTITLE).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(14), 0, 0) })
                addView(UI.text(ctx, emptyText, TextStyle.BODY_2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0) })
            }, lp().apply { topMargin = ctx.dp(8) })
            return
        }
        for (snap in all) {
            val row = UI.horizontal(ctx)
            row.background = Shapes.clickable(ctx, Palette.SURFACE, 20f, Palette.STROKE)
            row.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(10), ctx.dp(12))
            row.addView(UI.toolTile(ctx, snap.tool, 40, 22))
            val color = when (snap.status) { JobStatus.SUCCEEDED -> Palette.SUCCESS; JobStatus.PARTIAL -> Palette.WARNING; JobStatus.FAILED -> Palette.DANGER; JobStatus.RUNNING, JobStatus.QUEUED -> Palette.ACCENT; else -> Palette.TEXT_3 }
            row.addView(UI.vertical(ctx).apply {
                setPadding(ctx.dp(12), 0, ctx.dp(6), 0)
                addView(UI.text(ctx, snap.title, TextStyle.BODY).apply { maxLines = 2 })
                val running = snap.status == JobStatus.RUNNING
                addView(UI.text(ctx, if (running) "${(snap.fraction * 100).toInt()}% · ${snap.statusText}" else snap.summary(), TextStyle.CAPTION, color).apply { setPadding(0, ctx.dp(3), 0, 0) })
                if (snap.finishedAt > 0) addView(UI.text(ctx, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(snap.finishedAt)), TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(0, ctx.dp(2), 0, 0) })
            }, lp(0, WRAP, 1f))
            row.addView(UI.iconView(ctx, R.drawable.ic_chevron, Palette.TEXT_3, 18))
            row.isClickable = true
            row.contentDescription = "${snap.title}. ${snap.summary()}"
            row.setOnClickListener { screen.push(ResultsScreen(screen.activity, snap.id)) }
            body.addView(row, lp().apply { bottomMargin = ctx.dp(10) })
        }
    }
}

@Suppress("unused")
private fun TextView.bold() = apply { typeface = typeface(700) }
