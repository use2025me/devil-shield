package com.devilmedia.shield

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** First-time welcome: three simple steps, then one button. */
class WelcomeActivity : ShieldScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
        gap(12)
        text("Welcome to Devil Shield", 28f, Brand.TEXT, bold = true, center = true)
        text("Calm, simple protection against malware, scams and spying.", 16f, Brand.MUTED, center = true)
        gap(12)

        step(
            "1.  Turn on protection",
            "Tap the button below, then tap OK when Android asks. That lets Devil Shield check connections on this device."
        )
        step(
            "2.  Let it work quietly",
            "A small key icon at the top of your screen means you're protected. Your apps keep working normally."
        )
        step(
            "3.  Keep it on",
            "For the best protection, turn on \"Always-on VPN\" for Devil Shield. You'll find a shortcut in Settings."
        )

        if (Build.VERSION.SDK_INT >= 33 && !Notify.canNotify(this)) {
            val c = card()
            text("Gentle notices (optional)", 17f, Brand.TEXT, bold = true, parent = c)
            text(
                "Devil Shield can let you know if a new app needs a look, and send a short weekly summary. Nothing scary.",
                15f, Brand.MUTED, parent = c
            )
            button("Allow notices", parent = c) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }

        gap(8)
        val go = button("Turn on protection", primary = true) { finishWelcome(start = true) }
        button("Not now") { finishWelcome(start = false) }
        go.requestFocus()
    }

    private fun step(title: String, detail: String) {
        val c = card()
        text(title, 17f, Brand.TEXT, bold = true, parent = c)
        text(detail, 15f, Brand.MUTED, parent = c)
    }

    private fun finishWelcome(start: Boolean) {
        Prefs(this).welcomed = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_START, start)
        )
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWelcome(start = false)
    }
}
