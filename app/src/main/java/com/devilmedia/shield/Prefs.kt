package com.devilmedia.shield

import android.content.Context
import android.content.SharedPreferences

/** Small saved settings. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("shield", Context.MODE_PRIVATE)

    /** True when the person wants protection on (used to restart it after a reboot). */
    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Optional filter for adult and gambling sites. Off unless the person turns it on. */
    var familyFilter: Boolean
        get() = p.getBoolean("family", false)
        set(v) = p.edit().putBoolean("family", v).apply()

    /** Family PIN. Empty means no PIN. */
    var pin: String
        get() = p.getString("pin", "") ?: ""
        set(v) = p.edit().putString("pin", v).apply()

    /** Sites the person trusts. These are never blocked. */
    var trusted: Set<String>
        get() = p.getStringSet("trusted", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("trusted", HashSet(v)).apply()

    var listsUpdatedAt: Long
        get() = p.getLong("lists_at", 0L)
        set(v) = p.edit().putLong("lists_at", v).apply()

    var listsCount: Int
        get() = p.getInt("lists_count", 0)
        set(v) = p.edit().putInt("lists_count", v).apply()

    /** True once the welcome screen has been seen. */
    var welcomed: Boolean
        get() = p.getBoolean("welcomed", false)
        set(v) = p.edit().putBoolean("welcomed", v).apply()

    /** Send allowed lookups encrypted (private lookups). On by default. */
    var encryptedDns: Boolean
        get() = p.getBoolean("encrypted_dns", true)
        set(v) = p.edit().putBoolean("encrypted_dns", v).apply()

    /** Short, calm weekly summary notice. On by default. */
    var weeklySummary: Boolean
        get() = p.getBoolean("weekly_summary", true)
        set(v) = p.edit().putBoolean("weekly_summary", v).apply()

    var summaryAt: Long
        get() = p.getLong("summary_at", 0L)
        set(v) = p.edit().putLong("summary_at", v).apply()

    var summaryBase: Int
        get() = p.getInt("summary_base", 0)
        set(v) = p.edit().putInt("summary_base", v).apply()

    /** Newly installed apps that asked for strong permissions, waiting for a look. */
    var newAppFlags: Set<String>
        get() = p.getStringSet("new_app_flags", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("new_app_flags", HashSet(v)).apply()
}
