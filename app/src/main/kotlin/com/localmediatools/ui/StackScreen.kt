package com.localmediatools.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportManager
import com.localmediatools.tools.FlowKind
import com.localmediatools.tools.StackJob
import com.localmediatools.tools.StackRules
import com.localmediatools.tools.StackStep
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.ToolSection
import com.localmediatools.ui.tools.ToolScreens
import kotlinx.coroutines.launch

/** The tool stack being put together. Kept while the app runs, like each tool's selection. */
object ToolStackState {
    val selection: Selection get() = Selection.of(ToolId.TOOL_STACK)
    val steps = ArrayList<StackStep>()
    var keepInBetween = false

    fun startWith(items: List<MediaItem>, step: StackStep) {
        selection.clear()
        selection.add(items)
        steps.clear()
        steps.add(step)
    }

    /** Every kind a stack can start with (used before any files are picked). */
    val ALL: Set<FlowKind> = setOf(FlowKind.IMAGE, FlowKind.GIF, FlowKind.VIDEO, FlowKind.PDF)

    fun startKinds(): Set<FlowKind> = selection.usable.mapNotNull { FlowKind.of(it) }.toSet().ifEmpty { ALL }

    /** What step [index] gets (index = steps.size: what comes out at the end). */
    fun kindsBefore(index: Int): Set<FlowKind> {
        var k = startKinds()
        for (s in steps.take(index)) k = StackRules.after(s.tool, k)
        return k
    }
}

/** One-line description of a tool screen's chosen options, read from its option widgets. */
object OptionSummary {
    fun of(root: View): String {
        val parts = ArrayList<String>()
        fun add(p: String) { if (parts.none { it.contains(p, ignoreCase = true) }) parts.add(p) }
        fun walk(v: View) {
            // Hidden or switched-off options don't apply.
            if (v.visibility != View.VISIBLE || !v.isEnabled) return
            when (v) {
                is ChoiceGroup<*> -> v.selectedLabel()?.let { add(it) }
                is ToggleRow -> if (v.switch.isChecked && v.switch.isEnabled) add(v.title)
                // 0 means "keep the original" for the sliders that allow it.
                is SliderField -> add(if (v.value == 0) "${v.title}: original" else "${v.title} ${v.value}${v.unit}")
                is TextField -> v.edit.text?.toString()?.trim()?.takeIf { it.isNotBlank() && it.toDoubleOrNull() != 0.0 }?.let { add("${v.title}: $it") }
                is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        val s = parts.distinct().joinToString(" · ")
        return if (s.length > 140) s.take(137) + "…" else s
    }
}

/**
 * Tool stack: pick files once, add tools in order, run them all. Each step is set up on the
 * tool's own screen, so every option stays available.
 */
class StackScreen(activity: MainActivity) : Screen(activity) {
    private val state = ToolStackState
    private val selection get() = state.selection
    private lateinit var stepsBox: LinearLayout
    private lateinit var endInfo: TextView
    private lateinit var helper: TextView
    private lateinit var runButton: ButtonView
    private var panel: SelectionPanel? = null
    private val listener = { renderSteps() }

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val scroll = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false }
        val content = UI.vertical(ctx, 16, 0)
        content.setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(150))
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(TopBar(this, ToolId.TOOL_STACK.title).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })

        val hero = UI.card(ctx)
        val row = UI.horizontal(ctx, Gravity.TOP)
        row.addView(UI.toolTile(ctx, ToolId.TOOL_STACK, 52, 28))
        row.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(14), 0, 0, 0)
            addView(UI.text(ctx, "Run several tools in a row. Each tool works on the results of the one before; only the final files are saved.", TextStyle.BODY_2))
            addView(UI.text(ctx, "Example: Blur faces → Video compressor → Video → GIF → GIF optimizer", TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(0, ctx.dp(8), 0, 0) })
        }, lp(0, WRAP, 1f))
        hero.addView(row)
        content.addView(hero)

        val p = SelectionPanel(this, selection, "Pick your files")
        panel = p
        content.addView(p, lp().apply { topMargin = ctx.dp(14) })

        val steps = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 2))
        head.addView(UI.titled(ctx, "Steps", "Tap a step to change its settings").apply { setPadding(ctx.dp(10), 0, 0, 0) }, lp(0, WRAP, 1f))
        steps.addView(head)
        stepsBox = UI.vertical(ctx)
        steps.addView(stepsBox, lp().apply { topMargin = ctx.dp(12) })
        content.addView(steps, lp().apply { topMargin = ctx.dp(14) })

        val opts = UI.card(ctx)
        val oh = UI.horizontal(ctx)
        oh.addView(StepBadge(ctx, 3))
        oh.addView(UI.text(ctx, "Results", TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
        opts.addView(oh)
        endInfo = UI.text(ctx, "", TextStyle.BODY_2)
        opts.addView(endInfo, lp().apply { topMargin = ctx.dp(10) })
        opts.addView(ToggleRow(ctx, "Also save the in-between results", "Off: only the final files are saved; the rest are temporary", state.keepInBetween) { state.keepInBetween = it }, lp().apply { topMargin = ctx.dp(6) })
        opts.addView(UI.note(ctx, "If a step has nothing to do for a file (for example it's already small), or can't work on that kind of file, the file goes on to the next step unchanged, so nothing is lost.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(8) })
        content.addView(opts, lp().apply { topMargin = ctx.dp(14) })
        content.addView(ExportStatusCard(this, ToolId.TOOL_STACK), lp().apply { topMargin = ctx.dp(14) })

        val bar = UI.vertical(ctx, 16, 12)
        bar.background = Shapes.rounded(ctx, Palette.SURFACE, 24f, Palette.STROKE)
        bar.elevation = ctx.dp(8).toFloat()
        helper = UI.text(ctx, "", TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ctx.dp(8)) }
        bar.addView(helper)
        runButton = UI.primaryButton(ctx, "Run stack") { run() }
        bar.addView(runButton, lp())
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, ctx.dp(10), ctx.dp(10)) })

        selection.listen(listener)
        scope.launch { ExportManager.state.collect { refresh() } }
        renderSteps()
        return root
    }

    private fun flowLabel(text: String): View = UI.horizontal(ctx).apply {
        setPadding(ctx.dp(18), ctx.dp(6), 0, ctx.dp(6))
        addView(UI.iconView(ctx, R.drawable.ic_down, Palette.TEXT_3, 16))
        addView(UI.text(ctx, text, TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(ctx.dp(8), 0, 0, 0) })
    }

    private fun renderSteps() {
        if (!::stepsBox.isInitialized) return
        stepsBox.removeAllViews()
        val files = selection.usable
        var kinds = state.startKinds()
        stepsBox.addView(flowLabel(if (files.isEmpty()) "Starts with the files you pick" else "Starts with ${files.size} ${if (files.size == 1) "file" else "files"}: ${FlowKind.describe(kinds)}"))
        for ((i, step) in state.steps.withIndex()) {
            val usable = StackRules.accepts(step.tool).intersect(kinds)
            stepsBox.addView(stepRow(i, step, if (usable.isEmpty()) "Gets no files it can work on: it needs ${FlowKind.describe(StackRules.accepts(step.tool))}" else null))
            kinds = StackRules.after(step.tool, kinds)
            stepsBox.addView(flowLabel(if (i == state.steps.lastIndex) "Saved: ${FlowKind.describe(kinds)}" else "then ${FlowKind.describe(kinds)}"))
        }
        stepsBox.addView(UI.secondaryButton(ctx, if (state.steps.isEmpty()) "Add the first step" else "Add a step", R.drawable.ic_add) {
            push(StackToolPicker(activity, state.steps.size + 1, kinds) { t -> openStep(t, state.steps.size, editing = false) })
        }, lp().apply { topMargin = ctx.dp(6) })
        val last = state.steps.lastOrNull()
        endInfo.text = if (last == null) "Add steps to see where the results go." else
            "The final files are saved where step ${state.steps.size} saves them: ${last.tool.outputPath.replace("\n", " and ")}. Files that the last step doesn't change are saved by the last step that did."
        refresh()
    }

    private fun stepRow(i: Int, step: StackStep, problem: String?): View {
        val row = UI.horizontal(ctx, Gravity.CENTER_VERTICAL)
        row.background = Shapes.clickable(ctx, Palette.SURFACE_2, 18f, if (problem != null) Palette.WARNING else Palette.STROKE)
        row.setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(4), ctx.dp(12))
        row.addView(StepBadge(ctx, i + 1))
        row.addView(UI.toolTile(ctx, step.tool, 40, 22), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { leftMargin = ctx.dp(10) })
        row.addView(UI.vertical(ctx).apply {
            setPadding(ctx.dp(12), 0, ctx.dp(4), 0)
            addView(UI.text(ctx, step.tool.title, TextStyle.SUBTITLE))
            addView(UI.text(ctx, step.summary, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(3), 0, 0); maxLines = 4 })
            if (problem != null) addView(UI.text(ctx, problem, TextStyle.CAPTION, Palette.WARNING).apply { setPadding(0, ctx.dp(4), 0, 0) })
        }, lp(0, WRAP, 1f))
        if (i > 0) row.addView(UI.iconButton(ctx, R.drawable.ic_up, "Move step ${i + 1} up", Palette.TEXT_2) { move(i, i - 1) })
        if (i < state.steps.lastIndex) row.addView(UI.iconButton(ctx, R.drawable.ic_down, "Move step ${i + 1} down", Palette.TEXT_2) { move(i, i + 1) })
        row.addView(UI.iconButton(ctx, R.drawable.ic_close, "Remove step ${i + 1}", Palette.TEXT_2) { state.steps.removeAt(i); renderSteps() })
        row.isClickable = true; row.isFocusable = true
        row.contentDescription = "Step ${i + 1}: ${step.tool.title}. ${step.summary}. Tap to change"
        row.setOnClickListener { openStep(step.tool, i, editing = true) }
        return LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(row, lp()) }
    }

    private fun move(from: Int, to: Int) {
        val s = state.steps.removeAt(from)
        state.steps.add(to, s)
        renderSteps()
    }

    /** Opens [t]'s own screen to set up the step at [index]. */
    private fun openStep(t: ToolId, index: Int, editing: Boolean) {
        val files = selection.usable
        val input = if (index == 0) {
            if (files.isEmpty()) "the files you pick" else "the ${files.size} ${if (files.size == 1) "file" else "files"} you picked"
        } else {
            val k = StackRules.accepts(t).intersect(state.kindsBefore(index))
            "the ${FlowKind.describe(k)} coming from step $index (${state.steps[index - 1].tool.title})"
        }
        val screen = ToolScreens.create(activity, t)
        screen.stack = StackMode(index + 1, input, files, editing) { step ->
            if (editing && index < state.steps.size) state.steps[index] = step else state.steps.add(index.coerceAtMost(state.steps.size), step)
            renderSteps()
        }
        push(screen)
    }

    private fun validate(): String? {
        if (selection.loading > 0) return "Reading the selected files…"
        if (selection.usable.isEmpty()) return "Pick files to continue"
        if (state.steps.isEmpty()) return "Add at least one step"
        var kinds = state.startKinds()
        for ((i, s) in state.steps.withIndex()) {
            if (StackRules.accepts(s.tool).intersect(kinds).isEmpty()) return "Step ${i + 1} (${s.tool.title}) gets no files it can work on"
            kinds = StackRules.after(s.tool, kinds)
        }
        return null
    }

    private fun refresh() {
        if (!::runButton.isInitialized) return
        val msg = validate()
        runButton.isEnabled = msg == null
        val busy = ExportManager.state.value.busy
        helper.text = msg ?: "${selection.usable.size} ${if (selection.usable.size == 1) "file" else "files"} · ${state.steps.size} ${if (state.steps.size == 1) "step" else "steps"}" +
            if (busy) " · starts after the current export" else " · you can leave the app while it runs"
        helper.setTextColor(if (msg == null) Palette.TEXT_2 else Palette.WARNING)
        runButton.label = if (msg == null && busy) "Add to queue" else "Run stack"
    }

    private fun run() {
        if (validate() != null) return
        val job = StackJob(selection.usable, state.steps.toList(), state.keepInBetween)
        activity.ensureNotificationPermission {
            val wasBusy = ExportManager.state.value.busy
            ExportManager.enqueue(job)
            Toast.makeText(ctx, if (wasBusy) "Queued — it starts when the current export finishes." else "Stack started. You can leave the app; progress is shown in the notification.", Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    override fun onShow() { renderSteps(); panel?.refresh() }

    override fun onDestroy() {
        selection.unlisten(listener)
        panel?.detach()
        super.onDestroy()
    }
}

/** Chooses the tool for a new step; tools that can't use what arrives at this point are shown greyed out with the reason. */
class StackToolPicker(activity: MainActivity, private val stepNumber: Int, private val kinds: Set<FlowKind>, private val onPick: (ToolId) -> Unit) : Screen(activity) {
    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val scroll = ScrollView(ctx).apply { clipToPadding = false }
        val col = UI.vertical(ctx, 16, 0)
        col.setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(40))
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(TopBar(this, "Add step $stepNumber").apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })
        col.addView(UI.text(ctx, "This step gets ${FlowKind.describe(kinds)}. Pick a tool; you'll set its options next.", TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(4) })
        for (sec in ToolSection.entries) {
            val tools = ToolId.entries.filter { it.section == sec && StackRules.stackable(it) }
            if (tools.isEmpty()) continue
            col.addView(sectionHeader(ctx, sec.title, null, Palette.section(sec)), lp().apply { topMargin = ctx.dp(22); bottomMargin = ctx.dp(8) })
            for (t in tools) {
                val ok = StackRules.accepts(t).intersect(kinds).isNotEmpty()
                val row = listRow(ctx, Icons.tool(t), Palette.section(t.section), t.title,
                    if (ok) t.shortDescription else "Works on ${FlowKind.describe(StackRules.accepts(t))}, which this step doesn't get",
                    UI.iconView(ctx, R.drawable.ic_chevron, Palette.TEXT_3, 18)) {
                    if (ok) { pop(); onPick(t) }
                }
                row.alpha = if (ok) 1f else 0.45f
                row.contentDescription = "${t.title}${if (ok) "" else ", not available at this step"}"
                col.addView(row, lp().apply { topMargin = ctx.dp(8) })
            }
        }
        val other = ToolId.entries.filter { !StackRules.stackable(it) && it != ToolId.TOOL_STACK }
        col.addView(UI.note(ctx, "Not available in stacks: " + other.joinToString(", ") { it.title } + ". They work on one photo by hand, look through your library or use the camera.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(22) })
        return root
    }
}
