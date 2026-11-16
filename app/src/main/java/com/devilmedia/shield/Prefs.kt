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

    /** The person's own VPN file (WireGuard). Kept only in this app's private storage. */
    var vpnConfig: String
        get() = p.getString("vpn_config", "") ?: ""
        set(v) = p.edit().putString("vpn_config", v).apply()

    var vpnName: String
        get() = p.getString("vpn_name", "") ?: ""
        set(v) = p.edit().putString("vpn_name", v).apply()

    /** True when the imported VPN should be used together with protection. */
    var vpnOn: Boolean
        get() = p.getBoolean("vpn_on", false)
        set(v) = p.edit().putBoolean("vpn_on", v).apply()

    /** Apps the person has cut off from the internet. */
    var blockedApps: Set<String>
        get() = p.getStringSet("blocked_apps", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("blocked_apps", HashSet(v)).apply()

    /** Switch the VPN on automatically on open (no password) Wi-Fi. On by default. */
    var autoVpn: Boolean
        get() = p.getBoolean("auto_vpn", true)
        set(v) = p.edit().putBoolean("auto_vpn", v).apply()

    /** True while the VPN is on only because of an open Wi-Fi (so it can be switched back off). */
    var autoVpnEngaged: Boolean
        get() = p.getBoolean("auto_vpn_engaged", false)
        set(v) = p.edit().putBoolean("auto_vpn_engaged", v).apply()

    /** Newly installed apps that asked for strong permissions, waiting for a look. */
    var newAppFlags: Set<String>
        get() = p.getStringSet("new_app_flags", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("new_app_flags", HashSet(v)).apply()
}
