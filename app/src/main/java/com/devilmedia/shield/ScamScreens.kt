package com.devilmedia.shield

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** "Scam protection": the hub for everything that helps with scam texts, calls and emails. */
class ScamHubActivity : ShieldScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen(null)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        heading("Scam protection")
        text(
            "Fake delivery texts, bank messages and calls are the most common scams in Ireland right now. " +
                "These tools help you spot them and stay safe."
        )

        button("Check a message or link", primary = true) { startActivity(Intent(this, ScamCheckActivity::class.java)) }

        val auto = card()
        text("Automatic scam warnings", 18f, Brand.TEXT, bold = true, parent = auto)
        text(
            "Devil Shield can look at new texts and messages as they arrive and warn you straight away if one looks " +
                "like a scam, like a fake DPD \"pay a delivery fee\" text. Messages are checked on this phone only " +
                "and never stored or sent anywhere.",
            14f, Brand.MUTED, parent = auto
        )
        val on = ScamWatchService.enabled(this)
        text(if (on) "Automatic warnings are on ✓" else "Automatic warnings are off.", 15f, if (on) Brand.GREEN else Brand.MUTED, parent = auto)
        button(if (on) "Turn automatic warnings off" else "Turn on automatic warnings", primary = !on, parent = auto) {
            dialog()
                .setTitle(if (on) "Turn off warnings" else "Allow notification access")
                .setMessage(
                    if (on) "On the next screen, switch Devil Shield off."
                    else "On the next screen, find Devil Shield and switch it on. This lets it read new message " +
                        "notifications to check them for scams. Nothing is saved or shared."
                )
                .setPositiveButton("Continue") { _, _ ->
                    Prefs(this).scamWatch = !on
                    safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val fake = card()
        text("Block fake company websites", 18f, Brand.TEXT, bold = true, parent = fake)
        text(
            "Blocks sites pretending to be DPD, An Post, Evri, banks, Revolut, PayPal and others (like " +
                "\"dpd-redelivery-ie.com\"), even before they're on any list. Works while protection is on.",
            14f, Brand.MUTED, parent = fake
        )
        val p = Prefs(this)
        button(if (p.blockLookalikes) "Fake site blocking: on (tap to turn off)" else "Fake site blocking: off (tap to turn on)", parent = fake) {
            p.blockLookalikes = !p.blockLookalikes
            Blocklists.refreshSettings(this)
            render()
        }

        button("Account safety check") { startActivity(Intent(this, AccountSafetyActivity::class.java)) }
        button("Scam calls and family safe word") { startActivity(Intent(this, CallSafetyActivity::class.java)) }

        val official = card()
        text("Official help in Ireland", 18f, Brand.TEXT, bold = true, parent = official)
        text("Latest scam warnings from the National Cyber Security Centre, and a scam checker from the banks' FraudSMART.", 14f, Brand.MUTED, parent = official)
        button("NCSC Ireland warnings", parent = official) { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ncsc.gov.ie/"))) }
        button("FraudSMART scam checker", parent = official) { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://fraudsmart.scamchecker.ie/"))) }
        text("Lost money? Call your bank straight away, then report it to your local Garda station.", 13f, Brand.GREY, parent = official)
    }
}

/** "Check a message or link": paste, share or select text, and get a calm verdict. */
class ScamCheckActivity : ShieldScreen() {

    private lateinit var input: EditText
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Blocklists.loadCachedAsync(applicationContext)
        setupScreen("Check a message")
        text("Paste a text, WhatsApp message, email or link. You can also share a message straight to Devil Shield, or select text and choose \"Check for scams\".")

        input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.minLines = 4
        input.gravity = Gravity.TOP or Gravity.START
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Brand.GREY)
        input.hint = "Paste the message here"
        input.background = rounded(Brand.CARD)
        input.setPadding(dp(14), dp(12), dp(14), dp(12))
        body.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        button("Check it", primary = true) { runCheck() }

        results = LinearLayout(this)
        results.orientation = LinearLayout.VERTICAL
        body.addView(results)

        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val shared = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> i?.getStringExtra(EXTRA_TEXT)
        }
        if (!shared.isNullOrBlank()) {
            input.setText(shared)
            runCheck()
        }
    }

    private fun runCheck() {
        val text = input.text.toString()
        results.removeAllViews()
        if (text.isBlank()) {
            toast("Paste a message first.")
            return
        }
        val r = ScamCheck.check(text)
        val c = card(results)
        when (r.verdict) {
            ScamCheck.Verdict.LIKELY_SCAM -> {
                text("This looks like a scam", 20f, Brand.SOFT_RED, bold = true, parent = c)
                text("Don't tap any links or reply.", 15f, Brand.TEXT, parent = c)
            }
            ScamCheck.Verdict.CAREFUL -> {
                text("Be careful with this one", 20f, Brand.AMBER, bold = true, parent = c)
                text("It has some signs of a scam.", 15f, Brand.TEXT, parent = c)
            }
            ScamCheck.Verdict.NOTHING_FOUND -> {
                text("No scam signs found", 20f, Brand.GREEN, bold = true, parent = c)
                text(
                    "Devil Shield didn't find anything it knows about. That doesn't guarantee it's safe, so if a " +
                        "message asks for money, codes or card details, check with the company directly.",
                    15f, Brand.MUTED, parent = c
                )
            }
        }
        if (r.reasons.isNotEmpty()) {
            text("Why", 16f, Brand.TEXT, bold = true, parent = c)
            for (reason in r.reasons) text("•  $reason", 14f, Brand.MUTED, parent = c)
        }
        if (r.advice.isNotEmpty()) {
            text("What to do", 16f, Brand.TEXT, bold = true, parent = c)
            for (a in r.advice) text("•  $a", 14f, Brand.MUTED, parent = c)
        }
    }

    companion object {
        const val EXTRA_TEXT = "scam_text"
    }
}

/**
 * Automatic scam warnings: looks at new message notifications (texts, WhatsApp and similar),
 * checks them on the phone, and shows a calm warning if one looks like a scam.
 * Nothing is stored or sent anywhere.
 */
class ScamWatchService : NotificationListenerService() {

    private val seen = LinkedHashSet<Int>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (!Prefs(this).scamWatch) return
            if (sbn.packageName == packageName || !isMessagingApp(sbn.packageName)) return
            val extras = sbn.notification.extras ?: return
            val text = listOfNotNull(
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ).joinToString("\n").trim()
            if (text.length < 8) return
            val key = text.hashCode()
            synchronized(seen) {
                if (!seen.add(key)) return
                while (seen.size > 200) seen.remove(seen.first())
            }
            Blocklists.loadCachedAsync(applicationContext)
            val r = ScamCheck.check(text)
            if (r.verdict == ScamCheck.Verdict.LIKELY_SCAM) {
                val from = AppInfo.label(this, sbn.packageName)
                Notify.scam(
                    this, key,
                    "A message in $from looks like a scam. Don't tap the link or reply. " + (r.reasons.firstOrNull() ?: ""),
                    text
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun isMessagingApp(pkg: String): Boolean {
        if (pkg in MESSAGING) return true
        return try {
            pkg == Telephony.Sms.getDefaultSmsPackage(this)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val MESSAGING = setOf(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger",
            "com.viber.voip", "org.thoughtcrime.securesms", "com.google.android.gm", "com.microsoft.office.outlook"
        )

        fun enabled(ctx: android.content.Context): Boolean = try {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(ComponentName(ctx, ScamWatchService::class.java).flattenToString()) && Prefs(ctx).scamWatch
        } catch (_: Exception) {
            false
        }
    }
}

/** "Account safety check": leaked password check plus simple two-step login guides. */
class AccountSafetyActivity : ShieldScreen() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen("Account safety check")

        val pw = card()
        text("Has my password been leaked?", 18f, Brand.TEXT, bold = true, parent = pw)
        text(
            "Checks a password against billions of passwords from known data breaches (Have I Been Pwned). Your " +
                "password never leaves this phone: only the first 5 characters of a scrambled version are sent, " +
                "so nobody can work out what it is. Nothing is saved.",
            14f, Brand.MUTED, parent = pw
        )
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Brand.GREY)
        input.hint = "Type a password to check"
        pw.addView(input)
        val result = text("", 15f, Brand.MUTED, parent = pw)
        button("Check password", primary = true, parent = pw) {
            val p = input.text.toString()
            input.setText("")
            if (p.isEmpty()) return@button
            result.text = "Checking…"
            result.setTextColor(Brand.MUTED)
            worker.execute {
                val n = pwnedCount(p)
                runOnUiThread {
                    when {
                        n == null -> {
                            result.text = "Couldn't check right now. Check your internet connection."
                            result.setTextColor(Brand.AMBER)
                        }
                        n > 0 -> {
                            result.text = "This password has appeared in ${"%,d".format(n)} leaks. Stop using it, and change it everywhere you use it."
                            result.setTextColor(Brand.SOFT_RED)
                        }
                        else -> {
                            result.text = "Good news: this password isn't in any known leak. Keep it unique to one account."
                            result.setTextColor(Brand.GREEN)
                        }
                    }
                }
            }
        }

        guide(
            "WhatsApp two-step verification",
            "Stops scammers taking over your WhatsApp even if they trick you into sharing a code.\n" +
                "WhatsApp → Settings → Account → Two-step verification → Turn on. Choose a PIN only you know."
        )
        guide(
            "Email two-step login",
            "Your email can reset every other password, so protect it first.\n" +
                "Gmail: myaccount.google.com → Security → 2-Step Verification.\n" +
                "Outlook / Hotmail: account.microsoft.com → Security → Two-step verification."
        )
        guide(
            "Banking",
            "Use your bank's app for alerts and approvals. Your bank will never ask for your PIN, full password or a " +
                "code by text, email or phone call."
        )
        guide(
            "Good habits",
            "• Use a different password for each important account (a password manager helps).\n" +
                "• Never share codes sent to your phone.\n" +
                "• Keep your phone and apps updated."
        )
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun guide(title: String, detail: String) {
        val c = card()
        text(title, 17f, Brand.TEXT, bold = true, parent = c)
        text(detail, 14f, Brand.MUTED, parent = c)
    }

    /** Times this password appears in known breaches (k-anonymity), or null if the check failed. */
    private fun pwnedCount(password: String): Int? {
        return try {
            val sha1 = MessageDigest.getInstance("SHA-1").digest(password.toByteArray())
                .joinToString("") { "%02X".format(it) }
            val prefix = sha1.substring(0, 5)
            val suffix = sha1.substring(5)
            val c = URL("https://api.pwnedpasswords.com/range/$prefix").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "DevilShield")
            c.setRequestProperty("Add-Padding", "true")
            if (c.responseCode != 200) return null
            var count = 0
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split(':')
                    if (parts.size == 2 && parts[0].equals(suffix, ignoreCase = true)) {
                        count = parts[1].trim().toIntOrNull() ?: 0
                        break
                    }
                }
            }
            c.disconnect()
            count
        } catch (_: Exception) {
            null
        }
    }
}

/** "Scam calls and family safe word": a calm checklist for suspicious calls, including AI voice clones. */
class CallSafetyActivity : ShieldScreen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupScreen("Scam calls and safe word")

        val now = card()
        text("On a suspicious call right now?", 18f, Brand.SOFT_RED, bold = true, parent = now)
        text(
            "1. Hang up. It's not rude. Real banks and companies are fine with it.\n" +
                "2. Never share a code, PIN or card details, or move money because a caller asked.\n" +
                "3. Call back on a number you already know: the back of your card, or the company's official website.\n" +
                "4. Never pay with gift cards, vouchers or cryptocurrency. That's always a scam.\n" +
                "5. Lost money? Call your bank immediately, then contact the Gardaí.",
            15f, Brand.TEXT, parent = now
        )

        val word = card()
        text("Family safe word", 18f, Brand.TEXT, bold = true, parent = word)
        text(
            "Scammers can now copy a person's voice from a short video, then call pretending to be your child or " +
                "partner in trouble and asking for money.\n\n" +
                "Agree a secret word with your family, face to face. Not a pet's name or anything on social media. " +
                "If someone calls asking for money urgently, ask for the word. No word, no money, however real they " +
                "sound.\n\nDon't write the word in your phone or share it by text.",
            14f, Brand.MUTED, parent = word
        )

        val signs = card()
        text("Signs of a scam call or message", 18f, Brand.TEXT, bold = true, parent = signs)
        text(
            "• It's urgent: \"right now\", \"or your account will be closed\".\n" +
                "• It asks for a code, PIN, password or card details.\n" +
                "• It asks you to move money to a \"safe account\".\n" +
                "• It asks you to install an app so they can \"help\" (remote control apps).\n" +
                "• The number looks right. Scammers can fake caller ID.",
            14f, Brand.MUTED, parent = signs
        )
    }
}
