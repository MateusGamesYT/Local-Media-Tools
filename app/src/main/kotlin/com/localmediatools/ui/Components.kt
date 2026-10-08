package com.localmediatools.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.R
import com.localmediatools.tools.ToolId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Rounded search box with a clear button. */
class SearchField(ctx: Context, hint: String, private val onChange: (String) -> Unit) : LinearLayout(ctx) {
    val edit = EditText(ctx)
    private val clear: ImageView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Shapes.rounded(ctx, Palette.SURFACE, 18f, Palette.STROKE_2)
        setPadding(ctx.dp(14), 0, ctx.dp(4), 0)
        minimumHeight = ctx.dp(52)
        addView(UI.iconView(ctx, R.drawable.ic_search, Palette.TEXT_3, 20))
        edit.apply {
            style(TextStyle.BODY)
            setHintTextColor(Palette.TEXT_3)
            this.hint = hint
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(8), ctx.dp(12))
            contentDescription = hint
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    clear.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
                    onChange(s?.toString() ?: "")
                }
            })
            setOnEditorActionListener { v, _, _ ->
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); true
            }
        }
        addView(edit, lp(0, WRAP, 1f))
        clear = UI.iconButton(ctx, R.drawable.ic_close, "Clear search", Palette.TEXT_2) { edit.setText("") }
        clear.visibility = GONE
        addView(clear)
    }
}

/** Single-choice chips in a horizontal scroller (categories, aspect ratios, adjustments). */
class ChipRow<T>(
    ctx: Context,
    private val options: List<T>,
    private val labelOf: (T) -> String,
    selected: T?,
    private val iconOf: ((T) -> Int?)? = null,
    private val markOf: ((T) -> Boolean)? = null,
    private val onSelect: (T) -> Unit,
) : HorizontalScrollView(ctx) {
    var selected: T? = selected; private set
    private val row = UI.horizontal(ctx)
    private val views = ArrayList<LinearLayout>()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        addView(row)
        for (o in options) {
            val chip = UI.horizontal(ctx).apply {
                setPadding(ctx.dp(14), 0, ctx.dp(16), 0)
                minimumHeight = ctx.dp(40)
                isClickable = true; isFocusable = true
                setOnClickListener { select(o, true) }
            }
            iconOf?.invoke(o)?.let { chip.addView(UI.iconView(ctx, it, Palette.TEXT_2, 18).apply { (layoutParams as LayoutParams).rightMargin = ctx.dp(7) }) }
            chip.addView(UI.text(ctx, labelOf(o), TextStyle.SUBTITLE).apply { textSize = 14f })
            chip.addView(View(ctx).apply { tag = "dot" }, LayoutParams(ctx.dp(6), ctx.dp(6)).apply { leftMargin = ctx.dp(6) })
            views.add(chip)
            row.addView(chip, LayoutParams(WRAP, ctx.dp(40)).apply { rightMargin = ctx.dp(8) })
        }
        refresh()
    }

    fun select(o: T, notify: Boolean) {
        selected = o
        refresh()
        if (notify) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onSelect(o) }
    }

    fun refresh() {
        for ((i, v) in views.withIndex()) {
            val o = options[i]
            val sel = o == selected
            v.background = if (sel) Shapes.clickable(context, Palette.ACCENT_DARK, 100f, Palette.ACCENT) else Shapes.clickable(context, Palette.SURFACE_2, 100f, Palette.STROKE_2)
            for (k in 0 until v.childCount) {
                when (val c = v.getChildAt(k)) {
                    is TextView -> c.setTextColor(if (sel) Palette.TEXT else Palette.TEXT_2)
                    is ImageView -> c.drawable?.setTint(if (sel) Palette.ACCENT else Palette.TEXT_2)
                }
            }
            val dot = v.findViewWithTag<View>("dot")
            val marked = markOf?.invoke(o) == true
            dot.visibility = if (marked) VISIBLE else GONE
            dot.background = Shapes.circle(Palette.ACCENT)
            v.contentDescription = labelOf(o) + (if (sel) ", selected" else "") + (if (marked) ", changed" else "")
        }
    }
}

/** Big gradient card for featured tools on the home screen. */
class FeatureCard(ctx: Context, t: ToolId, colors: IntArray, badge: String?, onClick: () -> Unit) : FrameLayout(ctx) {
    init {
        background = Shapes.clickableGradient(ctx, colors, 26f)
        isClickable = true; isFocusable = true
        contentDescription = "${t.title}. ${t.shortDescription}"
        setOnClickListener { onClick() }
        clipToOutline = true
        // Large faint glyph in the corner for depth.
        addView(ImageView(ctx).apply {
            setImageDrawable(ctx.icon(Icons.tool(t), Palette.withAlpha(Color.WHITE, 0x2A)))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(ctx.dp(110), ctx.dp(110), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -ctx.dp(18); bottomMargin = -ctx.dp(18) })
        val col = UI.vertical(ctx, 16, 16)
        val top = UI.horizontal(ctx)
        top.addView(FrameLayout(ctx).apply {
            background = Shapes.rounded(ctx, Palette.withAlpha(Color.WHITE, 0x33), 14f)
            addView(ImageView(ctx).apply { setImageDrawable(ctx.icon(Icons.tool(t), Color.WHITE)) }, LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        top.addView(UI.flex(ctx))
        if (badge != null) top.addView(UI.text(ctx, badge, TextStyle.LABEL, Color.WHITE).apply {
            background = Shapes.pill(ctx, Palette.withAlpha(Color.BLACK, 0x38))
            setPadding(ctx.dp(10), ctx.dp(5), ctx.dp(10), ctx.dp(5))
            letterSpacing = 0.06f
        })
        col.addView(top)
        col.addView(UI.flex(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        col.addView(UI.text(ctx, t.title, TextStyle.TITLE, Color.WHITE).apply { textSize = 18f })
        col.addView(UI.text(ctx, t.shortDescription, TextStyle.CAPTION, Palette.withAlpha(Color.WHITE, 0xD9)).apply { setPadding(0, ctx.dp(4), ctx.dp(8), 0) })
        addView(col, LayoutParams(MATCH, MATCH))
    }
}

/** Tool tile used in the home grid. */
class ToolTile(ctx: Context, t: ToolId, onClick: () -> Unit) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        background = Shapes.clickable(ctx, Palette.SURFACE, 22f, Palette.STROKE)
        minimumHeight = ctx.dp(132)
        addView(UI.toolTile(ctx, t, 40, 22))
        addView(UI.text(ctx, t.title, TextStyle.SUBTITLE).apply { setPadding(0, ctx.dp(12), 0, 0) })
        addView(UI.text(ctx, t.shortDescription, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(4), 0, 0); maxLines = 3 })
        isClickable = true; isFocusable = true
        contentDescription = "${t.title}. ${t.shortDescription}"
        setOnClickListener { onClick() }
    }
}

/** Section title with a coloured marker. */
fun sectionHeader(ctx: Context, title: String, subtitle: String?, color: Int): View = UI.horizontal(ctx).apply {
    addView(View(ctx).apply { background = Shapes.rounded(ctx, color, 3f) }, LinearLayout.LayoutParams(ctx.dp(4), ctx.dp(22)))
    addView(UI.vertical(ctx).apply {
        setPadding(ctx.dp(10), 0, 0, 0)
        addView(UI.text(ctx, title, TextStyle.TITLE).apply { isAccessibilityHeading = true })
        if (subtitle != null) addView(UI.text(ctx, subtitle, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(2), 0, 0) })
    })
}

/** Settings-style row: icon, title/subtitle and an optional trailing view. */
fun listRow(ctx: Context, icon: Int, color: Int, title: String, subtitle: String?, trailing: View? = null, onClick: (() -> Unit)? = null): LinearLayout =
    UI.horizontal(ctx).apply {
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        minimumHeight = ctx.dp(60)
        addView(UI.iconTile(ctx, icon, color, 38, 20))
        addView(UI.titled(ctx, title, subtitle).apply { setPadding(ctx.dp(12), 0, ctx.dp(8), 0) }, lp(0, WRAP, 1f))
        if (trailing != null) addView(trailing)
        else if (onClick != null) addView(UI.iconView(ctx, R.drawable.ic_chevron, Palette.TEXT_3, 18))
        if (onClick != null) {
            isClickable = true; isFocusable = true
            background = Shapes.clickable(ctx, 0, 16f)
            setOnClickListener { onClick() }
        }
    }

/** Floating bottom navigation (icons with labels; the selected one gets a soft accent pill). */
class BottomNav(ctx: Context, private val items: List<Triple<String, Int, String>>, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    private val cells = ArrayList<LinearLayout>()
    private val badges = ArrayList<View>()
    var selected = 0; private set

    init {
        orientation = HORIZONTAL
        background = Shapes.rounded(ctx, Palette.withAlpha(Palette.BG_2, 0xF2), 28f, Palette.STROKE_2)
        elevation = ctx.dp(12).toFloat()
        setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
        for ((i, it) in items.withIndex()) {
            val cell = UI.vertical(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(6), 0, ctx.dp(6))
                isClickable = true; isFocusable = true
                contentDescription = it.first
                setOnClickListener { _ -> select(i, true) }
            }
            val iconBox = FrameLayout(ctx)
            iconBox.addView(UI.iconView(ctx, it.second, Palette.TEXT_3, 22).apply { tag = "icon" }, FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
            val badge = View(ctx).apply { background = Shapes.circle(Palette.ACCENT); visibility = GONE }
            iconBox.addView(badge, FrameLayout.LayoutParams(ctx.dp(8), ctx.dp(8), Gravity.TOP or Gravity.END).apply { topMargin = ctx.dp(4); rightMargin = ctx.dp(14) })
            badges.add(badge)
            cell.addView(iconBox, LayoutParams(ctx.dp(64), ctx.dp(30)))
            cell.addView(UI.text(ctx, it.third, TextStyle.CAPTION).apply { tag = "label"; gravity = Gravity.CENTER; setPadding(0, ctx.dp(2), 0, 0); textSize = 11.5f })
            cells.add(cell)
            addView(cell, lp(0, WRAP, 1f))
        }
        refresh()
    }

    fun select(i: Int, notify: Boolean) {
        val changed = selected != i
        selected = i
        refresh()
        if (notify) { if (changed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onSelect(i) }
    }

    fun setBadge(i: Int, on: Boolean) { badges.getOrNull(i)?.visibility = if (on) VISIBLE else GONE }

    private fun refresh() {
        for ((i, c) in cells.withIndex()) {
            val sel = i == selected
            val box = c.getChildAt(0) as FrameLayout
            box.background = if (sel) Shapes.pill(context, Palette.ACCENT_DARK) else null
            (box.findViewWithTag<ImageView>("icon")).drawable.setTint(if (sel) Palette.ACCENT else Palette.TEXT_3)
            (c.findViewWithTag<TextView>("label")).apply {
                setTextColor(if (sel) Palette.TEXT else Palette.TEXT_3)
                typeface = typeface(if (sel) 650 else 500)
            }
            c.isSelected = sel
            c.contentDescription = items[i].first + if (sel) ", selected" else ""
        }
    }
}

/**
 * Slider centred on zero (−range..+range), drawn as a thin track with the filled part growing from
 * the middle. A tick marks zero, with a haptic bump when crossing it; double-tap resets.
 */
class BipolarSlider(ctx: Context, private val range: Float = 100f, private val onChange: (Float, Boolean) -> Unit) : View(ctx) {
    var value = 0f; private set
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE_3 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT_3 }
    private val r = RectF()
    private var lastTap = 0L
    var bipolar = true

    init {
        minimumHeight = ctx.dp(44)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun set(v: Float, notify: Boolean = false) {
        value = v.coerceIn(if (bipolar) -range else 0f, range)
        contentDescription = "Value ${value.roundToInt()}"
        invalidate()
        if (notify) onChange(value, true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(44))
    }

    private val pad get() = context.dp(14).toFloat()
    private fun xOf(v: Float): Float {
        val min = if (bipolar) -range else 0f
        return pad + (v - min) / (range - min) * (width - 2 * pad)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val th = context.dp(4).toFloat()
        r.set(pad, cy - th / 2, width - pad, cy + th / 2)
        canvas.drawRoundRect(r, th, th, track)
        val zero = xOf(0f); val x = xOf(value)
        r.set(minOf(zero, x), cy - th / 2, maxOf(zero, x), cy + th / 2)
        canvas.drawRoundRect(r, th, th, fill)
        if (bipolar) canvas.drawRect(zero - context.dp(1), cy - context.dp(7), zero + context.dp(1), cy + context.dp(7), tick)
        canvas.drawCircle(x, cy, context.dp(10).toFloat(), thumb)
        canvas.drawCircle(x, cy, context.dp(4).toFloat(), fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val now = System.currentTimeMillis()
                if (now - lastTap < 280) { set(0f); onChange(0f, true); lastTap = 0; return true }
                lastTap = now
                update(e.x, false)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { update(e.x, true); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    private fun update(x: Float, done: Boolean) {
        val min = if (bipolar) -range else 0f
        var v = min + ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * (range - min)
        if (bipolar && abs(v) < range * 0.03f) v = 0f
        val crossed = (value != 0f && v == 0f)
        value = v
        if (crossed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        contentDescription = "Value ${value.roundToInt()}"
        invalidate()
        onChange(value, done)
    }
}

/** Small "AI" style badge. */
fun badge(ctx: Context, text: String, colors: IntArray = Palette.AI): TextView = UI.text(ctx, text, TextStyle.LABEL, Color.WHITE).apply {
    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply { cornerRadius = ctx.dp(100).toFloat() }
    setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3))
    letterSpacing = 0.06f
}
