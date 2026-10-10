package com.localmediatools.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.localmediatools.app.R
import kotlin.math.roundToInt

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val p = (layoutParams as? ViewGroup.MarginLayoutParams) ?: LinearLayout.LayoutParams(MATCH, WRAP)
    p.setMargins(dp(l), dp(t), dp(r), dp(b))
    layoutParams = p
    return this
}

object UI {
    fun text(ctx: Context, s: CharSequence, style: TextStyle = TextStyle.BODY, color: Int = style.color): TextView =
        TextView(ctx).apply { text = s; style(style, color) }

    fun vertical(ctx: Context, padH: Int = 0, padV: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(padH), ctx.dp(padV), ctx.dp(padH), ctx.dp(padV))
    }

    fun horizontal(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    fun card(ctx: Context, padDp: Int = 16, color: Int = Palette.SURFACE): LinearLayout = vertical(ctx, padDp, padDp).apply {
        background = Shapes.rounded(ctx, color, 22f, Palette.STROKE)
    }

    fun spacer(ctx: Context, hDp: Int = 0, wDp: Int = 0): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(if (wDp > 0) ctx.dp(wDp) else 0, if (hDp > 0) ctx.dp(hDp) else 0)
    }

    fun flex(ctx: Context): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Palette.STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, ctx.dp(1) / 2 + 1))
    }

    fun label(ctx: Context, s: String): TextView = text(ctx, s.uppercase(), TextStyle.LABEL)

    fun iconView(ctx: Context, res: Int, tint: Int, sizeDp: Int = 22): ImageView = ImageView(ctx).apply {
        setImageDrawable(ctx.icon(res, tint))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Squircle with a vivid gradient and a white glyph (tool tiles, headers). */
    fun gradientTile(ctx: Context, res: Int, colors: IntArray, sizeDp: Int = 44, iconDp: Int = 24): FrameLayout = FrameLayout(ctx).apply {
        background = Shapes.gradient(ctx, colors, sizeDp * 0.3f)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        elevation = ctx.dp(2).toFloat()
        addView(ImageView(ctx).apply {
            setImageDrawable(ctx.icon(res, Color.WHITE))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(ctx.dp(iconDp), ctx.dp(iconDp), Gravity.CENTER))
    }

    /** Tool icon in its section's gradient. */
    fun toolTile(ctx: Context, t: com.localmediatools.tools.ToolId, sizeDp: Int = 44, iconDp: Int = 24) =
        gradientTile(ctx, Icons.tool(t), Palette.sectionGradient(t.section), sizeDp, iconDp)

    /** Softly tinted square tile holding an icon (secondary contexts). */
    fun iconTile(ctx: Context, res: Int, accent: Int, sizeDp: Int = 44, iconDp: Int = 24): FrameLayout = FrameLayout(ctx).apply {
        background = Shapes.rounded(ctx, Palette.withAlpha(accent, 0x2A), (sizeDp * 0.3f))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        addView(ImageView(ctx).apply {
            setImageDrawable(ctx.icon(res, accent))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(ctx.dp(iconDp), ctx.dp(iconDp), Gravity.CENTER))
    }

    fun iconButton(ctx: Context, res: Int, description: String, tint: Int = Palette.TEXT, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageDrawable(ctx.icon(res, tint))
        contentDescription = description
        val p = ctx.dp(11)
        setPadding(p, p, p, p)
        background = Shapes.clickable(ctx, Palette.withAlpha(Palette.SURFACE_2, 0), 100f)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(46))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): ButtonView = ButtonView(ctx, label, ButtonView.Kind.PRIMARY, null, onClick)
    fun secondaryButton(ctx: Context, label: String, iconRes: Int? = null, onClick: () -> Unit) = ButtonView(ctx, label, ButtonView.Kind.SECONDARY, iconRes, onClick)
    fun ghostButton(ctx: Context, label: String, iconRes: Int? = null, onClick: () -> Unit) = ButtonView(ctx, label, ButtonView.Kind.GHOST, iconRes, onClick)

    enum class NoteKind(val color: Int, val icon: Int) {
        INFO(Palette.ACCENT, R.drawable.ic_info), WARN(Palette.WARNING, R.drawable.ic_warning),
        SUCCESS(Palette.SUCCESS, R.drawable.ic_check), PRIVACY(Palette.SUCCESS, R.drawable.ic_shield), TIP(0xFFA78BFA.toInt(), R.drawable.ic_sparkle),
    }

    fun note(ctx: Context, s: CharSequence, kind: NoteKind = NoteKind.INFO): LinearLayout = horizontal(ctx, Gravity.TOP).apply {
        background = Shapes.rounded(ctx, Palette.withAlpha(kind.color, 0x1A), 14f)
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        addView(iconView(ctx, kind.icon, kind.color, 18).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(1) })
        addView(text(ctx, s, TextStyle.CAPTION, Palette.TEXT_2).apply { setPadding(ctx.dp(10), 0, 0, 0) }, lp(0, WRAP, 1f))
    }

    /** "Title / subtitle" stack used inside rows. */
    fun titled(ctx: Context, title: String, subtitle: String?): LinearLayout = vertical(ctx).apply {
        addView(text(ctx, title, TextStyle.SUBTITLE))
        if (!subtitle.isNullOrBlank()) addView(text(ctx, subtitle, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(3), 0, 0) })
    }
}

/** Large, accessible button with clear enabled/disabled states. */
class ButtonView(ctx: Context, label: String, private val kind: Kind, iconRes: Int?, onClick: () -> Unit) : LinearLayout(ctx) {
    enum class Kind { PRIMARY, SECONDARY, GHOST }
    private val textView = UI.text(ctx, label, TextStyle.BUTTON)
    private val iconView = iconRes?.let { UI.iconView(ctx, it, Palette.TEXT, 20) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = ctx.dp(if (kind == Kind.PRIMARY) 54 else 46)
        setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(18), ctx.dp(10))
        iconView?.let { addView(it); addView(UI.spacer(ctx, wDp = 8)) }
        addView(textView)
        isClickable = true; isFocusable = true
        contentDescription = label
        setOnClickListener { if (isEnabled) onClick() }
        refresh()
    }

    var label: String
        get() = textView.text.toString()
        set(v) { textView.text = v; contentDescription = v }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        refresh()
    }

    private fun refresh() {
        val ctx = context
        when (kind) {
            Kind.PRIMARY -> {
                background = if (isEnabled) Shapes.clickableGradient(ctx, Palette.BRAND, 18f) else Shapes.rounded(ctx, Palette.SURFACE_3, 18f)
                textView.setTextColor(if (isEnabled) Palette.ON_ACCENT else Palette.TEXT_3)
                iconView?.drawable?.setTint(if (isEnabled) Palette.ON_ACCENT else Palette.TEXT_3)
            }
            Kind.SECONDARY -> {
                background = Shapes.clickable(ctx, Palette.SURFACE_2, 16f, Palette.STROKE_2)
                textView.setTextColor(if (isEnabled) Palette.TEXT else Palette.TEXT_3)
                iconView?.drawable?.setTint(if (isEnabled) Palette.TEXT else Palette.TEXT_3)
            }
            Kind.GHOST -> {
                background = Shapes.clickable(ctx, 0, 14f)
                textView.setTextColor(if (isEnabled) Palette.ACCENT else Palette.TEXT_3)
                iconView?.drawable?.setTint(if (isEnabled) Palette.ACCENT else Palette.TEXT_3)
            }
        }
        alpha = if (isEnabled || kind == Kind.PRIMARY) 1f else 0.6f
    }
}

/** Lays children out left to right, wrapping onto new lines. */
open class FlowLayout(ctx: Context, private val hGapDp: Int = 8, private val vGapDp: Int = 8) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        val hg = context.dp(hGapDp); val vg = context.dp(vGapDp)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vg; rowH = 0 }
            x += c.measuredWidth + hg
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        val hg = context.dp(hGapDp); val vg = context.dp(vGapDp)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vg; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hg
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** Single-choice pills, e.g. formats or presets. */
class ChoiceGroup<T>(
    ctx: Context,
    private val options: List<T>,
    private val labelOf: (T) -> String,
    selected: T?,
    private val badgeOf: ((T) -> String?)? = null,
    private val onChange: (T) -> Unit,
) : FlowLayout(ctx) {
    var selected: T? = selected
        private set
    private val views = ArrayList<TextView>()
    private val enabledMap = HashMap<Int, Boolean>()

    init {
        for ((i, o) in options.withIndex()) {
            val badge = badgeOf?.invoke(o)
            val tv = UI.text(ctx, if (badge != null) "${labelOf(o)}  ·  $badge" else labelOf(o), TextStyle.BODY).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dp(42)
                setPadding(ctx.dp(16), ctx.dp(9), ctx.dp(16), ctx.dp(9))
                isClickable = true; isFocusable = true
                setOnClickListener { if (enabledMap[i] != false) select(o, notify = true) }
            }
            views.add(tv)
            addView(tv)
        }
        refresh()
    }

    fun select(o: T, notify: Boolean) {
        selected = o
        refresh()
        if (notify) onChange(o)
    }

    /** Shows the labels again (for labels that depend on other settings). */
    fun relabel() {
        for ((i, tv) in views.withIndex()) { val b = badgeOf?.invoke(options[i]); tv.text = if (b != null) "${labelOf(options[i])}  ·  $b" else labelOf(options[i]) }
        refresh()
    }

    /** Label of the selected option (null when none is selected). */
    fun selectedLabel(): String? = selected?.takeIf { it in options }?.let(labelOf)

    fun setOptionEnabled(o: T, enabled: Boolean) {
        enabledMap[options.indexOf(o)] = enabled
        refresh()
    }

    private fun refresh() {
        for ((i, tv) in views.withIndex()) {
            val sel = options[i] == selected
            val en = enabledMap[i] != false
            tv.background = if (sel) Shapes.clickable(context, Palette.ACCENT_DARK, 100f, Palette.ACCENT) else Shapes.clickable(context, Palette.SURFACE_2, 100f, Palette.STROKE_2)
            tv.setTextColor(if (!en) Palette.TEXT_3 else if (sel) Palette.TEXT else Palette.TEXT_2)
            tv.alpha = if (en) 1f else 0.5f
            tv.isSelected = sel
            tv.contentDescription = labelOf(options[i]) + if (sel) ", selected" else ""
        }
    }
}

/** Slider with a synchronised numeric field and value range validation. */
class SliderField(
    ctx: Context,
    val title: String,
    private val min: Int,
    private val max: Int,
    initial: Int,
    val unit: String = "",
    subtitle: String? = null,
    private val onChange: (Int) -> Unit,
) : LinearLayout(ctx) {
    private val seek = SeekBar(ctx)
    private val field = EditText(ctx)
    private var updating = false
    var value: Int = initial.coerceIn(min, max); private set

    init {
        orientation = VERTICAL
        val row = UI.horizontal(ctx)
        row.addView(UI.titled(ctx, title, subtitle), lp(0, WRAP, 1f))
        field.apply {
            style(TextStyle.SUBTITLE)
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            background = Shapes.rounded(ctx, Palette.SURFACE_2, 12f, Palette.STROKE)
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            minWidth = ctx.dp(72)
            contentDescription = "$title value"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (updating) return
                    val v = s?.toString()?.toIntOrNull()
                    if (v == null || v < this@SliderField.min || v > this@SliderField.max) {
                        error = "${this@SliderField.min}–${this@SliderField.max}$unit"
                        return
                    }
                    error = null
                    setValue(v, fromField = true)
                }
            })
            setOnFocusChangeListener { _, has -> if (!has) { updating = true; setText(value.toString()); error = null; updating = false } }
        }
        row.addView(field, LinearLayout.LayoutParams(WRAP, WRAP))
        if (unit.isNotEmpty()) row.addView(UI.text(ctx, unit, TextStyle.BODY_2).apply { setPadding(ctx.dp(6), 0, 0, 0) })
        addView(row)
        seek.apply {
            this.max = this@SliderField.max - this@SliderField.min
            progress = value - this@SliderField.min
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
            thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.SURFACE_3)
            contentDescription = title
            minimumHeight = ctx.dp(44)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) setValue(p + this@SliderField.min, fromField = false) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        addView(seek, lp().apply { topMargin = ctx.dp(6) })
        val range = UI.horizontal(ctx)
        range.addView(UI.text(ctx, "$min$unit", TextStyle.CAPTION, Palette.TEXT_3), lp(0, WRAP, 1f))
        range.addView(UI.text(ctx, "$max$unit", TextStyle.CAPTION, Palette.TEXT_3))
        range.setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        addView(range)
    }

    fun setValue(v: Int, fromField: Boolean) {
        val c = v.coerceIn(min, max)
        value = c
        updating = true
        if (!fromField) field.setText(c.toString())
        seek.progress = c - min
        updating = false
        onChange(c)
    }
}

/** Title + description + switch; the whole row toggles. */
class ToggleRow(ctx: Context, val title: String, subtitle: String?, checked: Boolean, private val onChange: (Boolean) -> Unit) : LinearLayout(ctx) {
    val switch = Switch(ctx)
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(56)
        addView(UI.titled(ctx, title, subtitle), lp(0, WRAP, 1f))
        switch.isChecked = checked
        switch.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.ACCENT, Palette.TEXT_2))
        switch.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.withAlpha(Palette.ACCENT, 0x66), Palette.SURFACE_3))
        switch.contentDescription = title
        switch.setOnCheckedChangeListener { _, c -> onChange(c) }
        addView(switch)
        isClickable = true
        background = Shapes.clickable(ctx, 0, 12f)
        setOnClickListener { if (switch.isEnabled) switch.toggle() }
    }

    fun setAvailable(enabled: Boolean) {
        switch.isEnabled = enabled
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        if (!enabled) switch.isChecked = false
    }
}

/** Labelled single-line text input. */
class TextField(ctx: Context, val title: String, hint: String, initial: String, numeric: Boolean = false, private val onChange: (String) -> Unit) : LinearLayout(ctx) {
    val edit = EditText(ctx)
    init {
        orientation = VERTICAL
        addView(UI.text(ctx, title, TextStyle.SUBTITLE))
        edit.apply {
            style(TextStyle.BODY)
            setHintTextColor(Palette.TEXT_3)
            this.hint = hint
            setText(initial)
            isSingleLine = true
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = Shapes.rounded(ctx, Palette.SURFACE_2, 12f, Palette.STROKE)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            contentDescription = title
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
            })
        }
        addView(edit, lp().apply { topMargin = ctx.dp(8) })
    }
}

/** Animated rounded progress bar. */
class ProgressBarView(ctx: Context, private val color: Int = Palette.ACCENT) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Palette.SURFACE_3 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var gradientFor = -1

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    /** The default accent progress uses the brand gradient; status colours stay solid. */
    private fun updateShader() {
        fill.shader = if (fill.color == Palette.ACCENT && width > 0) android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
            Palette.BRAND[0], Palette.BRAND[1], android.graphics.Shader.TileMode.CLAMP) else null
        gradientFor = fill.color
    }
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val rect = RectF()
    var indeterminate = false
        set(v) { field = v; invalidate() }
    private var phase = 0f

    init {
        minimumHeight = ctx.dp(8)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setProgress(f: Float, color: Int = this.fill.color) {
        fill.color = color
        if (gradientFor != color) updateShader()
        val target = f.coerceIn(0f, 1f)
        contentDescription = "${(target * 100).roundToInt()} percent"
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 250
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(8))
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        if (indeterminate) {
            phase = (phase + 0.012f) % 1f
            val w = width * 0.3f
            val x = -w + (width + w) * phase
            rect.set(x.coerceAtLeast(0f), 0f, (x + w).coerceAtMost(width.toFloat()), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
            postInvalidateOnAnimation()
        } else if (shown > 0f) {
            rect.set(0f, 0f, (width * shown).coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}

/** 3×3 placement picker; draws a mini canvas with the chosen anchor highlighted. */
class PositionGrid(ctx: Context, selected: com.localmediatools.image.WatermarkPosition, private val onSelect: (com.localmediatools.image.WatermarkPosition) -> Unit) : LinearLayout(ctx) {
    var selected = selected; private set
    private val cells = ArrayList<Pair<com.localmediatools.image.WatermarkPosition, View>>()

    init {
        orientation = VERTICAL
        background = Shapes.rounded(ctx, Palette.SURFACE_2, 16f, Palette.STROKE)
        setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        for (r in 0 until 3) {
            val row = UI.horizontal(ctx)
            for (c in 0 until 3) {
                val pos = com.localmediatools.image.WatermarkPosition.at(r, c)
                val cell = FrameLayout(ctx).apply {
                    isClickable = true; isFocusable = true
                    contentDescription = pos.label
                    setOnClickListener { select(pos) }
                    addView(View(ctx).apply { tag = "dot" }, FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.CENTER))
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ctx.dp(52), 1f).apply { setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3)) })
                cells.add(pos to cell)
            }
            addView(row, lp())
        }
        refresh()
    }

    fun select(p: com.localmediatools.image.WatermarkPosition, notify: Boolean = true) {
        selected = p
        refresh()
        if (notify) onSelect(p)
    }

    private fun refresh() {
        for ((p, v) in cells) {
            val sel = p == selected
            v.background = Shapes.clickable(context, if (sel) Palette.ACCENT_DARK else Palette.SURFACE, 10f, if (sel) Palette.ACCENT else null)
            (v as FrameLayout).findViewWithTag<View>("dot").background = Shapes.circle(if (sel) Palette.ACCENT else Palette.TEXT_3)
            v.contentDescription = p.label + if (sel) ", selected" else ""
        }
    }
}
