package com.devilmedia.shield

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Remove my data": writes data-protection request emails and keeps track of them.
 * Everything stays on the device. The wording follows the GDPR articles named in each
 * email; it isn't legal advice.
 */
object Removals {
    const val MONTH = 30L * 24 * 60 * 60 * 1000

    enum class Type(val label: String) {
        ERASE("Delete my data"),
        ACCESS("What do you have on me?"),
        STOP("Stop marketing me"),
        FORM("Removal form")
    }

    enum class Status(val label: String) {
        WAITING("Waiting for a reply"),
        REPLIED("They replied"),
        REMOVED("Removed ✓"),
        IGNORED("No reply")
    }

    class Request(
        val company: String,
        val email: String,
        val type: Type,
        val sentAt: Long,
        var status: Status = Status.WAITING,
        var reminded: Boolean = false
    ) {
        val overdue: Boolean
            get() = (status == Status.WAITING || status == Status.IGNORED) && System.currentTimeMillis() - sentAt > MONTH
    }

    /** Data companies with their official removal pages (checked on each company's own site). */
    class Broker(val name: String, val what: String, val url: String)

    val BROKERS = listOf(
        Broker(
            "192.com", "UK people-search directory (names, addresses, phone directory records)",
            "https://www.192.com/c01/new-request/"
        ),
        Broker(
            "Acxiom", "Marketing data company (builds profiles for advertisers)",
            "https://www.acxiom.com/privacy/international/"
        ),
        Broker(
            "Experian Marketing Services", "Marketing data (separate from credit reports)",
            "https://www.experian.co.uk/privacy/consumer-information-portal/opt-out-options"
        )
    )

    const val GOOGLE_RESULTS_ABOUT_YOU = "https://myactivity.google.com/results-about-you"
    const val GOOGLE_EU_REMOVAL_FORM = "https://reportcontent.google.com/forms/rtbf"
    const val DPC_FORM = "https://forms.dataprotection.ie/contact"
    const val DPC_EMAIL = "info@dataprotection.ie"

    // --- Your details ---------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("removals", Context.MODE_PRIVATE)

    fun name(ctx: Context): String = prefs(ctx).getString("name", "") ?: ""
    fun email(ctx: Context): String = prefs(ctx).getString("email", "") ?: ""
    fun phone(ctx: Context): String = prefs(ctx).getString("phone", "") ?: ""

    fun setDetails(ctx: Context, name: String, email: String, phone: String) {
        prefs(ctx).edit().putString("name", name).putString("email", email).putString("phone", phone).apply()
    }

    fun hasDetails(ctx: Context) = name(ctx).isNotBlank() && email(ctx).isNotBlank()

    // --- Requests --------------------------------------------------------------

    @Synchronized fun load(ctx: Context): MutableList<Request> {
        val out = ArrayList<Request>()
        try {
            val arr = JSONArray(prefs(ctx).getString("requests", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Request(
                    o.optString("c"), o.optString("e"),
                    runCatching { Type.valueOf(o.optString("y", "ERASE")) }.getOrDefault(Type.ERASE),
                    o.optLong("t"),
                    runCatching { Status.valueOf(o.optString("s", "WAITING")) }.getOrDefault(Status.WAITING),
                    o.optBoolean("r")
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized fun save(ctx: Context, list: List<Request>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject().put("c", r.company).put("e", r.email).put("y", r.type.name)
                    .put("t", r.sentAt).put("s", r.status.name).put("r", r.reminded)
            )
        }
        prefs(ctx).edit().putString("requests", arr.toString()).apply()
    }

    fun add(ctx: Context, company: String, email: String, type: Type) {
        val list = load(ctx)
        list.add(0, Request(company, email, type, System.currentTimeMillis()))
        save(ctx, list)
    }

    /** Removes the code indentation from the email text, line by line. */
    private fun tidy(text: String): String = text.lines().joinToString("\n") { it.trim() }.trim()

    fun date(t: Long): String = SimpleDateFormat("d MMMM yyyy", Locale.UK).format(Date(t))

    // --- Email wording ------------------------------------------------------------

    private fun details(name: String, email: String, phone: String): String =
        "Name: $name\nEmail: $email" + if (phone.isNotBlank()) "\nPhone: $phone" else ""

    private fun linkedTo(phone: String) =
        if (phone.isNotBlank()) "this email address or phone number" else "this email address"

    fun subject(type: Type): String = when (type) {
        Type.ERASE, Type.FORM -> "Request to erase my personal data (GDPR Article 17)"
        Type.ACCESS -> "Subject access request (GDPR Article 15)"
        Type.STOP -> "Objection to direct marketing (GDPR Article 21)"
    }

    fun body(type: Type, company: String, name: String, email: String, phone: String): String = when (type) {
        Type.ERASE, Type.FORM -> """
            Dear Data Protection Officer,

            I am writing to ask $company to erase all personal data you hold about me, under Article 17 of the General Data Protection Regulation (GDPR).

            My details, so you can find my data:
            ${details(name, email, phone)}

            Please:
            1. Erase all personal data you hold about me, including any account or records linked to ${linkedTo(phone)}.
            2. Stop using my personal data for marketing or any other purpose.
            3. Tell any third parties you have shared my personal data with about this request (Article 19 GDPR).
            4. Confirm in writing when this has been done.

            Under Article 12(3) GDPR, please respond within one month of receiving this request. If you need to confirm my identity, please ask only for the minimum information necessary.

            Thank you,
            $name
        """.let { tidy(it) }

        Type.ACCESS -> """
            Dear Data Protection Officer,

            I am making a subject access request to $company under Article 15 of the General Data Protection Regulation (GDPR).

            My details, so you can find my data:
            ${details(name, email, phone)}

            Please send me:
            1. A copy of all personal data you hold about me, including anything linked to ${linkedTo(phone)}.
            2. The purposes you use it for.
            3. Who you have shared it with, or will share it with.
            4. How long you will keep it.
            5. Where you got it from, if not from me.

            Under Article 12(3) GDPR, please respond within one month of receiving this request. This request is free of charge. If you need to confirm my identity, please ask only for the minimum information necessary.

            Thank you,
            $name
        """.let { tidy(it) }

        Type.STOP -> """
            Dear Data Protection Officer,

            I object to $company using my personal data for direct marketing, under Article 21(2) of the General Data Protection Regulation (GDPR). I also withdraw any consent I may have given for marketing (Article 7(3) GDPR).

            My details, so you can find my data:
            ${details(name, email, phone)}

            Please:
            1. Stop sending me marketing by email, phone, text and post.
            2. Stop any profiling of me for marketing.
            3. Stop sharing or selling my personal data to others for marketing.
            4. Confirm in writing when this has been done.

            Under Article 21(3) GDPR, my personal data must no longer be used for direct marketing once I object. Please respond within one month of receiving this request (Article 12(3) GDPR).

            Thank you,
            $name
        """.let { tidy(it) }
    }

    fun reminderBody(r: Request, name: String, email: String, phone: String): String = """
        Dear Data Protection Officer,

        On ${date(r.sentAt)} I sent ${r.company} a request under the General Data Protection Regulation (GDPR): "${subject(r.type)}". More than one month has passed and I have not received a full reply.

        My details:
        ${details(name, email, phone)}

        Please deal with my request and confirm in writing. If I do not hear from you, I may make a complaint to the Data Protection Commission.

        Thank you,
        $name
    """.let { tidy(it) }

    /** A ready-to-paste summary for a complaint to Ireland's Data Protection Commission. */
    fun complaintSummary(r: Request, name: String, email: String): String = """
        Complaint about: ${r.company}
        My name: $name
        My email: $email

        What I asked for: ${subject(r.type)}
        Date I asked: ${date(r.sentAt)}
        Sent to: ${r.email.ifBlank { "their removal form" }}
        Reply received: ${if (r.status == Status.REPLIED) "Yes, but my request was not fully dealt with" else "No reply within one month"}

        I asked ${r.company} to deal with my request under the GDPR and they have not done so within the one-month time limit set by Article 12(3). I would like the Data Protection Commission to look into this.
    """.let { tidy(it) }

    /** Gentle notice for requests with no reply after a month (once each). */
    fun remindIfDue(ctx: Context) {
        val list = load(ctx)
        val due = list.filter { it.overdue && !it.reminded }
        if (due.isEmpty()) return
        due.forEach { it.reminded = true }
        save(ctx, list)
        val names = due.joinToString(", ") { it.company }
        Notify.removal(
            ctx,
            "It's been a month since you asked $names about your data. If you haven't heard back, " +
                "Devil Shield can send a reminder or help you complain."
        )
    }
}
