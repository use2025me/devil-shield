package com.devilmedia.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Turns protection back on after the device restarts, if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Prefs(context).enabled) return
        if (VpnService.prepare(context) != null) return // permission was taken back
        try {
            ShieldVpnService.start(context)
        } catch (_: Exception) {
            // Some devices don't allow this; "Always-on VPN" in Settings covers it.
        }
    }
}
