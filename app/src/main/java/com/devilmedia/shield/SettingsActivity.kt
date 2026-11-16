package com.devilmedia.shield

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.concurrent.Executors

class SettingsActivity : ShieldScreen() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
        render()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun render() {
        body.removeAllViews()
        heading("Settings")
        val p = Prefs(this)

        // Family filter
        val fam = card()
        text("Family filter", 18f, Brand.TEXT, bold = true, parent = fam)
        text(
            "Optional. Also blocks adult and gambling sites on this device. Protection against malware, " +
                "scams and spying is always on either way.",
            parent = fam
        )
        if (p.familyFilter) {
            text("The family filter is on.", 15f, Brand.GREEN, parent = fam)
            button("Turn the family filter off", parent = fam) {
                requirePin {
                    p.familyFilter = false
                    Blocklists.refreshSettings(this)
                    render()
                }
            }
        } else {
            button("Turn the family filter on", parent = fam) { turnFamilyOn() }
        }

        // Private lookups
        val priv = card()
        text("Private lookups", 18f, Brand.TEXT, bold = true, parent = priv)
        text(
            "Encrypts the website lookups Devil Shield lets through, so your internet provider and people on " +
                "public Wi-Fi can't see which sites you visit. Uses Cloudflare's private lookup service, and " +
                "falls back to your network's normal one if needed.",
            parent = priv
        )
        if (p.encryptedDns) {
            text("Private lookups are on.", 15f, Brand.GREEN, parent = priv)
            button("Turn private lookups off", parent = priv) {
                p.encryptedDns = false
                render()
            }
        } else {
            button("Turn private lookups on", parent = priv) {
                p.encryptedDns = true
                render()
            }
        }

        // Auto-VPN on open Wi-Fi
        val wifi = card()
        text("Open Wi-Fi protection", 18f, Brand.TEXT, bold = true, parent = wifi)
        text(
            "On Wi-Fi with no password (cafés, hotels), Devil Shield lets you know, and switches your VPN on " +
                "automatically if you've added one under \"Your VPN\". Needs Android 12 or newer.",
            parent = wifi
        )
        button(if (p.autoVpn) "Auto-VPN on open Wi-Fi: on (tap to turn off)" else "Auto-VPN on open Wi-Fi: off (tap to turn on)", parent = wifi) {
            p.autoVpn = !p.autoVpn
            render()
        }

        // Notices
        val notices = card()
        text("Gentle notices", 18f, Brand.TEXT, bold = true, parent = notices)
        text(
            "Devil Shield lets you know if a newly installed app needs a look, and can send a short weekly summary.",
            parent = notices
        )
        if (!Notify.canNotify(this) && Build.VERSION.SDK_INT >= 33) {
            button("Allow notices", parent = notices) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        button(if (p.weeklySummary) "Weekly summary: on (tap to turn off)" else "Weekly summary: off (tap to turn on)", parent = notices) {
            p.weeklySummary = !p.weeklySummary
            render()
        }

        // Start automatically
        val auto = card()
        text("Start automatically", 18f, Brand.TEXT, bold = true, parent = auto)
        text(
            "Devil Shield tries to turn itself back on after a restart. For the most reliable protection, " +
                "open VPN settings, tap the gear next to Devil Shield and turn on \"Always-on VPN\".",
            parent = auto
        )
        button("Open VPN settings", parent = auto) { safeStart(Intent("android.net.vpn.SETTINGS")) }

        // Lists
        val lists = card()
        text("Protection lists", 18f, Brand.TEXT, bold = true, parent = lists)
        text(
            if (p.listsUpdatedAt == 0L) "Not downloaded yet."
            else "Updated ${timeAgo(p.listsUpdatedAt)}. They update by themselves every day.",
            parent = lists
        )
        if (Blocklists.lastReport.isNotEmpty()) {
            text("Last download:\n${Blocklists.lastReport}", 12f, Brand.GREY, parent = lists)
        }
        button("Update now", parent = lists) {
            toast("Updating protection lists. This can take up to a minute…")
            worker.execute {
                Blocklists.loadCached(applicationContext)
                val ok = Blocklists.updateIfStale(applicationContext, force = true)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    toast(if (ok) "Protection lists are up to date." else "Couldn't update right now. It'll try again later.")
                    render()
                }
            }
        }

        // App updates
        val app = card()
        text("Devil Shield ${BuildConfig.VERSION_NAME}", 18f, Brand.TEXT, bold = true, parent = app)
        button("Check for app updates", parent = app) { UpdateChecker(this).check(manual = true) }
    }

    private fun turnFamilyOn() {
        val p = Prefs(this)
        val enable = {
            p.familyFilter = true
            toast("Family filter is on. Getting its list…")
            render()
            worker.execute {
                Blocklists.refreshSettings(applicationContext)
                Blocklists.updateIfStale(applicationContext, force = Blocklists.family.isEmpty())
            }
        }
        if (p.pin.isNotEmpty()) {
            requirePin(enable)
            return
        }
        askText(
            "Choose a family PIN",
            "Pick at least 4 numbers. You'll need this PIN to turn the filter or protection off, or to trust a site.",
            "PIN", true
        ) { pin ->
            if (pin.length < 4) {
                toast("Please use at least 4 numbers.")
            } else {
                p.pin = pin
                enable()
            }
        }
    }
}
