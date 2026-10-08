package com.localmediatools.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ExportManager
import com.localmediatools.tools.ToolId
import kotlinx.coroutines.launch

/**
 * Common layout for every tool: what it does, what to select/configure (numbered steps), where the
 * results go, live export status, and a sticky primary action.
 */
abstract class ToolScreen(activity: MainActivity, val tool: ToolId) : Screen(activity) {
    protected val selection: Selection = Selection.of(tool)
    protected lateinit var content: LinearLayout
    private lateinit var startButton: ButtonView
    private lateinit var helper: TextView
    private var selectionPanel: SelectionPanel? = null
    private val selectionListener = { refreshValidation(); onSelectionChanged() }
    protected val accent get() = Palette.section(tool.section)

    /** Adds the tool's option views. */
    protected abstract fun buildOptions(container: LinearLayout)

    /** Null when ready to export; otherwise a short instruction shown above the button. */
    protected open fun validate(): String? {
        val usable = selection.usable.size
        val min = ToolRules.minItems(tool)
        val noun = ToolRules.pickKind(tool).noun
        return when {
            selection.loading > 0 -> "Reading the selected files…"
            usable == 0 -> "Select $noun to continue"
            usable < min -> "Select at least $min $noun"
            else -> null
        }
    }

    protected abstract fun createJob(items: List<MediaItem>): ExportJob

    /** Explains naming of the outputs. */
    protected abstract fun outputNaming(): String

    protected open fun onSelectionChanged() {}

    /** Screens with their own selection UI (scanner) override this. */
    protected open fun buildSelection(container: LinearLayout) {
        val p = SelectionPanel(this, selection, "Select ${ToolRules.pickKind(tool).noun}")
        selectionPanel = p
        container.addView(p, lp().apply { topMargin = ctx.dp(14) })
    }

    protected open val startLabel: String get() = "Start export"

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false }
        content = UI.vertical(ctx, 16, 0)
        content.setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(150))
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        content.addView(TopBar(this, tool.title).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })
        content.addView(hero())
        buildSelection(content)

        val opts = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 2))
        head.addView(UI.text(ctx, "Options", TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
        opts.addView(head)
        val optBody = UI.vertical(ctx)
        buildOptions(optBody)
        opts.addView(optBody, lp().apply { topMargin = ctx.dp(8) })
        content.addView(opts, lp().apply { topMargin = ctx.dp(14) })

        content.addView(outputCard(), lp().apply { topMargin = ctx.dp(14) })
        content.addView(ExportStatusCard(this, tool), lp().apply { topMargin = ctx.dp(14) })

        // Sticky action bar.
        val bar = UI.vertical(ctx, 16, 12)
        bar.background = Shapes.rounded(ctx, Palette.SURFACE, 24f, Palette.STROKE)
        bar.elevation = ctx.dp(8).toFloat()
        helper = UI.text(ctx, "", TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ctx.dp(8)) }
        bar.addView(helper)
        startButton = UI.primaryButton(ctx, startLabel) { start() }
        bar.addView(startButton, lp())
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, ctx.dp(10), ctx.dp(10)) })

        selection.listen(selectionListener)
        refreshValidation()
        scope.launch { ExportManager.state.collect { refreshValidation() } }
        return root
    }

    private fun hero(): View {
        val c = UI.card(ctx)
        val row = UI.horizontal(ctx, Gravity.TOP)
        row.addView(UI.toolTile(ctx, tool, 52, 28))
        row.addView(UI.text(ctx, tool.longDescription, TextStyle.BODY_2).apply { setPadding(ctx.dp(14), 0, 0, 0) }, lp(0, WRAP, 1f))
        c.addView(row)
        val chips = FlowLayout(ctx)
        chips.addView(chip(R.drawable.ic_shield, "On-device", Palette.SUCCESS))
        chips.addView(chip(R.drawable.ic_lock, "Originals untouched", Palette.TEXT_2))
        chips.addView(chip(R.drawable.ic_clock, "Runs in background", Palette.TEXT_2))
        c.addView(chips, lp().apply { topMargin = ctx.dp(12) })
        return c
    }

    protected fun chip(icon: Int, text: String, color: Int): View = UI.horizontal(ctx).apply {
        background = Shapes.pill(ctx, Palette.SURFACE_2, Palette.STROKE)
        setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        addView(UI.iconView(ctx, icon, color, 15))
        addView(UI.text(ctx, text, TextStyle.CAPTION, Palette.TEXT_2).apply { setPadding(ctx.dp(6), 0, 0, 0) })
    }

    private fun outputCard(): View {
        val c = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 3))
        head.addView(UI.text(ctx, "Output", TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
        c.addView(head)
        val row = UI.horizontal(ctx, Gravity.TOP)
        row.setPadding(0, ctx.dp(12), 0, 0)
        row.addView(UI.iconView(ctx, R.drawable.ic_folder, accent, 20))
        row.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(10), 0, 0, 0)
            addView(UI.text(ctx, tool.outputPath, TextStyle.BODY).apply { setTextIsSelectable(true) })
            addView(UI.text(ctx, outputNaming(), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(4), 0, 0) })
        }, lp(0, WRAP, 1f))
        c.addView(row)
        c.addView(UI.note(ctx, "New files are always created; your originals are never changed. A file is only published after it has been fully written and checked.", UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(12) })
        return c
    }

    fun refreshValidation() {
        if (!::startButton.isInitialized) return
        val msg = validate()
        startButton.isEnabled = msg == null
        val busy = ExportManager.state.value.busy
        helper.text = msg ?: if (busy) "Another export is running; this one will start right after it." else
            "${selection.usable.size} ${if (selection.usable.size == 1) "file" else "files"} ready · you can leave the app while it runs"
        helper.setTextColor(if (msg == null) Palette.TEXT_2 else Palette.WARNING)
        startButton.label = if (msg == null && busy) "Add to queue" else startLabel
    }

    protected open fun itemsForJob(): List<MediaItem> = selection.usable

    private fun start() {
        if (validate() != null) return
        val job = try { createJob(itemsForJob()) } catch (e: IllegalArgumentException) {
            Toast.makeText(ctx, e.message ?: "Check the options", Toast.LENGTH_LONG).show(); return
        }
        activity.ensureNotificationPermission {
            val wasBusy = ExportManager.state.value.busy
            ExportManager.enqueue(job)
            Toast.makeText(ctx, if (wasBusy) "Queued — it starts when the current export finishes." else "Export started. You can leave the app; progress is shown in the notification.", Toast.LENGTH_LONG).show()
            refreshValidation()
        }
    }

    override fun onShow() { refreshValidation(); selectionPanel?.refresh() }

    override fun onDestroy() {
        selection.unlisten(selectionListener)
        selectionPanel?.detach()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ option helpers
    protected fun section(container: LinearLayout, title: String, subtitle: String? = null, top: Int = 16) {
        container.addView(UI.titled(ctx, title, subtitle), lp().apply { topMargin = ctx.dp(top) })
    }

    protected fun gap(container: LinearLayout, dp: Int = 10) = container.addView(UI.spacer(ctx, dp))
}
