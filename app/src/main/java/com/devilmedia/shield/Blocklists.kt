package com.devilmedia.shield

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays

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
    fun matches(domain: String, set: Set<String>): Boolean =
        set.isNotEmpty() && walk(domain) { set.contains(it) }

    fun matches(domain: String, set: DomainSet): Boolean =
        set.isNotEmpty() && walk(domain) { set.contains(it) }

    /** Tries "a.b.example.com", then "b.example.com", then "example.com". */
    private inline fun walk(domain: String, test: (String) -> Boolean): Boolean {
        var d = domain
        while (true) {
            if (test(d)) return true
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

    private const val SECURITY_FILE = "security.bin"
    private const val FAMILY_FILE = "family.bin"
    private const val DAY = 24L * 60 * 60 * 1000

    enum class Verdict { ALLOW, THREAT, FAMILY }

    @Volatile var security: DomainSet = DomainSet.EMPTY
        private set
    @Volatile var family: DomainSet = DomainSet.EMPTY
        private set
    @Volatile var trusted: Set<String> = emptySet()
        private set
    @Volatile var familyOn = false
        private set
    /** Apps cut off from the internet by the person. */
    @Volatile var blockedApps: Set<String> = emptySet()
        private set
    /** Goes up whenever settings change, so remembered decisions get refreshed. */
    @Volatile var settingsVersion = 0
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
        blockedApps = p.blockedApps
        settingsVersion++
        if (familyOn && family.isEmpty()) family = readSet(file(ctx, FAMILY_FILE))
        if (!familyOn) family = DomainSet.EMPTY
    }

    /** Loads the lists saved from last time, so protection starts straight away. */
    fun loadCached(ctx: Context) {
        // Older versions saved plain text lists; they're replaced by the lighter format.
        File(ctx.filesDir, "security.txt").delete()
        File(ctx.filesDir, "family.txt").delete()
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

    private fun download(sources: List<Source>, report: StringBuilder): DomainSet {
        val out = LongList()
        for (src in sources) {
            var result = ""
            for (u in src.urls) {
                val count = IntArray(1)
                val err = fetchInto(u, out, count)
                if (err == null) {
                    result = "OK (${count[0]})"
                    break
                }
                result = err
            }
            report.append(src.name).append(": ").append(result).append('\n')
        }
        return DomainSet(out.sortedUnique())
    }

    /** Adds a list's sites to [out]. Returns null on success, or a short reason it failed. */
    private fun fetchInto(url: String, out: LongList, count: IntArray): String? {
        var c: HttpURLConnection? = null
        val start = out.size
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 45_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DevilShield")
            val code = c.responseCode
            if (code != 200) return "HTTP $code"
            c.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val d = Domains.parseLine(line)
                    if (d != null) {
                        out.add(DomainSet.hash(d))
                        count[0]++
                    }
                }
            }
            if (count[0] > 0) null else "empty list"
        } catch (e: Exception) {
            out.truncate(start) // drop a half-downloaded list
            count[0] = 0
            e.javaClass.simpleName
        } finally {
            try {
                c?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readSet(f: File): DomainSet {
        if (!f.exists()) return DomainSet.EMPTY
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { input ->
                val n = input.readInt()
                if (n < 0 || n > 10_000_000) return DomainSet.EMPTY
                val a = LongArray(n)
                for (i in 0 until n) a[i] = input.readLong()
                DomainSet(a)
            }
        } catch (_: Exception) {
            DomainSet.EMPTY
        }
    }

    private fun writeSet(f: File, s: DomainSet) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(s.hashes.size)
                for (h in s.hashes) out.writeLong(h)
            }
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }
}

/**
 * A memory-light set of site names. It keeps an 8-byte fingerprint of each name in a
 * sorted array instead of the names themselves, so 200,000 sites use about 1.6 MB.
 */
class DomainSet(val hashes: LongArray) {
    val size: Int get() = hashes.size
    fun isEmpty(): Boolean = hashes.isEmpty()
    fun isNotEmpty(): Boolean = hashes.isNotEmpty()
    fun contains(domain: String): Boolean =
        hashes.isNotEmpty() && Arrays.binarySearch(hashes, hash(domain)) >= 0

    companion object {
        val EMPTY = DomainSet(LongArray(0))

        /** 64-bit FNV-1a fingerprint of a site name. */
        fun hash(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (ch in s) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}

/** A growable list of numbers, used while downloading. */
class LongList {
    private var a = LongArray(1 shl 16)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun truncate(n: Int) {
        if (n in 0..size) size = n
    }

    fun sortedUnique(): LongArray {
        val b = a.copyOf(size)
        b.sort()
        var n = 0
        for (i in b.indices) {
            if (n == 0 || b[i] != b[n - 1]) {
                b[n] = b[i]
                n++
            }
        }
        return b.copyOf(n)
    }
}
