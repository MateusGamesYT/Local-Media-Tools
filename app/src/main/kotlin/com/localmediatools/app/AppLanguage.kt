package com.localmediatools.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.Language
import java.io.IOException
import java.util.Locale

/** The language the app is shown in: chosen in Settings, or the phone's (see [Language.forLocales]). */
object AppLanguage {
    private const val PREFS = "settings"
    private const val KEY = "language"

    /** The language chosen in Settings, or null to follow the phone. */
    fun chosen(ctx: Context): Language? = Language.of(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** The phone's languages, most preferred first (not the app's own, which [wrap] may have changed). */
    fun phoneLocales(): List<Locale> {
        val list = Resources.getSystem().configuration.locales
        return (0 until list.size()).map { list[it] }
    }

    fun phoneLanguage(): Language = Language.forLocales(phoneLocales())

    /** Loads the language to use now (at start, and when the phone's language changes). */
    fun apply(ctx: Context) {
        val lang = chosen(ctx) ?: phoneLanguage()
        if (lang == I18n.language && (lang == Language.ENGLISH || I18n.catalog.size > 0)) return
        val assets = ctx.applicationContext.assets
        I18n.load(lang) { path ->
            try { assets.open(path).bufferedReader().use { it.readText() } } catch (_: IOException) { null }
        }
    }

    /** Saves the choice (null: the phone's language) and switches to it. */
    fun set(ctx: Context, lang: Language?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (lang == null) remove(KEY) else putString(KEY, lang.tag) }.commit()
        apply(ctx)
    }

    /**
     * [base] showing Android's own text (copy and paste menus, pickers) in the app's language too,
     * when it was chosen in Settings.
     */
    fun wrap(base: Context): Context {
        if (chosen(base) == null) return base
        apply(base)
        val config = Configuration(base.resources.configuration)
        config.setLocale(I18n.locale)
        return base.createConfigurationContext(config)
    }
}
