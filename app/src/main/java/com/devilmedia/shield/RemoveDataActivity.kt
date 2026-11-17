package com.devilmedia.shield

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/** "Remove my data": ask companies to delete, show or stop using your personal data. */
class RemoveDataActivity : ShieldScreen() {

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
        heading("Remove my data")
        text(
            "Under European data protection law (GDPR) you can ask any company to show you, delete, or stop " +
                "using your personal data, and they normally have one month to reply. Devil Shield writes the " +
                "request for you. You just tap send."
        )

        // Your details
        val me = card()
        text("Your details", 18f, Brand.TEXT, bold = true, parent = me)
        if (Removals.hasDetails(this)) {
            val phone = Removals.phone(this)
            text(
                "${Removals.name(this)}\n${Removals.email(this)}" + if (phone.isNotBlank()) "\n$phone" else "",
                15f, Brand.MUTED, parent = me
            )
            text("Used only in the requests you send. Kept on this device.", 12f, Brand.GREY, parent = me)
            button("Change my details", parent = me) { editDetails() }
        } else {
            text("Add your name, email and (optionally) phone number so companies can find your data.", parent = me)
            button("Add my details", primary = true, parent = me) { editDetails() }
        }

        // Send a request
        val send = card()
        text("Send a request", 18f, Brand.TEXT, bold = true, parent = send)
        button("Ask a company to delete my data", primary = true, parent = send) { newRequest(Removals.Type.ERASE) }
        button("Ask what they have on me", parent = send) { newRequest(Removals.Type.ACCESS) }
        button("Ask them to stop marketing me", parent = send) { newRequest(Removals.Type.STOP) }

        // Data companies
        val brokers = card()
        text("Data companies", 18f, Brand.TEXT, bold = true, parent = brokers)
        text(
            "These companies collect and sell personal details, often without you knowing. Each link opens the " +
                "company's own removal page.", 14f, Brand.MUTED, parent = brokers
        )
        for (b in Removals.BROKERS) {
            text(b.name, 16f, Brand.TEXT, bold = true, parent = brokers)
            text(b.what, 13f, Brand.GREY, parent = brokers)
            button("Open ${b.name} removal page", parent = brokers) {
                open(b.url)
                Removals.add(this, b.name, "", Removals.Type.FORM)
                toast("Added to your requests, so you can keep track.")
            }
        }

        // Google search
        val google = card()
        text("Remove from Google search", 18f, Brand.TEXT, bold = true, parent = google)
        text(
            "Ask Google to remove search results that show your phone number, email or home address. Google " +
                "removes them from its search results; the website itself may still have them.",
            14f, Brand.MUTED, parent = google
        )
        button("Google \"Results about you\"", parent = google) { open(Removals.GOOGLE_RESULTS_ABOUT_YOU) }
        button("Google EU removal request form", parent = google) { open(Removals.GOOGLE_EU_REMOVAL_FORM) }

        // Your requests
        heading("Your requests")
        val list = Removals.load(this)
        if (list.isEmpty()) {
            text("No requests yet.", 15f, Brand.GREY)
        } else {
            val done = list.count { it.status == Removals.Status.REMOVED }
            text("${list.size} sent · $done removed", 14f, Brand.GREY)
            list.forEachIndexed { i, r -> requestCard(i, r) }
        }

        // Tips
        val tips = card()
        text("Tips", 18f, Brand.TEXT, bold = true, parent = tips)
        text(
            "• Forgotten where you signed up? Search your email inbox for words like \"welcome\", \"verify your " +
                "email\" or \"your account\". Each company that turns up has your details.\n" +
                "• Fewer scam calls: ask your phone provider to make your number ex-directory and to opt you out " +
                "of marketing calls.\n" +
                "• In future, sign up to sites with an \"alias\" email address, so you can switch it off if it's sold.",
            14f, Brand.MUTED, parent = tips
        )
    }

    private fun requestCard(index: Int, r: Removals.Request) {
        val c = card()
        text(r.company, 17f, Brand.TEXT, bold = true, parent = c)
        text("${r.type.label} · sent ${Removals.date(r.sentAt)}", 13f, Brand.GREY, parent = c)
        val color = when (r.status) {
            Removals.Status.REMOVED -> Brand.GREEN
            Removals.Status.IGNORED -> Brand.SOFT_RED
            else -> if (r.overdue) Brand.AMBER else Brand.MUTED
        }
        val statusText = if (r.overdue && r.status == Removals.Status.WAITING) "No reply after a month" else r.status.label
        text(statusText, 15f, color, parent = c)

        if (r.status != Removals.Status.REMOVED) {
            button("Update status", parent = c) { pickStatus(index) }
        }
        if (r.overdue && r.email.isNotBlank()) {
            button("Send a reminder", parent = c) {
                email(
                    r.email, "Reminder: " + Removals.subject(r.type),
                    Removals.reminderBody(r, Removals.name(this), Removals.email(this), Removals.phone(this))
                )
            }
        }
        if (r.overdue || r.status == Removals.Status.IGNORED) {
            button("Complain to the Data Protection Commission", parent = c) { complain(r) }
        }
    }

    private fun pickStatus(index: Int) {
        val options = arrayOf("They replied", "My data was removed", "No reply", "Still waiting")
        dialog().setTitle("What happened?").setItems(options) { _, which ->
            val list = Removals.load(this)
            if (index in list.indices) {
                list[index].status = when (which) {
                    0 -> Removals.Status.REPLIED
                    1 -> Removals.Status.REMOVED
                    2 -> Removals.Status.IGNORED
                    else -> Removals.Status.WAITING
                }
                Removals.save(this, list)
                render()
            }
        }.show()
    }

    private fun complain(r: Removals.Request) {
        val summary = Removals.complaintSummary(r, Removals.name(this), Removals.email(this))
        dialog()
            .setTitle("Complain to the DPC")
            .setMessage(
                "Ireland's Data Protection Commission can look into companies that ignore data requests. Copy " +
                    "this summary, then paste it into their online form.\n\n$summary"
            )
            .setPositiveButton("Copy and open form") { _, _ ->
                copy(summary)
                open(Removals.DPC_FORM)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editDetails() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        fun field(hint: String, value: String, type: Int): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.inputType = type
            e.setTextColor(Brand.TEXT)
            e.setHintTextColor(Brand.GREY)
            box.addView(e)
            return e
        }
        val name = field("Your full name", Removals.name(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val mail = field("Your email address", Removals.email(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val phone = field("Phone number (optional)", Removals.phone(this), InputType.TYPE_CLASS_PHONE)
        dialog()
            .setTitle("Your details")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Removals.setDetails(this, name.text.toString().trim(), mail.text.toString().trim(), phone.text.toString().trim())
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newRequest(type: Removals.Type) {
        if (!Removals.hasDetails(this)) {
            toast("Add your name and email first.")
            editDetails()
            return
        }
        askText("Which company?", "Type the company or website name, for example \"Example Shop\".", "Company name", false) { company ->
            if (company.isBlank()) return@askText
            dialog()
                .setTitle("Their privacy email")
                .setMessage(
                    "Requests go to the company's privacy or data-protection email. It's usually on their " +
                        "\"Privacy policy\" page."
                )
                .setPositiveButton("I have it") { _, _ -> askEmailAndSend(type, company) }
                .setNeutralButton("Find it for me") { _, _ ->
                    open("https://www.google.com/search?q=" + Uri.encode("$company privacy policy data protection email"))
                    toast("When you've found it, come back and tap the button again.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun askEmailAndSend(type: Removals.Type, company: String) {
        askText("Their privacy email", "For example privacy@example.com", "privacy@…", false) { to ->
            if (!to.contains('@')) {
                toast("That doesn't look like an email address.")
                return@askText
            }
            val sent = email(
                to, Removals.subject(type),
                Removals.body(type, company, Removals.name(this), Removals.email(this), Removals.phone(this))
            )
            if (sent) {
                Removals.add(this, company, to, type)
                toast("Request ready in your email app. Tap send there.")
                render()
            }
        }
    }

    /** Opens the email app with everything filled in. Returns false if it couldn't. */
    private fun email(to: String, subject: String, body: String): Boolean {
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        return try {
            startActivity(mailto)
            true
        } catch (_: Exception) {
            try {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                    .putExtra(Intent.EXTRA_TEXT, body)
                startActivity(Intent.createChooser(share, "Send with"))
                true
            } catch (_: Exception) {
                copy("To: $to\nSubject: $subject\n\n$body")
                toast("No email app found. The request is copied, so you can paste it into an email.")
                true
            }
        }
    }

    private fun open(url: String) {
        safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun copy(text: String) {
        try {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Devil Shield", text))
        } catch (_: Exception) {
        }
    }
}
