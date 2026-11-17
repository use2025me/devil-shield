package com.devilmedia.shield

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Companion mode: protection that keeps working while another VPN (like Surfshark) is on.
 * Android only lets one app use the VPN slot, so instead of blocking through it, Devil Shield
 * (1) guides the person to Android's built-in Private DNS with a security lookup service that
 * blocks malware and scam sites, and (2) runs quiet background check-ups.
 */
object CompanionMode {
    /** Security lookup services with malware/scam blocking (Private DNS hostnames). */
    const val QUAD9 = "dns.quad9.net"
    const val CLOUDFLARE_SECURITY = "security.cloudflare-dns.com"

    fun privateDnsHost(ctx: Context): String? = try {
        if (Settings.Global.getString(ctx.contentResolver, "private_dns_mode") == "hostname")
            Settings.Global.getString(ctx.contentResolver, "private_dns_specifier")
        else null
    } catch (_: Exception) {
        null
    }

    /** True if Private DNS points at one of the security lookup services. */
    fun securityDnsOn(ctx: Context): Boolean {
        val h = privateDnsHost(ctx)?.lowercase() ?: return false
        return h == QUAD9 || h == CLOUDFLARE_SECURITY || h.endsWith(".quad9.net") && !h.startsWith("dns10") && !h.startsWith("dns12")
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs(ctx).companion = on
        if (on) GuardJob.schedule(ctx) else GuardJob.cancel(ctx)
    }
}

/** Background check-ups every few hours, with or without the VPN slot. */
class GuardJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                runChecks(applicationContext)
            } catch (_: Exception) {
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val ID = 4242

        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                val job = JobInfo.Builder(ID, ComponentName(ctx, GuardJob::class.java))
                    .setPeriodic(4L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .build()
                js.schedule(job)
            } catch (_: Exception) {
            }
        }

        fun cancel(ctx: Context) {
            try {
                ctx.getSystemService(JobScheduler::class.java)?.cancel(ID)
            } catch (_: Exception) {
            }
        }

        fun runChecks(ctx: Context) {
            Removals.remindIfDue(ctx)
            checkNewApps(ctx)
            checkWifi(ctx)
        }

        /** Looks at apps installed since the last check-up. */
        private fun checkNewApps(ctx: Context) {
            val sp = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)
            val seq = sp.getInt("seq", -1)
            val lastRun = sp.getLong("last_run", System.currentTimeMillis())
            val changed = ctx.packageManager.getChangedPackages(if (seq < 0) 0 else seq)
            if (changed != null && seq >= 0) {
                for (pkg in changed.packageNames) {
                    val firstInstall = try {
                        ctx.packageManager.getPackageInfo(pkg, 0).firstInstallTime
                    } catch (_: Exception) {
                        0L
                    }
                    if (firstInstall >= lastRun && pkg !in Prefs(ctx).newAppFlags) NewAppCheck.check(ctx, pkg)
                }
            }
            sp.edit()
                .putInt("seq", changed?.sequenceNumber ?: maxOf(seq, 0))
                .putLong("last_run", System.currentTimeMillis())
                .apply()
        }

        /** Gentle notice when on Wi-Fi with no password and no protection running. */
        private fun checkWifi(ctx: Context) {
            if (Build.VERSION.SDK_INT < 31 || ShieldVpnService.running) return
            try {
                val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val info = caps.transportInfo as? WifiInfo ?: continue
                    val sec = info.currentSecurityType
                    if (sec != WifiInfo.SECURITY_TYPE_OPEN && sec != WifiInfo.SECURITY_TYPE_WEP) continue
                    val sp = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)
                    val last = sp.getLong("wifi_notice", 0L)
                    if (System.currentTimeMillis() - last < 12L * 60 * 60 * 1000) return
                    sp.edit().putLong("wifi_notice", System.currentTimeMillis()).apply()
                    Notify.wifi(
                        ctx,
                        "This Wi-Fi has no password, so people nearby could see what you do. Make sure your VPN " +
                            "is on while you use it."
                    )
                    return
                }
            } catch (_: Exception) {
            }
        }
    }
}

/** "Companion mode" screen. */
class CompanionActivity : ShieldScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        heading("Companion mode")
        text(
            "Use your own VPN app (like Surfshark) and still have Devil Shield protecting you. Android only lets " +
                "one app use the VPN slot, so Companion mode works in two other ways:"
        )

        // Step 1: Private DNS
        val dns = card()
        text("1. Block dangerous sites with Private DNS", 18f, Brand.TEXT, bold = true, parent = dns)
        val host = CompanionMode.privateDnsHost(this)
        when {
            CompanionMode.securityDnsOn(this) ->
                text("Set up ✓  Private DNS is using $host, which blocks known malware and scam sites.", 15f, Brand.GREEN, parent = dns)
            host != null ->
                text("Private DNS is set to $host. To block dangerous sites, change it to the address below.", 15f, Brand.AMBER, parent = dns)
            else ->
                text("Not set up yet. It takes about a minute, and then works in the background with any VPN.", 15f, Brand.MUTED, parent = dns)
        }
        text(
            "a) Tap \"Copy the address\".\nb) Tap \"Open phone settings\", then find Private DNS (usually under " +
                "Network & internet, or Connections → More connection settings).\nc) Choose \"Private DNS provider " +
                "hostname\", paste the address, and tap Save.",
            14f, Brand.MUTED, parent = dns
        )
        text("Address: ${CompanionMode.QUAD9}  (Quad9, a non-profit security service)", 14f, Brand.TEXT, parent = dns)
        button("Copy the address", primary = true, parent = dns) {
            copy(CompanionMode.QUAD9)
            toast("Copied: ${CompanionMode.QUAD9}")
        }
        button("Open phone settings", parent = dns) { openPrivateDnsSettings() }
        button("Use Cloudflare's instead", parent = dns) {
            copy(CompanionMode.CLOUDFLARE_SECURITY)
            toast("Copied: ${CompanionMode.CLOUDFLARE_SECURITY}")
        }
        text(
            "Some VPN apps use their own lookups and ignore Private DNS. If yours does, switch on its own malware " +
                "blocker too (Surfshark calls it CleanWeb). When you go back to Devil Shield's full protection, " +
                "set Private DNS back to Automatic so Devil Shield's own lists are used.",
            12f, Brand.GREY, parent = dns
        )

        // Step 2: background check-ups
        val guard = card()
        text("2. Background check-ups", 18f, Brand.TEXT, bold = true, parent = guard)
        text(
            "Every few hours, Devil Shield quietly checks for risky new apps, Wi-Fi with no password, and " +
                "data-removal requests that need a nudge. No VPN slot needed.",
            14f, Brand.MUTED, parent = guard
        )
        val on = Prefs(this).companion
        text(if (on) "Background check-ups are on ✓" else "Background check-ups are off.", 15f, if (on) Brand.GREEN else Brand.MUTED, parent = guard)
        button(if (on) "Turn background check-ups off" else "Turn background check-ups on", primary = !on, parent = guard) {
            CompanionMode.setEnabled(this, !on)
            if (!on) Thread { GuardJob.runChecks(applicationContext) }.start()
            render()
        }

        // What you keep / lose
        val diff = card()
        text("Good to know", 18f, Brand.TEXT, bold = true, parent = diff)
        text(
            "Works in Companion mode: blocking known dangerous sites, health check, network scan, new-app checks, " +
                "open Wi-Fi notices, Remove my data.\n\nOnly with full protection (or your VPN imported under \"Your " +
                "VPN\"): the Activity log, Devil Shield's own lists, Trusted sites and Cut off this app.",
            14f, Brand.MUTED, parent = diff
        )
    }

    private fun openPrivateDnsSettings() {
        for (action in listOf("android.settings.PRIVATE_DNS_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: Exception) {
            }
        }
        toast("Open your phone's Settings app and search for \"Private DNS\".")
    }

    private fun copy(text: String) {
        try {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Devil Shield", text))
        } catch (_: Exception) {
        }
    }
}
