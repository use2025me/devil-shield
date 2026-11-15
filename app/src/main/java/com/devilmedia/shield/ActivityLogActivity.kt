package com.devilmedia.shield

import android.os.Bundle

/** What Devil Shield has blocked recently. Tap one to trust that site. */
class ActivityLogActivity : ShieldScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLog.load(this)
        setupScreen(null)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        heading("Activity")
        text("Devil Shield quietly blocked these. You don't need to do anything. This is just so you can see it working.")
        gap(6)

        val entries = ActivityLog.recent()
        if (entries.isEmpty()) {
            val c = card()
            text("Nothing blocked yet", 17f, Brand.TEXT, bold = true, parent = c)
            text("That's good news. Devil Shield is keeping watch.", parent = c)
            return
        }

        for (e in entries.take(150)) {
            val c = card { offerTrust(e.domain) }
            val what = if (e.family) "Blocked by the family filter" else "Blocked a known threat"
            text(what, 16f, if (e.family) Brand.MUTED else Brand.TEXT, bold = true, parent = c)
            text(e.domain, 15f, Brand.MUTED, parent = c)
            text("${e.app} · ${timeAgo(e.time)}", 13f, Brand.GREY, parent = c)
            text("Blocked by mistake? Tap here to trust it.", 13f, Brand.GREEN, parent = c)
        }
    }

    private fun offerTrust(domain: String) {
        dialog()
            .setTitle("Trust this site?")
            .setMessage("If you know and trust $domain, Devil Shield will stop blocking it.")
            .setPositiveButton("Trust it") { _, _ ->
                requirePin {
                    val p = Prefs(this)
                    p.trusted = p.trusted + domain
                    Blocklists.refreshSettings(this)
                    toast("$domain is now trusted.")
                }
            }
            .setNegativeButton("Keep blocking", null)
            .show()
    }
}
