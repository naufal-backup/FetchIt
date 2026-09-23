package com.tharunbirla.fetchit.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Tema Terang / Gelap / Ikuti sistem, tersimpan di SharedPreferences. */
object ThemeHelper {
    private const val PREFS = "fetchit_settings"
    private const val KEY = "theme_mode"

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    fun getSaved(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, SYSTEM)

    /** Panggil di awal onCreate (sebelum super) agar tidak kedip. */
    fun applySaved(context: Context) {
        apply(getSaved(context))
    }

    fun save(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, mode).apply()
        apply(mode)
    }

    private fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
