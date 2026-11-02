package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.ToolSection
import com.localmediatools.ui.AppTheme
import com.localmediatools.ui.Appearance
import com.localmediatools.ui.MainShell
import com.localmediatools.ui.Palette
import com.localmediatools.ui.ToolLauncher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The light and dark themes: every text colour readable on what it is drawn on (WCAG AA: 4.5:1, 3:1
 * for large text; icons 3:1), checked on the colours themselves and on the real screens (home, every
 * tool, the tabs, Settings), the theme following the Settings choice and the phone, and the screens
 * showing photos full size staying dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ThemeTest {
    private lateinit var app: Application

    @Before fun setUp() { app = RuntimeEnvironment.getApplication(); Robo.resetUiState() }
    @After fun tearDown() { AppTheme.setAppearance(app, Appearance.SYSTEM) }

    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @Test fun everyColourPairIsReadableInBothThemes() {
        for (s in listOf(Palette.DARK, Palette.LIGHT)) {
            val name = if (s.dark) "dark" else "light"
            val surfaces = listOf(s.bg, s.surface, s.surface2)
            for (t in listOf(s.text, s.text2, s.text3, s.accent, s.success, s.warning, s.danger)) for (b in surfaces)
                assertTrue("$name: %06X on %06X: %.2f".format(t and 0xFFFFFF, b and 0xFFFFFF, Palette.contrast(t, b)), Palette.contrast(t, b) >= 4.5)
            // Selected chips and the current tab: text and accent on the accent's soft background.
            for (t in listOf(s.text, s.accent)) assertTrue("$name: on accent container", Palette.contrast(t, s.accentContainer) >= 4.5)
            // Notes: secondary text on the note's tint over a card.
            for (k in listOf(s.accent, s.success, s.warning, s.danger)) {
                val bg = blend(Palette.withAlpha(k, 0x1A), s.surface)
                assertTrue("$name: note text on %06X".format(k and 0xFFFFFF), Palette.contrast(s.text2, bg) >= 4.5)
            }
            // Primary buttons: white text on both ends of the brand gradient.
            for (c in s.brand) assertTrue("$name: button text on %06X".format(c and 0xFFFFFF), Palette.contrast(s.onAccent, c) >= 4.5)
            // Section colours as icons and markers.
            for (c in s.sections) for (b in surfaces) assertTrue("$name: section %06X on %06X".format(c and 0xFFFFFF, b and 0xFFFFFF), Palette.contrast(c, b) >= 3.0)
        }
        // White glyphs on the tool tiles' gradients (the same in both themes).
        val gradients = ToolSection.entries.map { Palette.sectionGradient(it) } + listOf(Palette.AI, Palette.TEAL, Palette.SUNSET)
        for (g in gradients) for (c in g) assertTrue("white on %06X: %.2f".format(c and 0xFFFFFF, Palette.contrast(Color.WHITE, c)), Palette.contrast(Color.WHITE, c) >= 3.0)
    }

    @Test fun theSettingChoosesTheThemeAndMediaScreensStayDark() {
        AppTheme.setAppearance(app, Appearance.LIGHT)
        var a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertFalse(Palette.isDark); assertEquals(Palette.LIGHT.bg, Palette.BG)
        // The video editor shows videos: dark while it is open, light again after.
        a.navigator.push(com.localmediatools.ui.video.VideoEditorScreen(a)); idle(500)
        assertTrue(Palette.isDark)
        a.navigator.pop(); idle(500)
        assertFalse(Palette.isDark)
        AppTheme.setAppearance(app, Appearance.DARK)
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertTrue(Palette.isDark)
    }

    @Test @Config(qualifiers = "+night") fun likeThePhoneFollowsItsDarkSetting() {
        AppTheme.setAppearance(app, Appearance.SYSTEM)
        Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertTrue(Palette.isDark)
    }

    @Test fun likeThePhoneInLightMode() {
        Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertFalse(Palette.isDark)
    }

    @Test fun screensAreReadableInTheLightTheme() = checkScreens(Appearance.LIGHT)
    @Test fun screensAreReadableInTheDarkTheme() = checkScreens(Appearance.DARK)

    /** With the phone's text 30 % larger, no text that is limited to a few lines loses words without "…". */
    @Test fun largeTextIsNotCutOff() {
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
            val problems = ArrayList<String>()
            fun scan(where: String) {
                val root = a.window.decorView
                val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                for (t in all(a.navigator.top!!.view).filterIsInstance<TextView>()) {
                    if (t.visibility != View.VISIBLE || t.ellipsize != null || t.maxLines > 4 || t.text.isNullOrEmpty()) continue
                    val l = t.layout ?: continue
                    if (l.getLineEnd(l.lineCount - 1) < t.text.length) problems.add("$where: \"${t.text}\" shows ${l.lineCount} lines")
                }
            }
            scan("home")
            val shell = a.navigator.top as MainShell
            for (t in ToolId.entries) {
                if (t == ToolId.PHOTO_EDITOR || t == ToolId.MAGIC_ERASER || t == ToolId.BLUR_REDACT) continue
                ToolLauncher.open(a, t); idle(400)
                scan(t.name)
                while (a.navigator.depth > 1) a.navigator.pop()
                idle(400)
            }
            for (tab in listOf(MainShell.TAB_GALLERY, MainShell.TAB_ACTIVITY, MainShell.TAB_SETTINGS)) { shell.show(tab); idle(500); scan("tab $tab") }
            assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /** Home, every tool's screen (the photo editors open the system picker first), the tabs and Settings. */
    private fun checkScreens(appearance: Appearance) {
        AppTheme.setAppearance(app, appearance)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        val problems = ArrayList<String>()
        check(a, "home", problems)
        val shell = a.navigator.top as MainShell
        for (t in ToolId.entries) {
            if (t == ToolId.PHOTO_EDITOR || t == ToolId.MAGIC_ERASER || t == ToolId.BLUR_REDACT) continue
            ToolLauncher.open(a, t); idle(400)
            check(a, t.name, problems)
            while (a.navigator.depth > 1) a.navigator.pop()
            idle(400)
        }
        for (tab in listOf(MainShell.TAB_GALLERY, MainShell.TAB_ACTIVITY, MainShell.TAB_SETTINGS)) { shell.show(tab); idle(500); check(a, "tab $tab", problems) }
        assertTrue("${problems.size} texts hard to read in the ${appearance.name.lowercase()} theme:\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    private fun check(a: MainActivity, where: String, problems: MutableList<String>) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp)); bmp.recycle()
        val top = a.navigator.top?.view ?: return
        for (t in all(top).filterIsInstance<TextView>()) {
            if (!shown(t)) continue
            val empty = t.text.isNullOrBlank()
            if (empty && (t !is EditText || t.hint.isNullOrBlank())) continue
            val fg = if (empty) t.currentHintTextColor else t.currentTextColor
            val sp = t.textSize / a.resources.displayMetrics.scaledDensity
            val bold = (t.typeface?.weight ?: 400) >= 600
            val need = if (sp >= 24f || (sp >= 18.66f && bold)) 3.0 else 4.5
            for (bg in backgrounds(t, a)) {
                val c = Palette.contrast(blend(fg, bg), bg)
                if (c < need) problems.add("$where: \"${(if (empty) t.hint else t.text).toString().take(40)}\" %06X on %06X = %.2f (needs %.1f)".format(fg and 0xFFFFFF, bg and 0xFFFFFF, c, need))
            }
        }
    }

    /** Visible, enabled (WCAG leaves out disabled controls) and not faded. */
    private fun shown(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p.visibility != View.VISIBLE || p.alpha < 0.9f || !p.isEnabled) return false
            p = p.parent as? View
        }
        return v.width > 0 && v.height > 0
    }

    /** The colours behind [v]: its ancestors' backgrounds composed from the window down (each end of a gradient). */
    private fun backgrounds(v: View, a: MainActivity): List<Int> {
        val chain = ArrayList<View>()
        var p: View? = v
        while (p != null) { chain.add(p); p = p.parent as? View }
        var bgs = listOf(Palette.scheme.bg)
        for (x in chain.asReversed()) {
            val cs = colours(x.background) ?: continue
            bgs = cs.flatMap { c -> bgs.map { b -> blend(c, b) } }.distinct()
        }
        return bgs
    }

    private fun colours(d: Drawable?): List<Int>? = when (d) {
        null -> null
        is ColorDrawable -> listOf(d.color)
        is RippleDrawable -> if (d.numberOfLayers > 0) colours(d.getDrawable(0)) else null
        is GradientDrawable -> d.colors?.toList() ?: d.color?.defaultColor?.let { listOf(it) }
        else -> null
    }.let { list -> list?.filter { Color.alpha(it) > 0 }?.takeIf { it.isNotEmpty() } }

    private fun blend(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255f
        fun ch(s: Int) = (((fg shr s) and 255) * a + ((bg shr s) and 255) * (1 - a)).toInt().coerceIn(0, 255)
        return Color.rgb(ch(16), ch(8), ch(0))
    }
}
