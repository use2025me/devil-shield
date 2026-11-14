package com.devilmedia.shield

import android.os.Bundle

/** Sites the person trusts. Devil Shield never blocks these. */
class TrustedActivity : ShieldScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
        render()
    }

    private fun render() {
        body.removeAllViews()
        heading("Trusted sites")
        text("Devil Shield only blocks known threats, so you'll rarely need this. If something you rely on is ever blocked by mistake, add it here and it won't be touched again.")
        gap(4)

        button("Add a site", primary = true) {
            askText("Add a trusted site", "Type the site's address, for example example.com", "example.com", false) { raw ->
                val d = Domains.clean(raw.removePrefix("https://").removePrefix("http://").substringBefore('/'))
                if (d == null) {
                    toast("That doesn't look like a site address.")
                } else {
                    requirePin {
                        val p = Prefs(this)
                        p.trusted = p.trusted + d
                        Blocklists.refreshSettings(this)
                        render()
                    }
                }
            }
        }

        val trusted = Prefs(this).trusted.sorted()
        if (trusted.isEmpty()) {
            text("No trusted sites yet.", 15f, Brand.GREY, center = true)
            return
        }
        for (d in trusted) {
            val c = card()
            text(d, 16f, Brand.TEXT, bold = true, parent = c)
            button("Stop trusting", parent = c) {
                requirePin {
                    val p = Prefs(this)
                    p.trusted = p.trusted - d
                    Blocklists.refreshSettings(this)
                    render()
                }
            }
        }
    }
}
