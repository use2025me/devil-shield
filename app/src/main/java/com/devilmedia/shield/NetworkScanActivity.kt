package com.devilmedia.shield

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import java.util.concurrent.Executors

/** "Network scan": checks the devices on your home Wi-Fi for common risks. */
class NetworkScanActivity : ShieldScreen() {

    private val worker = Executors.newSingleThreadExecutor()
    private var progress: TextView? = null
    @Volatile private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
        intro()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun intro() {
        body.removeAllViews()
        heading("Network scan")
        text(
            "Checks the devices on your Wi-Fi (cameras, TV boxes, your router and more) for common risks, " +
                "like doors hackers use being left open. It never tries to log in to anything."
        )
        text("Only scan networks you own, or have permission to check.", 13f, Brand.GREY)
        gap(4)
        button("Scan my network", primary = true) { startScan() }
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        body.removeAllViews()
        heading("Network scan")
        progress = text("Scanning… this takes up to a minute.")
        worker.execute {
            val result = try {
                NetworkScan.scan(applicationContext) { done, total ->
                    if (done % 8 == 0 || done == total) {
                        runOnUiThread { progress?.text = "Scanning… ${done * 100 / total}%" }
                    }
                }
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                scanning = false
                if (!isFinishing && !isDestroyed) show(result)
            }
        }
    }

    private fun show(result: NetworkScan.Result?) {
        body.removeAllViews()
        heading("Network scan")
        if (result == null) {
            text("Connect this device to your home Wi-Fi (or a wired connection), then try again.")
            button("Try again", primary = true) { startScan() }
            return
        }
        val actions = result.devices.sumOf { d -> d.notes.count { it.first == Level.ACTION } }
        val summary = card()
        if (actions > 0) {
            text("A few things need your attention", 19f, Brand.AMBER, bold = true, parent = summary)
            text("Nothing here is an emergency. The notes below say what to do.", parent = summary)
        } else {
            text("No open doors found", 19f, Brand.GREEN, bold = true, parent = summary)
            text("Have a look at the tips below for any cameras or your router.", parent = summary)
        }
        text("Found ${result.devices.size} devices on ${result.network}.", 13f, Brand.GREY, parent = summary)

        for (d in result.devices) {
            val c = card()
            val title = if (d.name.isNotEmpty()) "${d.kind}: ${d.name}" else d.kind
            text(title, 17f, Brand.TEXT, bold = true, parent = c)
            text(d.ip, 13f, Brand.GREY, parent = c)
            for ((level, label, advice) in d.notes) {
                val color = if (level == Level.ACTION) Brand.SOFT_RED else Brand.AMBER
                val line = SpannableString("●  $label")
                line.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text(line, 15f, Brand.TEXT, bold = true, parent = c)
                text(advice, 14f, Brand.MUTED, parent = c)
            }
        }
        text(
            "Devices that don't answer on common doors won't appear, so the list may not include everything.",
            13f, Brand.GREY
        )
        button("Scan again", primary = true) { startScan() }
    }
}
