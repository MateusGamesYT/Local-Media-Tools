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
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.tools.StackRules
import com.localmediatools.tools.StackStep
import com.localmediatools.tools.ToolId
import kotlinx.coroutines.launch

/** A tool screen opened to set up one step of a tool stack instead of exporting. */
class StackMode(
    val stepNumber: Int,
    /** What the step works on, e.g. "Works on the 3 files you picked." */
    val input: String,
    /** The stack's own files that this tool can work on, used for previews. */
    val previewItems: List<MediaItem>,
    val editing: Boolean,
    val onSave: (StackStep) -> Unit,
)

/**
 * Common layout for every tool: what it does, what to select/configure (numbered steps), where the
 * results go, live export status, and a sticky primary action.
 */
abstract class ToolScreen(activity: MainActivity, val tool: ToolId) : Screen(activity) {
    /** Set before the screen is shown to configure a tool-stack step. */
    var stack: StackMode? = null
    /** In stack mode: the stack's files this tool can use for previews (not the tool's own selection). */
    protected val selection: Selection by lazy {
        stack?.let { m -> Selection(tool).apply { items.addAll(m.previewItems.filter { ToolRules.issue(tool, it) == null }) } } ?: Selection.of(tool)
    }
    protected lateinit var content: LinearLayout
    protected lateinit var optionsBody: LinearLayout
    /** True while checking settings for a stack step from the normal screen (file count doesn't matter). */
    private var checkingStep = false
    private lateinit var startButton: ButtonView
    private lateinit var helper: TextView
    private var selectionPanel: SelectionPanel? = null
    private val selectionListener = { refreshValidation(); onSelectionChanged() }
    protected val accent get() = Palette.section(tool.section)

    /** Adds the tool's option views. */
    protected abstract fun buildOptions(container: LinearLayout)

    /** Null when ready to export; otherwise a short instruction shown above the button. */
    protected open fun validate(): String? {
        if (stack != null || checkingStep) return if (selection.loading > 0) tr("Reading the files…") else null
        val usable = selection.usable.size
        val min = ToolRules.minItems(tool)
        val kind = ToolRules.pickKind(tool)
        return when {
            selection.loading > 0 -> tr("Reading the selected files…")
            usable == 0 -> kind.selectToContinue()
            usable < min -> kind.selectAtLeast(min)
            else -> null
        }
    }

    protected abstract fun createJob(items: List<MediaItem>): ExportJob

    /**
     * The job for a tool-stack step, holding the current settings. Its files are set when the
     * stack runs; the selection's files only serve as a starting point.
     */
    protected open fun createStepJob(): ExportJob = createJob(selection.usable)

    /** Short description of the settings, shown in the stack (e.g. "WebP · Quality 80"). */
    protected open fun stackSummary(): String = OptionSummary.of(optionsBody)

    /** True while a stack step is being set up or checked (not exporting this tool's own selection). */
    protected val stepMode: Boolean get() = stack != null || checkingStep

    /** Explains naming of the outputs. */
    protected abstract fun outputNaming(): String

    protected open fun onSelectionChanged() {}

    /** Screens with their own selection UI (scanner) override this. */
    protected open fun buildSelection(container: LinearLayout) {
        val p = SelectionPanel(this, selection, ToolRules.pickKind(tool).select())
        selectionPanel = p
        container.addView(p, lp().apply { topMargin = ctx.dp(14) })
    }

    protected open val startLabel: String get() = tr("Start export")

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false }
        content = UI.vertical(ctx, 16, 0)
        content.setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(150))
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val st = stack
        content.addView(TopBar(this, if (st != null) tr("Step {0}: {1}", st.stepNumber, tool.title) else tool.title).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })
        content.addView(hero())
        if (st != null) content.addView(stackInputCard(st), lp().apply { topMargin = ctx.dp(14) }) else buildSelection(content)

        val opts = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 2))
        head.addView(UI.text(ctx, tr("Options"), TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
        opts.addView(head)
        val optBody = UI.vertical(ctx)
        optionsBody = optBody
        buildOptions(optBody)
        opts.addView(optBody, lp().apply { topMargin = ctx.dp(8) })
        content.addView(opts, lp().apply { topMargin = ctx.dp(14) })

        if (st != null) {
            content.addView(stackResultCard(), lp().apply { topMargin = ctx.dp(14) })
        } else {
            content.addView(outputCard(), lp().apply { topMargin = ctx.dp(14) })
            content.addView(ExportStatusCard(this, tool), lp().apply { topMargin = ctx.dp(14) })
        }

        // Sticky action bar.
        val bar = UI.vertical(ctx, 16, 12)
        bar.background = Shapes.rounded(ctx, Palette.SURFACE, 24f, Palette.STROKE)
        bar.elevation = ctx.dp(8).toFloat()
        helper = UI.text(ctx, "", TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ctx.dp(8)) }
        bar.addView(helper)
        startButton = UI.primaryButton(ctx, buttonLabel) { if (stack != null) saveStep() else start() }
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
        for (v in heroChips()) chips.addView(v)
        c.addView(chips, lp().apply { topMargin = ctx.dp(12) })
        return c
    }

    protected open fun heroChips(): List<View> = listOf(
        chip(R.drawable.ic_shield, tr("On-device"), Palette.SUCCESS),
        chip(R.drawable.ic_lock, tr("Originals untouched"), Palette.TEXT_2),
        chip(R.drawable.ic_clock, tr("Runs in background"), Palette.TEXT_2),
    )

    protected fun chip(icon: Int, text: String, color: Int): View = UI.horizontal(ctx).apply {
        background = Shapes.pill(ctx, Palette.SURFACE_2, Palette.STROKE)
        setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        addView(UI.iconView(ctx, icon, color, 15))
        addView(UI.text(ctx, text, TextStyle.CAPTION, Palette.TEXT_2).apply { setPadding(ctx.dp(6), 0, 0, 0) })
    }

    protected open fun outputCard(): View {
        val c = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 3))
        head.addView(UI.text(ctx, tr("Output"), TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
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
        c.addView(UI.note(ctx, tr("New files are always created; your originals are never changed. A file is only published after it has been fully written and checked."), UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(12) })
        if (StackRules.stackable(tool)) {
            val row = UI.horizontal(ctx)
            row.setPadding(0, ctx.dp(14), 0, 0)
            row.addView(UI.iconView(ctx, R.drawable.ic_tool_stack, Palette.section(com.localmediatools.tools.ToolSection.STACKS), 20))
            row.addView(UI.titled(ctx, tr("Do more with the results?"), tr("Stack another tool that works on them, e.g. compress after this")).apply { setPadding(ctx.dp(10), 0, ctx.dp(8), 0) }, lp(0, WRAP, 1f))
            c.addView(row)
            c.addView(UI.secondaryButton(ctx, tr("Then run another tool"), R.drawable.ic_add) { startStack() }, lp().apply { topMargin = ctx.dp(10) })
        }
        return c
    }

    // ------------------------------------------------------------------ tool stacks
    private val buttonLabel: String get() = stack?.let { if (it.editing) tr("Save step") else tr("Add to stack") } ?: startLabel

    private fun stackInputCard(m: StackMode): View {
        val c = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 1))
        head.addView(UI.text(ctx, tr("Input"), TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
        c.addView(head)
        c.addView(UI.text(ctx, m.input, TextStyle.BODY).apply { setPadding(0, ctx.dp(10), 0, 0) })
        val previews = selection.usable
        c.addView(UI.text(ctx, if (previews.isEmpty()) tr("Files it can't work on go to the next step unchanged.")
            else tr("Previews use your file \"{0}\". Files it can't work on go to the next step unchanged.", previews.first().name), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(6), 0, 0) })
        return c
    }

    private fun stackResultCard(): View {
        val c = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 3))
        head.addView(UI.text(ctx, tr("Result"), TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
        c.addView(head)
        c.addView(UI.text(ctx, tr("Goes to the next step. If this is the last step, the results are saved in {0}.", I18n.andList(tool.outputPath.split("\n"))), TextStyle.BODY_2).apply { setPadding(0, ctx.dp(10), 0, 0) })
        return c
    }

    private fun currentStep(): StackStep? {
        val job = try { createStepJob() } catch (e: IllegalArgumentException) {
            Toast.makeText(ctx, e.message ?: tr("Check the options"), Toast.LENGTH_LONG).show(); return null
        }
        return StackStep(tool, stackSummary().ifBlank { tr("Standard settings") }, job)
    }

    private fun saveStep() {
        val m = stack ?: return
        if (validate() != null) return
        val step = currentStep() ?: return
        m.onSave(step)
        pop()
    }

    /** Starts a new tool stack with this tool (and its files and settings) as step 1. */
    private fun startStack() {
        checkingStep = true
        val msg = try { validate() } finally { checkingStep = false }
        if (msg != null) { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); return }
        checkingStep = true
        val step = try { currentStep() } finally { checkingStep = false } ?: return
        fun go() {
            ToolStackState.startWith(selection.items.toList(), step)
            push(StackScreen(activity))
        }
        if (ToolStackState.steps.isEmpty()) go() else android.app.AlertDialog.Builder(ctx)
            .setTitle(tr("Start a new tool stack?"))
            .setMessage(tr("Your current stack ({0}) will be replaced.", ToolStackState.steps.joinToString(" → ") { it.tool.title }))
            .setPositiveButton(tr("Start new")) { _, _ -> go() }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    fun refreshValidation() {
        if (!::startButton.isInitialized) return
        val msg = validate()
        startButton.isEnabled = msg == null
        val busy = ExportManager.state.value.busy
        helper.text = msg ?: if (stack != null) tr("Settings are kept with the step; tap it in the stack to change them") else if (busy) tr("Another export is running; this one will start right after it.") else
            trn(selection.usable.size, "{0} file ready · you can leave the app while it runs", "{0} files ready · you can leave the app while it runs")
        helper.setTextColor(if (msg == null) Palette.TEXT_2 else Palette.WARNING)
        startButton.label = if (msg == null && busy && stack == null) tr("Add to queue") else buttonLabel
    }

    protected open fun itemsForJob(): List<MediaItem> = selection.usable

    private fun start() {
        if (validate() != null) return
        val job = try { createJob(itemsForJob()) } catch (e: IllegalArgumentException) {
            Toast.makeText(ctx, e.message ?: tr("Check the options"), Toast.LENGTH_LONG).show(); return
        }
        activity.ensureNotificationPermission {
            val wasBusy = ExportManager.state.value.busy
            ExportManager.enqueue(job)
            Toast.makeText(ctx, startedText(wasBusy), Toast.LENGTH_LONG).show()
            refreshValidation()
        }
    }

    protected open fun startedText(queued: Boolean) =
        if (queued) tr("Queued — it starts when the current export finishes.") else tr("Export started. You can leave the app; progress is shown in the notification.")

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
