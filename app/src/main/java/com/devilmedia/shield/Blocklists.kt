package com.devilmedia.shield

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Helpers for reading and matching website names. */
object Domains {

    /** Tidies a site name, or returns null if it isn't a usable one. */
    fun clean(raw: String): String? {
        var d = raw.trim().lowercase().trimEnd('.')
        if (d.startsWith("*.")) d = d.substring(2)
        if (d.isEmpty() || d.length > 253 || !d.contains('.')) return null
        for (c in d) {
            val ok = (c in 'a'..'z') || (c in '0'..'9') || c == '.' || c == '-' || c == '_'
            if (!ok) return null
        }
        return d
    }

    /** Reads one line of a protection list (plain list or hosts-file style). */
    fun parseLine(line: String): String? {
        val l = line.substringBefore('#').trim()
        if (l.isEmpty() || l.startsWith("!") || l.startsWith("[")) return null
        val parts = l.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var token = if (parts.size >= 2 &&
            (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1")
        ) parts[1] else parts[0]
        if (token.startsWith("||")) token = token.removePrefix("||").substringBefore('^')
        if (token == "localhost" || token == "0.0.0.0" || token == "localhost.localdomain") return null
        return clean(token)
    }

    /** True if the site, or any site it belongs to, is in the set. */
    fun matches(domain: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var d = domain
        while (true) {
            if (set.contains(d)) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
            if (!d.contains('.')) return false // never match a bare ending like "com"
        }
    }
}

/**
 * The protection lists. They contain known malware, scam, phishing and botnet sites
 * only, from public threat-intelligence feeds. The family list (adult and gambling
 * sites) is used only when the person turns the family filter on.
 */
object Blocklists {

    // Each inner list is one source; the first address that works is used.
    private class Source(val name: String, val urls: List<String>)

    // Several independent sources, each with backup addresses, so one being down
    // doesn't leave the device unprotected. Threat lists only: no ad or tracker lists.
    private val SECURITY_SOURCES = listOf(
        Source(
            "HaGeZi threat feeds", listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt",
                "https://gitlab.com/hagezi/mirror/-/raw/main/dns-blocklists/wildcard/tif.mini-onlydomains.txt"
            )
        ),
        Source(
            "Malware sites (URLhaus)", listOf(
                "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt",
                "https://curbengh.github.io/malware-filter/urlhaus-filter-hosts.txt",
                "https://urlhaus.abuse.ch/downloads/hostfile/"
            )
        ),
        Source(
            "Phishing sites", listOf(
                "https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt",
                "https://curbengh.github.io/malware-filter/phishing-filter-hosts.txt"
            )
        )
    )

    private val FAMILY_SOURCES = listOf(
        Source(
            "Adult sites", listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt",
                "https://gitlab.com/hagezi/mirror/-/raw/main/dns-blocklists/wildcard/nsfw-onlydomains.txt"
            )
        ),
        Source(
            "Gambling sites", listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling.mini-onlydomains.txt",
                "https://gitlab.com/hagezi/mirror/-/raw/main/dns-blocklists/wildcard/gambling.mini-onlydomains.txt"
            )
        )
    )

    /** What happened on the last download attempt, shown in Settings to help sort out problems. */
    @Volatile var lastReport: String = ""
        private set

    private const val SECURITY_FILE = "security.txt"
    private const val FAMILY_FILE = "family.txt"
    private const val DAY = 24L * 60 * 60 * 1000

    enum class Verdict { ALLOW, THREAT, FAMILY }

    @Volatile var security: Set<String> = emptySet()
        private set
    @Volatile var family: Set<String> = emptySet()
        private set
    @Volatile var trusted: Set<String> = emptySet()
        private set
    @Volatile var familyOn = false
        private set
    @Volatile var updating = false
        private set

    fun check(domain: String): Verdict {
        if (Domains.matches(domain, trusted)) return Verdict.ALLOW
        if (Domains.matches(domain, security)) return Verdict.THREAT
        if (familyOn && Domains.matches(domain, family)) return Verdict.FAMILY
        return Verdict.ALLOW
    }

    /** Picks up changes to trusted sites and the family filter. */
    fun refreshSettings(ctx: Context) {
        val p = Prefs(ctx)
        trusted = p.trusted
        familyOn = p.familyFilter
        if (familyOn && family.isEmpty()) family = readSet(file(ctx, FAMILY_FILE))
        if (!familyOn) family = emptySet()
    }

    /** Loads the lists saved from last time, so protection starts straight away. */
    fun loadCached(ctx: Context) {
        if (security.isEmpty()) security = readSet(file(ctx, SECURITY_FILE))
        refreshSettings(ctx)
    }

    /** Downloads fresh lists if they're more than a day old (or if forced). Returns true on success. */
    fun updateIfStale(ctx: Context, force: Boolean = false): Boolean {
        val p = Prefs(ctx)
        val stale = force || security.isEmpty() || (familyOn && family.isEmpty()) ||
            System.currentTimeMillis() - p.listsUpdatedAt > DAY
        if (!stale) return false
        // If an update is already running (e.g. started when protection turned on), wait for it.
        if (updating) {
            var waited = 0
            while (updating && waited < 180) {
                Thread.sleep(1000)
                waited++
            }
            return security.isNotEmpty() && System.currentTimeMillis() - p.listsUpdatedAt < 5 * 60 * 1000
        }
        updating = true
        try {
            val report = StringBuilder()
            val sec = download(SECURITY_SOURCES, report)
            val ok = sec.size > 1000
            lastReport = report.toString().trim()
            if (ok) {
                writeSet(file(ctx, SECURITY_FILE), sec)
                security = sec
                p.listsUpdatedAt = System.currentTimeMillis()
                p.listsCount = sec.size
            }
            if (familyOn) {
                val fam = download(FAMILY_SOURCES, StringBuilder())
                if (fam.size > 1000) {
                    writeSet(file(ctx, FAMILY_FILE), fam)
                    family = fam
                }
            }
            return ok
        } finally {
            updating = false
        }
    }

    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    private fun download(sources: List<Source>, report: StringBuilder): HashSet<String> {
        val out = HashSet<String>(200_000)
        for (src in sources) {
            var result = ""
            for (u in src.urls) {
                val before = out.size
                val err = fetchInto(u, out)
                if (err == null) {
                    result = "OK (${out.size - before})"
                    break
                }
                result = err
            }
            report.append(src.name).append(": ").append(result).append('\n')
        }
        return out
    }

    /** Adds a list's sites to [out]. Returns null on success, or a short reason it failed. */
    private fun fetchInto(url: String, out: MutableSet<String>): String? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 45_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DevilShield")
            val code = c.responseCode
            if (code != 200) return "HTTP $code"
            var added = 0
            c.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val d = Domains.parseLine(line)
                    if (d != null && out.add(d)) added++
                }
            }
            if (added > 0) null else "empty list"
        } catch (e: Exception) {
            e.javaClass.simpleName
        } finally {
            try {
                c?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readSet(f: File): Set<String> {
        if (!f.exists()) return emptySet()
        return try {
            val s = HashSet<String>(200_000)
            f.bufferedReader().useLines { lines -> lines.forEach { if (it.isNotEmpty()) s.add(it) } }
            s
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun writeSet(f: File, s: Set<String>) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { w -> for (d in s) { w.write(d); w.write("\n") } }
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }
}
