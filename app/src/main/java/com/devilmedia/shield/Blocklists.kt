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
    private val SECURITY_SOURCES = listOf(
        listOf(
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/tif.mini.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.mini.txt"
        ),
        listOf("https://urlhaus.abuse.ch/downloads/hostfile/")
    )

    private val FAMILY_SOURCES = listOf(
        listOf(
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/nsfw.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/nsfw.txt"
        ),
        listOf(
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/gambling.mini.txt",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/gambling.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/gambling.txt"
        )
    )

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
        if (!stale || updating) return false
        updating = true
        try {
            val sec = download(SECURITY_SOURCES)
            val ok = sec.size > 1000
            if (ok) {
                writeSet(file(ctx, SECURITY_FILE), sec)
                security = sec
                p.listsUpdatedAt = System.currentTimeMillis()
                p.listsCount = sec.size
            }
            if (familyOn) {
                val fam = download(FAMILY_SOURCES)
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

    private fun download(groups: List<List<String>>): HashSet<String> {
        val out = HashSet<String>(200_000)
        for (urls in groups) {
            for (u in urls) {
                if (fetchInto(u, out)) break
            }
        }
        return out
    }

    private fun fetchInto(url: String, out: MutableSet<String>): Boolean {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "DevilShield")
            if (c.responseCode != 200) {
                c.disconnect()
                return false
            }
            var added = 0
            c.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val d = Domains.parseLine(line)
                    if (d != null && out.add(d)) added++
                }
            }
            c.disconnect()
            added > 0
        } catch (_: Exception) {
            false
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
