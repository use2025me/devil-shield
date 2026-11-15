package com.devilmedia.shield

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat

/** The main screen: one calm status, one button, and the other screens. */
class MainActivity : ShieldScreen() {

    private lateinit var circle: TextView
    private lateinit var message: TextView
    private lateinit var toggle: Button
    private lateinit var stats: TextView
    private lateinit var lists: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val numbers = NumberFormat.getIntegerInstance()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLog.load(this)
        setupScreen(null)

        text("Devil Shield", 30f, Brand.TEXT, bold = true, center = true)
        text("by Devil Media Group", 14f, Brand.MUTED, center = true)
        gap(18)

        circle = TextView(this)
        circle.gravity = Gravity.CENTER
        circle.textSize = 22f
        circle.setTextColor(Brand.TEXT)
        val size = dp(210)
        val lp = LinearLayout.LayoutParams(size, size)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(circle, lp)
        gap(14)

        message = text("", 16f, Brand.MUTED, center = true)
        toggle = button("", primary = true) { onToggle() }
        stats = text("", 15f, Brand.MUTED, center = true)
        lists = text("", 13f, Brand.GREY, center = true)
        gap(12)

        button("Health check") { startActivity(Intent(this, HealthActivity::class.java)) }
        button("Activity") { startActivity(Intent(this, ActivityLogActivity::class.java)) }
        button("Trusted sites") { startActivity(Intent(this, TrustedActivity::class.java)) }
        button("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        button("What Devil Shield protects you from") { startActivity(Intent(this, AboutActivity::class.java)) }

        toggle.requestFocus()
        UpdateChecker(this).check(manual = false)

        val p = Prefs(this)
        if (!p.welcomed && !p.enabled) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        } else {
            handleStart(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStart(intent)
    }

    /** Turns protection on when asked by the welcome screen or the quick switch. */
    private fun handleStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START, false) == true) {
            intent.removeExtra(EXTRA_START)
            if (!ShieldVpnService.running) onToggle()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val on = ShieldVpnService.running
        val ring = GradientDrawable()
        ring.shape = GradientDrawable.OVAL
        ring.setColor(Brand.CARD)
        ring.setStroke(dp(8), if (on) Brand.GREEN else Brand.GREY)
        circle.background = ring

        if (on) {
            circle.text = "You're\nprotected"
            message.text = if (Blocklists.security.isEmpty() && Blocklists.updating)
                "Getting the latest protection lists…"
            else
                "Devil Shield is quietly blocking known threats. Your apps keep working normally."
            toggle.text = "Turn protection off"
        } else if (otherVpnActive()) {
            circle.text = "Another VPN\nis running"
            message.text = "Another VPN app (like Surfshark) is on, and Android only allows one at a time. " +
                "You can still use the Health check below. Built-in VPN support is coming soon, so you can have both."
            toggle.text = "Use Devil Shield instead"
        } else {
            circle.text = "Protection\nis off"
            message.text = if (ShieldVpnService.revoked)
                "Another VPN app has taken over. Tap below to turn Devil Shield back on."
            else
                "Turn on Devil Shield to block malware, scams and spying."
            toggle.text = "Turn protection on"
        }

        stats.text = "Checked today: ${numbers.format(ActivityLog.checkedToday)}   ·   " +
            "Blocked today: ${numbers.format(ActivityLog.blockedToday)}"

        val p = Prefs(this)
        lists.text = if (p.listsUpdatedAt == 0L)
            "Protection lists download when you turn protection on."
        else
            "Protection lists: ${numbers.format(p.listsCount)} known threats · updated ${timeAgo(p.listsUpdatedAt)}"
    }

    private fun onToggle() {
        if (ShieldVpnService.running) {
            requirePin {
                ShieldVpnService.stop(this)
                handler.postDelayed({ refresh() }, 400)
            }
            return
        }
        if (otherVpnActive()) {
            dialog()
                .setTitle("Switch to Devil Shield?")
                .setMessage(
                    "Your other VPN app will be switched off while Devil Shield is on, because Android only " +
                        "allows one at a time. You can switch back whenever you like."
                )
                .setPositiveButton("Switch") { _, _ -> askPermissionAndStart() }
                .setNegativeButton("Keep my VPN", null)
                .show()
            return
        }
        askPermissionAndStart()
    }

    private fun askPermissionAndStart() {
        val permission = try {
            VpnService.prepare(this)
        } catch (_: Exception) {
            null
        }
        if (permission != null) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(permission, REQUEST_VPN)
            } catch (_: Exception) {
                toast("This device couldn't show the permission screen. Please try again.")
            }
        } else {
            turnOn()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) turnOn()
            else toast("Devil Shield needs that permission to protect this device.")
        }
    }

    private fun turnOn() {
        ShieldVpnService.start(this)
        handler.postDelayed({ refresh() }, 600)
    }

    companion object {
        private const val REQUEST_VPN = 1
        const val EXTRA_START = "start_protection"
    }
}
