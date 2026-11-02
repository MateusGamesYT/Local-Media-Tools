package com.localmediatools.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import com.localmediatools.app.R

/** How the app looks: like the phone (light or dark as the phone is set), or always light or dark. */
enum class Appearance { SYSTEM, LIGHT, DARK }

/**
 * Chooses the light or dark theme ([Palette.scheme], the window's XML theme and the system bars'
 * icons). Screens that show photos and videos full size stay dark in both ([Screen.alwaysDark]).
 */
object AppTheme {
    private const val KEY = "appearance"

    /** The theme the user's choice gives (media screens may show [Palette.DARK] meanwhile). */
    @Volatile var chosen: Scheme = Palette.DARK
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun appearance(ctx: Context): Appearance =
        runCatching { Appearance.valueOf(prefs(ctx).getString(KEY, null) ?: Appearance.SYSTEM.name) }.getOrDefault(Appearance.SYSTEM)

    fun setAppearance(ctx: Context, a: Appearance) { prefs(ctx).edit().putString(KEY, a.name).apply() }

    fun systemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun wantsDark(ctx: Context): Boolean = when (appearance(ctx)) {
        Appearance.SYSTEM -> systemDark(ctx)
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }

    /** Picks the theme for [activity]; call before its super.onCreate. */
    fun apply(activity: Activity) {
        val dark = wantsDark(activity)
        chosen = if (dark) Palette.DARK else Palette.LIGHT
        Palette.scheme = chosen
        activity.setTheme(if (dark) R.style.Theme_LocalMediaTools_Dark else R.style.Theme_LocalMediaTools_Light)
    }

    /** The colours [s] is drawn with. */
    fun schemeFor(s: Screen): Scheme = if (s.alwaysDark) Palette.DARK else chosen

    /** Makes [s]'s colours current and the system bars' icons readable on it. */
    fun enter(activity: Activity, s: Screen) {
        Palette.scheme = schemeFor(s)
        bars(activity, !Palette.isDark)
    }

    /** Dark status and navigation bar icons on light screens, light ones on dark screens. */
    fun bars(activity: Activity, lightBackground: Boolean) {
        val w = activity.window ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (lightBackground) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = (w.decorView.systemUiVisibility and light.inv()) or (if (lightBackground) light else 0)
        }
    }
}
