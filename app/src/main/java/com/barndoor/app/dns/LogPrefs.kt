package com.barndoor.app.dns

import android.content.Context

/** Logging defaults to OFF — it's an opt-in diagnostic tool, not a background habit. */
class LogPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("barndoor_log_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("logging_enabled", false)
        set(value) = prefs.edit().putBoolean("logging_enabled", value).apply()
}
