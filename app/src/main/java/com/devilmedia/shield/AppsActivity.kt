package com.devilmedia.shield

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import java.util.concurrent.Executors

/** "App controls": cut any app off from the internet with one tap. */
class AppsActivity : ShieldScreen() {

    private class AppRow(val pkg: String, val label: String, val system: Boolean)

    private val worker = Executors.newSingleThreadExecutor()
    private var showSystem = false
    private var apps: List<AppRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen("App controls")
        text("Loading apps…")
        load()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        worker.execute {
            val pm = packageManager
            val rows = try {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
                    .filter { it.packageName != packageName }
                    .filter { pm.checkPermission(Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
                    .map { AppRow(it.packageName, AppInfo.label(this, it.packageName), AppInfo.isSystem(this, it.packageName)) }
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    apps = rows
                    render()
                }
            }
        }
    }

    private fun render() {
        body.removeAllViews()
        heading("App controls")
        text(
            "Cut off any app you don't trust, and it can't reach the internet. Useful for hidden apps that " +
                "came with a box and can't be removed."
        )
        text(
            "With your VPN on (\"Your VPN\"), a cut-off app is blocked completely. Without it, Devil Shield stops " +
                "the app finding any servers, which stops almost all apps.",
            13f, Brand.GREY
        )
        button(if (showSystem) "Hide built-in apps" else "Show built-in apps too") {
            showSystem = !showSystem
            render()
        }

        val blocked = Prefs(this).blockedApps
        val list = apps
            .filter { showSystem || !it.system || it.pkg in blocked }
            .sortedWith(compareBy<AppRow>({ it.pkg !in blocked }, { it.label.lowercase() }))
        if (list.isEmpty()) {
            text("No apps to show.", 15f, Brand.GREY, center = true)
            return
        }
        for (a in list) {
            val cut = a.pkg in blocked
            val c = card()
            text(a.label, 17f, Brand.TEXT, bold = true, parent = c)
            text(
                (if (a.system) "Built-in app · " else "") + if (cut) "Internet: cut off" else "Internet: allowed",
                14f, if (cut) Brand.SOFT_RED else Brand.MUTED, parent = c
            )
            button(if (cut) "Allow internet again" else "Cut off this app", parent = c) {
                requirePin { setCut(a.pkg, !cut) }
            }
        }
    }

    private fun setCut(pkg: String, cut: Boolean) {
        val p = Prefs(this)
        p.blockedApps = if (cut) p.blockedApps + pkg else p.blockedApps - pkg
        Blocklists.refreshSettings(this)
        toast(if (cut) "${AppInfo.label(this, pkg)} is cut off." else "${AppInfo.label(this, pkg)} can use the internet again.")
        render()
    }
}
