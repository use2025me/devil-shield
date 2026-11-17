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
    private lateinit var vpnLine: TextView
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
        if (isTv) buildTv() else buildPhone()

        toggle.requestFocus()
        UpdateChecker(this).check(manual = false)

        val p = Prefs(this)
        if (!p.welcomed && !p.enabled) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        } else {
            handleStart(intent)
        }
    }

    // ---------------------------------------------------------------------------------
    // Phone layout: one calm status circle and a list of buttons.

    private fun buildPhone() {
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

        vpnLine = text("", 14f, Brand.MUTED, center = true)
        gap(4)
        for (t in tiles) button(t.title) { open(t.target) }
    }

    // ---------------------------------------------------------------------------------
    // TV layout: a status panel and a grid of big tiles, easy to use with a remote.

    private class Tile(val icon: String, val title: String, val target: Class<*>)

    private val tiles = listOf(
        Tile("🚫", "Scam protection", ScamHubActivity::class.java),
        Tile("🩺", "Health check", HealthActivity::class.java),
        Tile("🔒", "Your VPN", VpnActivity::class.java),
        Tile("📡", "Network scan", NetworkScanActivity::class.java),
        Tile("📵", "App controls", AppsActivity::class.java),
        Tile("📋", "Activity", ActivityLogActivity::class.java),
        Tile("🗑", "Remove my data", RemoveDataActivity::class.java),
        Tile("🤝", "Companion mode", CompanionActivity::class.java),
        Tile("✅", "Trusted sites", TrustedActivity::class.java),
        Tile("⚙", "Settings", SettingsActivity::class.java),
        Tile("ℹ", "What we protect you from", AboutActivity::class.java)
    )
    private val tileStatus = HashMap<String, TextView>()

    private val isTv: Boolean
        get() {
            val ui = getSystemService(android.app.UiModeManager::class.java)
            return ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    private fun buildTv() {
        setupScreen(null, wide = true)

        // Header
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        body.addView(header)
        val logo = android.widget.ImageView(this)
        logo.setImageResource(R.drawable.ic_shield_fg)
        header.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)))
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        header.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        text("Devil Shield", 28f, Brand.TEXT, bold = true, parent = names)
        text("by Devil Media Group", 14f, Brand.MUTED, parent = names)
        gap(12)

        // Status panel: ring on the left, words and the main button on the right
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.background = rounded(Brand.CARD, radius = 20)
        panel.setPadding(dp(24), dp(20), dp(24), dp(20))
        body.addView(panel)

        circle = TextView(this)
        circle.gravity = Gravity.CENTER
        circle.textSize = 18f
        circle.setTextColor(Brand.TEXT)
        panel.addView(circle, LinearLayout.LayoutParams(dp(150), dp(150)))

        val words = LinearLayout(this)
        words.orientation = LinearLayout.VERTICAL
        words.setPadding(dp(24), 0, 0, 0)
        panel.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        message = text("", 18f, Brand.TEXT, parent = words)
        stats = text("", 15f, Brand.MUTED, parent = words)
        vpnLine = text("", 15f, Brand.MUTED, parent = words)
        lists = text("", 13f, Brand.GREY, parent = words)
        toggle = button("", primary = true, parent = words) { onToggle() }
        gap(16)

        // Grid of tiles, 4 per row
        val perRow = 4
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, t ->
            if (i % perRow == 0) {
                row = LinearLayout(this).also {
                    it.orientation = LinearLayout.HORIZONTAL
                    body.addView(it)
                }
            }
            row!!.addView(tileView(t), LinearLayout.LayoutParams(0, dp(150), 1f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        }
        // Keep the last row's tiles the same size as the others
        val leftover = tiles.size % perRow
        if (leftover != 0) {
            repeat(perRow - leftover) {
                row!!.addView(android.view.View(this), LinearLayout.LayoutParams(0, dp(150), 1f).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                })
            }
        }
    }

    private fun tileView(t: Tile): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.gravity = Gravity.CENTER
        v.setPadding(dp(12), dp(12), dp(12), dp(12))
        v.background = focusBg(Brand.CARD, Brand.CARD_PRESSED)
        v.isFocusable = true
        v.isClickable = true
        v.setOnClickListener { open(t.target) }
        v.setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
        }
        val icon = TextView(this)
        icon.text = t.icon
        icon.textSize = 34f
        icon.gravity = Gravity.CENTER
        v.addView(icon)
        val title = TextView(this)
        title.text = t.title
        title.textSize = 16f
        title.setTextColor(Brand.TEXT)
        title.gravity = Gravity.CENTER
        title.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        v.addView(title)
        val status = TextView(this)
        status.textSize = 13f
        status.setTextColor(Brand.MUTED)
        status.gravity = Gravity.CENTER
        status.maxLines = 1
        status.ellipsize = android.text.TextUtils.TruncateAt.END
        v.addView(status)
        tileStatus[t.title] = status
        return v
    }

    /** Short status lines under each TV tile. */
    private fun refreshTiles(on: Boolean) {
        if (tileStatus.isEmpty()) return
        val p = Prefs(this)
        fun set(title: String, text: String, color: Int = Brand.MUTED) {
            tileStatus[title]?.let { it.text = text; it.setTextColor(color) }
        }
        set("Scam protection", if (p.blockLookalikes) "Fake sites blocked" else "Fake site blocking off",
            if (p.blockLookalikes) Brand.GREEN else Brand.AMBER)
        set("Health check", "Check this device")
        set("Your VPN", when {
            p.vpnConfig.isEmpty() -> "Not set up"
            !p.vpnOn -> "${p.vpnName}: off"
            on && WgTunnel.state == WgTunnel.State.CONNECTED -> "Connected ✓"
            on && WgTunnel.state == WgTunnel.State.FAILED -> "Can't connect"
            on -> "Connecting…"
            else -> "${p.vpnName}"
        }, if (on && p.vpnOn && WgTunnel.state == WgTunnel.State.CONNECTED) Brand.GREEN else Brand.MUTED)
        set("Network scan", "Cameras, router, devices")
        val cut = p.blockedApps.size
        set("App controls", if (cut == 0) "No apps cut off" else "$cut cut off")
        set("Activity", "Blocked today: ${numbers.format(ActivityLog.blockedToday)}")
        val requests = Removals.load(this)
        set("Remove my data", if (requests.isEmpty()) "No requests yet" else "${requests.size} requests")
        set("Companion mode", if (p.companion) "Check-ups on" else "For use with another VPN")
        val trusted = p.trusted.size
        set("Trusted sites", if (trusted == 0) "None" else "$trusted trusted")
        set("Settings", "Version ${BuildConfig.VERSION_NAME}")
        set("What we protect you from", "Plain-English guide")
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
                "You can still use the Health check below, set up \"Companion mode\" to keep blocking threats alongside it, or import your VPN under \"Your VPN\" to have both in one app."
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
        vpnLine.text = when {
            !p.vpnOn || p.vpnConfig.isEmpty() -> ""
            !on -> "VPN: ${p.vpnName} (connects when protection is on)"
            WgTunnel.state == WgTunnel.State.CONNECTED -> "VPN: ${p.vpnName} · Connected ✓"
            WgTunnel.state == WgTunnel.State.FAILED -> "VPN: ${p.vpnName} · Can't connect (tap Your VPN)"
            else -> "VPN: ${p.vpnName} · Connecting…"
        }
        vpnLine.setTextColor(
            if (on && WgTunnel.state == WgTunnel.State.CONNECTED) Brand.GREEN
            else if (on && WgTunnel.state == WgTunnel.State.FAILED) Brand.SOFT_RED
            else Brand.MUTED
        )
        lists.text = if (p.listsUpdatedAt == 0L)
            "Protection lists download when you turn protection on."
        else
            "Protection lists: ${numbers.format(p.listsCount)} known threats · updated ${timeAgo(p.listsUpdatedAt)}"
        refreshTiles(on)
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
