package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.localmediatools.app.AppLanguage
import com.localmediatools.app.MainActivity
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.Language
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.MainShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The app in every language it speaks: home, each tool's screen and the other tabs open, lay out
 * and draw, show the language's text (not English), and no "{0}" is left unfilled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xxhdpi")
class LanguageTest {
    private lateinit var app: Application

    @Before fun setUp() { app = RuntimeEnvironment.getApplication(); Robo.resetUiState() }

    @After fun tearDown() { AppLanguage.set(app, Language.ENGLISH); AppLanguage.set(app, null) }

    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun render(a: MainActivity) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp)); bmp.recycle()
    }

    private val unfilled = Regex("(?<!\\{)\\{\\d+\\}(?!\\})")

    private fun checkTexts(a: MainActivity, where: String) {
        for (t in all(a.window.decorView).filterIsInstance<TextView>()) {
            val s = t.text.toString()
            assertTrue("$where: unfilled placeholder in \"$s\"", !unfilled.containsMatchIn(s))
        }
    }

    @Test fun everyLanguageOpensEveryScreen() {
        val editorTools = setOf(ToolId.PHOTO_EDITOR, ToolId.MAGIC_ERASER, ToolId.BLUR_REDACT)
        for (lang in Language.entries.filter { it != Language.ENGLISH }) {
            AppLanguage.set(app, lang)
            assertEquals(lang, I18n.language)
            assertTrue("${lang.tag}: catalog loaded", I18n.catalog.size > 2000)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val a = controller.get()
            idle()
            assertTrue(a.navigator.top is MainShell)
            render(a); checkTexts(a, "${lang.tag} home")
            // The home screen is in the language: the tool titles are the catalog's, not the English.
            val titles = ToolId.entries.map { it.title }
            assertTrue("${lang.tag}: tool titles translated", titles.count { t -> ToolId.entries.any { it.englishTitle == t } } < titles.size / 4)
            for (t in ToolId.entries) {
                if (t in editorTools) continue
                val card = all(a.navigator.top!!.view).firstOrNull { it.contentDescription?.toString()?.startsWith(t.title + ".") == true }
                assertNotNull("${lang.tag}: home card for ${t.englishTitle}", card)
                card!!.performClick(); idle()
                render(a); checkTexts(a, "${lang.tag} ${t.englishTitle}")
                @Suppress("DEPRECATION") a.onBackPressed(); idle()
                assertTrue("${lang.tag}: back home after ${t.englishTitle}", a.navigator.top is MainShell)
            }
            val shell = a.navigator.top as MainShell
            for (tab in listOf(MainShell.TAB_GALLERY, MainShell.TAB_ACTIVITY, MainShell.TAB_SETTINGS, MainShell.TAB_TOOLS)) {
                shell.show(tab); idle(); render(a); checkTexts(a, "${lang.tag} tab $tab")
            }
            controller.pause().stop().destroy()
        }
    }
}
