package com.devilmedia.shield

/**
 * Checks a text, email or link for scam signs: known scam sites, fake sites pretending to
 * be a courier, bank or service (like "dpd-redelivery.com"), hidden links and typical scam
 * wording such as "pay a small fee to redeliver your parcel". Runs entirely on the device.
 */
object ScamCheck {

    enum class Verdict { LIKELY_SCAM, CAREFUL, NOTHING_FOUND }

    class Result(val verdict: Verdict, val reasons: List<String>, val advice: List<String>, val links: List<String>)

    /** A brand scammers pretend to be, its official site names, and what to do instead. */
    class Brand(val name: String, val tokens: Set<String>, val official: Set<String>, val tip: String)

    val BRANDS = listOf(
        Brand("DPD", setOf("dpd"), setOf("dpd", "dpdgroup", "dpdlocal"),
            "DPD doesn't ask for delivery fees by text link. Check your parcel in the official DPD app or on dpd.ie."),
        Brand("An Post", setOf("anpost"), setOf("anpost"),
            "Check parcels and customs charges only on anpost.com or the An Post app."),
        Brand("Royal Mail", setOf("royalmail"), setOf("royalmail"),
            "Check parcels only on royalmail.com or the Royal Mail app."),
        Brand("Evri", setOf("evri"), setOf("evri", "myhermes"),
            "Check parcels only on evri.com or the Evri app."),
        Brand("DHL", setOf("dhl"), setOf("dhl"),
            "Check parcels and customs charges only on dhl.com or the DHL app."),
        Brand("FedEx", setOf("fedex"), setOf("fedex"),
            "Check parcels only on fedex.com."),
        Brand("UPS", setOf("ups"), setOf("ups"),
            "Check parcels only on ups.com."),
        Brand("Amazon", setOf("amazon"), setOf("amazon"),
            "Check orders only in the Amazon app or by typing amazon.co.uk / amazon.ie yourself."),
        Brand("AIB", setOf("aib"), setOf("aib"),
            "AIB won't ask you to log in through a text link. Use the AIB app, or call the number on your card."),
        Brand("Bank of Ireland", setOf("boi", "bankofireland", "boi365"), setOf("bankofireland", "boi"),
            "Bank of Ireland won't ask you to log in through a text link. Use the app, or call the number on your card."),
        Brand("PTSB", setOf("ptsb", "permanenttsb"), setOf("ptsb", "permanenttsb"),
            "PTSB won't ask you to log in through a text link. Use the app, or call the number on your card."),
        Brand("Revolut", setOf("revolut"), setOf("revolut"),
            "Revolut deals with you inside the app. Ignore texts and calls asking you to move money."),
        Brand("PayPal", setOf("paypal"), setOf("paypal"),
            "Log in to PayPal only by typing paypal.com yourself or using the app."),
        Brand("Netflix", setOf("netflix"), setOf("netflix"),
            "Update payment details only in the Netflix app or by typing netflix.com yourself."),
        Brand("HSE", setOf("hse"), setOf("hse"),
            "The HSE's website is hse.ie. It won't ask for card details by text."),
        Brand("MyGovID", setOf("mygovid"), setOf("mygovid"),
            "Log in to MyGovID only by typing mygovid.ie yourself."),
        Brand("Apple", setOf("apple", "icloud", "appleid"), setOf("apple", "icloud"),
            "Apple won't ask you to unlock your account through a text link. Use Settings on your iPhone."),
        Brand("WhatsApp", setOf("whatsapp"), setOf("whatsapp"),
            "WhatsApp never asks for your verification code. Never share it with anyone.")
    )

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "cutt.ly", "is.gd", "rb.gy", "shorturl.at", "ow.ly", "tiny.cc", "s.id", "rebrand.ly"
    )
    private val RISKY_ENDINGS = setOf("top", "xyz", "icu", "live", "cc", "vip", "shop", "club", "cyou", "sbs", "cfd", "rest", "click", "link")
    private val TWO_PART_ENDINGS = setOf("co", "com", "org", "net", "gov", "ac", "ltd", "plc")

    private val PRESSURE = listOf(
        "redelivery", "re-delivery", "delivery fee", "small fee", "customs fee", "customs charge", "unpaid",
        "package is on hold", "parcel is on hold", "held at", "could not be delivered", "couldn't be delivered",
        "update your address", "confirm your address", "reschedule", "pay now", "outstanding balance",
        "account has been suspended", "account will be closed", "account is locked", "unusual activity",
        "verify your account", "verify your identity", "confirm your details", "final notice", "within 24 hours",
        "refund", "tax rebate", "you have won", "claim your", "toll"
    )
    private val CODE_REQUESTS = listOf(
        "send me the code", "send me that code", "6 digit code", "6-digit code", "verification code",
        "code by mistake", "sent you a code"
    )
    private val URL = Regex("(?i)\\b((?:https?://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,24}(?::\\d+)?(?:/[^\\s]*)?)")
    private val NOT_SITES = setOf("jpg", "jpeg", "png", "gif", "pdf", "txt", "doc", "docx", "mp4", "mp3")

    /** The part of a site name a company actually owns, e.g. "dpd" for "track.dpd.co.uk". */
    fun baseLabel(domain: String): String {
        val labels = domain.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return domain
        val n = labels.size
        return if (n >= 3 && labels[n - 1].length == 2 && labels[n - 2] in TWO_PART_ENDINGS) labels[n - 3] else labels[n - 2]
    }

    private fun tokens(domain: String): List<String> = domain.split('.', '-').filter { it.isNotEmpty() }

    // Brand names that scammers often glue onto other words ("dpdparcel", "anpostcustoms").
    private val PREFIX_OK = setOf("dpd", "dhl", "evri", "anpost", "royalmail", "revolut", "mygovid", "bankofireland", "paypal")

    // Real company sites that contain a brand name (content servers and so on).
    private val ALSO_OFFICIAL = setOf(
        "paypalobjects", "amazonaws", "media-amazon", "ssl-images-amazon", "amazon-adsystem", "amazontrust",
        "icloud-content", "apple-cloudkit", "cdn-apple", "nflxvideo", "nflximg", "nflxext", "nflxso"
    )

    // Words fake sites add to look convincing ("dpd-redelivery-ie", "aib-secure-login").
    private val SCAM_WORDS = listOf(
        "track", "parcel", "deliver", "redeliver", "packag", "post", "pay", "fee", "secure", "login", "logon",
        "verify", "verif", "update", "support", "custom", "refund", "account", "help", "service", "billing",
        "confirm", "auth", "unlock", "online", "ie", "uk", "eu", "info", "id"
    )

    /**
     * If [domain] looks like it's pretending to be a known brand, returns that brand.
     * [strict] is used for automatic blocking: it needs stronger evidence, so real sites never break.
     */
    fun impersonates(domain: String, strict: Boolean = false): Brand? {
        val d = domain.lowercase().removePrefix("www.")
        val base = baseLabel(d)
        if (base in ALSO_OFFICIAL) return null
        val tks = tokens(d)
        for (b in BRANDS) {
            if (base in b.official) return null // it's the real site
            val exact = tks.any { it in b.tokens }
            val glued = tks.any { t ->
                b.tokens.any { bt -> bt in PREFIX_OK && t.startsWith(bt) && t.length in (bt.length + 2)..(bt.length + 14) }
            }
            if (!exact && !glued) continue
            if (!strict) return b
            // Automatic blocking: the brand name plus a typical scam word somewhere in the address.
            val others = tks.filter { t -> t !in b.tokens }
            val scammy = others.any { t -> SCAM_WORDS.any { w -> t == w || (w.length >= 4 && t.contains(w)) } } ||
                (glued && tks.any { t -> b.tokens.any { bt -> t.startsWith(bt) && SCAM_WORDS.any { w -> w.length >= 4 && t.removePrefix(bt).contains(w) } } })
            if (scammy) return b
        }
        return null
    }

    fun extractDomains(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in URL.findAll(text)) {
            var s = m.groupValues[1].lowercase()
            s = s.removePrefix("http://").removePrefix("https://")
            val host = s.substringBefore('/').substringBefore(':').trimEnd('.')
            val ending = host.substringAfterLast('.')
            if (ending in NOT_SITES || !host.contains('.')) continue
            out += host
        }
        return out.toList()
    }

    fun check(text: String): Result {
        val lower = text.lowercase()
        val domains = extractDomains(text)
        val reasons = ArrayList<String>()
        val advice = LinkedHashSet<String>()
        var score = 0

        for (d in domains) {
            val host = d.removePrefix("www.")
            if (Blocklists.check(host) == Blocklists.Verdict.THREAT) {
                score += 10
                reasons += "$host is on Devil Shield's list of known scam and malware sites."
            }
            val brand = impersonates(host)
            if (brand != null) {
                score += 8
                reasons += "$host is pretending to be ${brand.name}. It isn't ${brand.name}'s real website."
                advice += brand.tip
            }
            if (host in SHORTENERS) {
                score += 3
                reasons += "$host is a link shortener, which hides where the link really goes."
            }
            if (host.substringAfterLast('.') in RISKY_ENDINGS) {
                score += 2
                reasons += "$host uses a web address ending that scammers use a lot."
            }
            if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                score += 4
                reasons += "The link is a bare number address, which real companies don't use in texts."
            }
        }

        // Brand mentioned in the words, but the link goes somewhere else
        if (domains.isNotEmpty()) {
            for (b in BRANDS) {
                val mentioned = b.tokens.any { t -> Regex("\\b${Regex.escape(t)}\\b").containsMatchIn(lower) } ||
                    lower.contains(b.name.lowercase())
                if (mentioned && b.name.length > 2 && domains.none { baseLabel(it.removePrefix("www.")) in b.official }) {
                    score += 3
                    reasons += "The message mentions ${b.name}, but the link doesn't go to ${b.name}'s website."
                    advice += b.tip
                    break
                }
            }
        }

        val pressure = PRESSURE.filter { lower.contains(it) }
        if (pressure.isNotEmpty()) {
            score += if (domains.isNotEmpty()) 3 else 1
            reasons += "It uses typical scam wording (\"${pressure.first()}\")" + if (domains.isNotEmpty()) " together with a link." else "."
        }
        if (Regex("[€£$]\\s?\\d{1,2}([.,]\\d{2})?\\b").containsMatchIn(text) && domains.isNotEmpty()) {
            score += 2
            reasons += "It asks for a small payment through a link, a classic parcel and toll scam."
        }
        if (CODE_REQUESTS.any { lower.contains(it) }) {
            score += 8
            reasons += "It asks for a code. Sharing a verification code can hand over your WhatsApp, email or bank account."
            advice += "Never share a code sent to your phone, even with someone who seems to be a friend or family member."
        }

        val verdict = when {
            score >= 8 -> Verdict.LIKELY_SCAM
            score >= 3 -> Verdict.CAREFUL
            else -> Verdict.NOTHING_FOUND
        }
        if (verdict != Verdict.NOTHING_FOUND) {
            advice += "Don't tap the link or reply. Delete the message."
            advice += "If you're expecting something, check it yourself in the company's official app or website."
            advice += "If you've already entered card details, call your bank straight away."
        }
        return Result(verdict, reasons.distinct(), advice.toList(), domains)
    }
}
