package com.devilmedia.shield

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build

/**
 * Notices when the device joins an open (no password) Wi-Fi, like many cafés and hotels.
 * If a VPN has been imported, it switches the VPN on automatically, and back off when
 * leaving that Wi-Fi. Needs Android 12 or newer to tell what kind of Wi-Fi it is.
 */
object WifiSafety {

    /** True while connected to an open Wi-Fi. */
    @Volatile var openWifi = false
        private set
    @Volatile private var notifiedFor: String? = null

    fun watch(ctx: Context): ConnectivityManager.NetworkCallback? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val app = ctx.applicationContext
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    onWifi(app, network.toString(), caps)
                }

                override fun onLost(network: Network) {
                    openWifi = false
                    notifiedFor = null
                    disengage(app)
                }
            }
            cm.registerNetworkCallback(request, cb)
            cb
        } catch (_: Exception) {
            null
        }
    }

    fun unwatch(ctx: Context, cb: ConnectivityManager.NetworkCallback?) {
        if (cb == null) return
        try {
            ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun onWifi(ctx: Context, id: String, caps: NetworkCapabilities) {
        if (Build.VERSION.SDK_INT < 31) return
        val info = caps.transportInfo as? WifiInfo ?: return
        val security = info.currentSecurityType
        if (security == WifiInfo.SECURITY_TYPE_UNKNOWN) return
        val open = security == WifiInfo.SECURITY_TYPE_OPEN || security == WifiInfo.SECURITY_TYPE_WEP
        openWifi = open
        if (!open) {
            disengage(ctx)
            return
        }
        val p = Prefs(ctx)
        val hasVpn = p.vpnConfig.isNotEmpty()
        if (hasVpn && p.autoVpn && !p.vpnOn) {
            p.vpnOn = true
            p.autoVpnEngaged = true
            notifiedFor = id
            Notify.wifi(ctx, "This Wi-Fi has no password, so Devil Shield switched your VPN on to keep you private. It'll switch off again when you leave.")
            ShieldVpnService.restartIfRunning(ctx)
        } else if (notifiedFor != id && !p.vpnOn) {
            notifiedFor = id
            Notify.wifi(
                ctx,
                "This Wi-Fi has no password, so people nearby could see what you do. Devil Shield is still " +
                    "blocking threats. For full privacy, add your VPN under \"Your VPN\" and it'll switch on " +
                    "automatically on Wi-Fi like this."
            )
        }
    }

    private fun disengage(ctx: Context) {
        val p = Prefs(ctx)
        if (!p.autoVpnEngaged) return
        p.autoVpnEngaged = false
        p.vpnOn = false
        ShieldVpnService.restartIfRunning(ctx)
    }
}
