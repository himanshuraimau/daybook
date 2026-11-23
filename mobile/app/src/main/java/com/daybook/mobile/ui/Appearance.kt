package com.daybook.mobile.ui

import android.content.Context
import android.content.res.Configuration

/** The owner's choice of light, dark or following the phone. Stored on the phone, applied to every screen. */
object Appearance {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    private const val PREFS = "appearance"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", SYSTEM).let { if (it == LIGHT || it == DARK) it!! else SYSTEM }

    fun set(context: Context, mode: String) {
        require(mode == SYSTEM || mode == LIGHT || mode == DARK)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", mode).apply()
    }

    /** Returns [base] with the chosen night mode forced; unchanged when following the phone. */
    fun wrap(base: Context): Context {
        val night = when (mode(base)) { LIGHT -> Configuration.UI_MODE_NIGHT_NO; DARK -> Configuration.UI_MODE_NIGHT_YES; else -> return base }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }
}
