package com.devilmedia.shield

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.concurrent.Executors

/** Checks the device and explains anything worth knowing, calmly. */
class HealthActivity : ShieldScreen() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLog.load(this)
        setupScreen("Health check")
        text("Checking this device…")
    }

    override fun onResume() {
        super.onResume()
        runCheck()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun runCheck() {
        worker.execute {
            val findings = try {
                HealthCheck.run(applicationContext)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) show(findings) }
        }
    }

    private fun show(findings: List<Finding>) {
        body.removeAllViews()
        heading("Health check")

        val actions = findings.count { it.level == Level.ACTION }
        val watches = findings.count { it.level == Level.WATCH }
        val summary = card()
        when {
            actions > 0 -> {
                text("A few things need your attention", 19f, Brand.AMBER, bold = true, parent = summary)
                text("Nothing here is an emergency. Work through them when you're ready.", parent = summary)
            }
            watches > 0 -> {
                text("Looking good", 19f, Brand.GREEN, bold = true, parent = summary)
                text("A couple of things are worth knowing about.", parent = summary)
            }
            else -> {
                text("All clear", 19f, Brand.GREEN, bold = true, parent = summary)
                text("Nothing to worry about on this device.", parent = summary)
            }
        }
        gap(6)

        for (f in findings) {
            val color = when (f.level) {
                Level.GOOD -> Brand.GREEN
                Level.WATCH -> Brand.AMBER
                Level.ACTION -> Brand.SOFT_RED
            }
            val c = card()
            val title = SpannableString("●  ${f.title}")
            title.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text(title, 17f, Brand.TEXT, bold = true, parent = c)
            text(f.detail, 15f, Brand.MUTED, parent = c)
            for (a in f.actions) button(a.label, parent = c) { a.perform(this) }
        }

        gap(8)
        button("Run the check again", primary = true) {
            body.removeAllViews()
            heading("Health check")
            text("Checking this device…")
            runCheck()
        }
    }
}
