package com.devilmedia.shield

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/** "Your VPN": import a WireGuard VPN file and use it together with protection. */
class VpnActivity : ShieldScreen() {

    private val handler = Handler(Looper.getMainLooper())
    private var status: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        body.removeAllViews()
        status = null
        heading("Your VPN")
        val p = Prefs(this)
        val cfg = if (p.vpnConfig.isNotEmpty()) WgConfig.parse(p.vpnConfig) else null

        if (cfg == null) {
            text(
                "Use your own VPN inside Devil Shield, so you get a private connection and protection " +
                    "together, with one switch. It works with any VPN that gives you a WireGuard file " +
                    "(ending in .conf), such as Surfshark, Proton VPN, Mullvad, Windscribe and IVPN."
            )
            gap(4)
            button("Import VPN file", primary = true) { pickFile() }
            button("Paste VPN settings") { pasteDialog() }
            gap(6)
            val help = card()
            text("Getting a Surfshark file", 17f, Brand.TEXT, bold = true, parent = help)
            text(
                "1. Log in at surfshark.com\n" +
                    "2. Go to VPN, then Manual setup, then Router, then WireGuard\n" +
                    "3. Generate a key pair and choose a location\n" +
                    "4. Download the file and import it here\n\n" +
                    "Keep the file private. It works like a password for your VPN account.",
                15f, Brand.MUTED, parent = help
            )
            return
        }

        val c = card()
        text(p.vpnName.ifEmpty { "My VPN" }, 19f, Brand.TEXT, bold = true, parent = c)
        text("Server: ${cfg.endpointHost}", 15f, Brand.MUTED, parent = c)
        status = text("", 15f, Brand.MUTED, parent = c)
        updateStatus()

        if (p.vpnOn) {
            button("Stop using this VPN", parent = c) {
                p.vpnOn = false
                ShieldVpnService.restartIfRunning(this)
                render()
            }
        } else {
            button("Use this VPN with Devil Shield", primary = true, parent = c) {
                p.vpnOn = true
                applyOrTurnOn()
                render()
            }
        }

        gap(4)
        text(
            "With the VPN on, Devil Shield still blocks known threats and keeps the Activity log, and your " +
                "whole connection goes privately through your VPN. Android's \"Block connections without VPN\" " +
                "option (in VPN settings, next to Devil Shield) adds a kill switch.",
            14f, Brand.GREY
        )
        button("Replace with another VPN file") { pickFile() }
        button("Remove this VPN") {
            dialog()
                .setTitle("Remove this VPN?")
                .setMessage("Devil Shield will keep protecting this device without the VPN.")
                .setPositiveButton("Remove") { _, _ ->
                    p.vpnConfig = ""
                    p.vpnName = ""
                    p.vpnOn = false
                    ShieldVpnService.restartIfRunning(this)
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateStatus() {
        val s = status ?: return
        val p = Prefs(this)
        val (msg, color) = when {
            !p.vpnOn -> Pair("Not in use.", Brand.MUTED)
            !ShieldVpnService.running -> Pair("Turn on protection on the main screen to connect.", Brand.AMBER)
            !WgTunnel.active -> Pair("Starting…", Brand.MUTED)
            WgTunnel.state == WgTunnel.State.CONNECTED -> Pair("Connected ✓", Brand.GREEN)
            WgTunnel.state == WgTunnel.State.FAILED -> Pair(WgTunnel.detail, Brand.SOFT_RED)
            else -> Pair("Connecting…", Brand.MUTED)
        }
        s.text = msg
        s.setTextColor(color)
    }

    private fun applyOrTurnOn() {
        if (ShieldVpnService.running) {
            ShieldVpnService.restartIfRunning(this)
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_START, true)
            )
        }
    }

    private fun pickFile() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        for (i in listOf(open, get)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQUEST_FILE)
                return
            } catch (_: Exception) {
            }
        }
        toast("This device can't open a file picker. Use \"Paste VPN settings\" instead.")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > 64 * 1024) null else String(bytes)
            }
        } catch (_: Exception) {
            null
        }
        if (text == null) {
            toast("Couldn't read that file.")
            return
        }
        save(text, displayName(uri))
    }

    private fun displayName(uri: Uri): String {
        val name: String? = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return name?.removeSuffix(".conf")?.removeSuffix(".txt")?.ifBlank { null } ?: "My VPN"
    }

    private fun pasteDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.minLines = 6
        input.gravity = Gravity.TOP or Gravity.START
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Brand.GREY)
        input.textSize = 13f
        input.hint = "[Interface]\nPrivateKey = …\n\n[Peer]\nPublicKey = …"
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input)
        dialog()
            .setTitle("Paste VPN settings")
            .setMessage("Open your VPN file, copy everything in it, and paste it here.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> save(input.text.toString(), "My VPN") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(text: String, name: String) {
        val cfg = WgConfig.parse(text)
        if (cfg == null) {
            dialog()
                .setTitle("That doesn't look right")
                .setMessage(
                    "Devil Shield needs a WireGuard VPN file. It usually ends in .conf and starts with " +
                        "[Interface]. Check you downloaded the WireGuard version from your VPN provider."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val p = Prefs(this)
        p.vpnConfig = text
        p.vpnName = name
        p.vpnOn = true
        toast("VPN saved. Connecting…")
        applyOrTurnOn()
        render()
    }

    companion object {
        private const val REQUEST_FILE = 3
    }
}
