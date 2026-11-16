package com.devilmedia.shield

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

class LogEntry(
    val time: Long,
    val domain: String,
    val app: String,
    val pkg: String,
    val family: Boolean,
    /** True when blocked because the person cut this app off. */
    val cut: Boolean = false
)

/**
 * Remembers what Devil Shield blocked, today's counts, and which apps tried to reach
 * known threats (the health check uses this to spot hidden malware).
 */
object ActivityLog {
    private const val MAX = 300
    private val entries = ArrayDeque<LogEntry>()
    private val flagged = HashMap<String, Int>()     // package -> times it tried to reach a threat ("" = unknown app)
    private val flaggedNames = HashMap<String, String>()
    private var day = 0L
    private var loaded = false
    private var lastSave = 0L

    @Volatile var checkedToday = 0
        private set
    @Volatile var blockedToday = 0
        private set
    /** All threats ever blocked (not family-filter blocks). Used for the weekly summary. */
    @Volatile var threatsBlocked = 0
        private set

    private fun today(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L
    }

    private fun rollDay() {
        val d = today()
        if (d != day) {
            day = d
            checkedToday = 0
            blockedToday = 0
        }
    }

    @Synchronized fun recordCheck() {
        rollDay()
        checkedToday++
    }

    @Synchronized fun recordBlock(ctx: Context, e: LogEntry) {
        rollDay()
        blockedToday++
        entries.addFirst(e)
        while (entries.size > MAX) entries.removeLast()
        if (!e.family && !e.cut) {
            threatsBlocked++
            flagged[e.pkg] = (flagged[e.pkg] ?: 0) + 1
            flaggedNames[e.pkg] = e.app
        }
        if (System.currentTimeMillis() - lastSave > 15_000) save(ctx)
    }

    @Synchronized fun recent(): List<LogEntry> = entries.toList()

    /** (package, app name, times) for apps that tried to reach known threats, most first. */
    @Synchronized fun flaggedApps(): List<Triple<String, String, Int>> =
        flagged.map { Triple(it.key, flaggedNames[it.key] ?: it.key, it.value) }
            .sortedByDescending { it.third }

    @Synchronized fun clearFlag(ctx: Context, pkg: String) {
        flagged.remove(pkg)
        flaggedNames.remove(pkg)
        save(ctx)
    }

    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.filesDir, "activity.json")
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            day = o.optLong("day")
            checkedToday = o.optInt("checked")
            blockedToday = o.optInt("blocked")
            threatsBlocked = o.optInt("threats")
            rollDay()
            val arr = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                entries.addLast(
                    LogEntry(e.optLong("t"), e.optString("d"), e.optString("a"), e.optString("p"), e.optBoolean("f"), e.optBoolean("c"))
                )
            }
            val fl = o.optJSONObject("flagged")
            if (fl != null) {
                for (k in fl.keys()) {
                    val v = fl.getJSONObject(k)
                    flagged[k] = v.optInt("n")
                    flaggedNames[k] = v.optString("name", k)
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized fun save(ctx: Context) {
        lastSave = System.currentTimeMillis()
        try {
            val o = JSONObject()
            o.put("day", day)
            o.put("checked", checkedToday)
            o.put("blocked", blockedToday)
            o.put("threats", threatsBlocked)
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject().put("t", e.time).put("d", e.domain).put("a", e.app)
                        .put("p", e.pkg).put("f", e.family).put("c", e.cut)
                )
            }
            o.put("entries", arr)
            val fl = JSONObject()
            for ((k, n) in flagged) fl.put(k, JSONObject().put("n", n).put("name", flaggedNames[k] ?: k))
            o.put("flagged", fl)
            val tmp = File(ctx.filesDir, "activity.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(ctx.filesDir, "activity.json"))
        } catch (_: Exception) {
        }
    }
}

/** Friendly names for apps. */
object AppInfo {
    const val UNKNOWN = "An app on this device"

    fun forUid(ctx: Context, uid: Int): Pair<String, String> {
        if (uid == Process.SYSTEM_UID) return Pair("Android system", "android")
        val pkgs = ctx.packageManager.getPackagesForUid(uid)
        if (pkgs.isNullOrEmpty()) return Pair(UNKNOWN, "")
        val pkg = pkgs[0]
        return Pair(label(ctx, pkg), pkg)
    }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun isSystem(ctx: Context, pkg: String): Boolean = try {
        val flags = ctx.packageManager.getApplicationInfo(pkg, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (_: Exception) {
        false
    }
}
