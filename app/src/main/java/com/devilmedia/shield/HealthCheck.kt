package com.devilmedia.shield

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Locale

enum class Level { GOOD, WATCH, ACTION }

class Action(val label: String, val perform: (Activity) -> Unit)

class Finding(
    val level: Level,
    val title: String,
    val detail: String,
    val actions: List<Action> = emptyList()
)

/**
 * Looks for the usual signs of trouble on a box, phone or tablet and explains each one
 * in plain, calm language with a simple next step.
 */
object HealthCheck {

    private val TRUSTED_STORES = setOf(
        "com.android.vending", "com.amazon.venezia",
        "com.sec.android.app.samsungapps", "com.huawei.appmarket"
    )

    // Generic box models named in public reports about factory-installed malware.
    private val REPORTED_MODELS = listOf("T95", "X88", "MXQ")

    fun run(ctx: Context): List<Finding> {
        val out = ArrayList<Finding>()
        val pm = ctx.packageManager
        val cr = ctx.contentResolver

        // Protection
        if (ShieldVpnService.running) {
            out += Finding(Level.GOOD, "Protection is on", "Devil Shield is checking connections against known threats.")
        } else if (ctx.otherVpnActive()) {
            out += Finding(
                Level.WATCH, "Another VPN is running",
                "Devil Shield's blocking is paused while another VPN app (like Surfshark) is on, because Android " +
                    "only allows one at a time. Everything on this page still works. " +
                    "Built-in VPN support is coming soon, so you can have both."
            )
        } else {
            out += Finding(
                Level.ACTION, "Protection is off",
                "Turn Devil Shield on so it can block malware, scams and spying.",
                listOf(Action("Go to the main screen") { it.startActivity(Intent(it, MainActivity::class.java)) })
            )
        }

        // Apps that tried to reach known threats
        for ((pkg, name, n) in ActivityLog.flaggedApps()) {
            val times = if (n == 1) "once" else "$n times"
            if (pkg.isEmpty()) {
                out += Finding(
                    Level.WATCH, "An app tried to reach known threats",
                    "It tried $times and Devil Shield blocked it every time. This device doesn't say which app it was. " +
                        "Keep protection on and check again later.",
                    listOf(Action("Got it") { a -> ActivityLog.clearFlag(a, ""); a.recreate() })
                )
                continue
            }
            if (!AppInfo.isInstalled(ctx, pkg)) {
                ActivityLog.clearFlag(ctx, pkg) // already removed
                continue
            }
            val settings = Action("App settings") {
                it.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }
            if (AppInfo.isSystem(ctx, pkg)) {
                out += Finding(
                    Level.ACTION, "$name tried to reach known threats",
                    "It tried $times and Devil Shield blocked it every time, so it's cut off.\n\n" +
                        "This is part of the device's own software, so it can't simply be uninstalled. " +
                        "Your options, easiest first:\n" +
                        "1. Keep Devil Shield on. It keeps this app cut off.\n" +
                        "2. Open App settings and tap Disable, if your device allows it.\n" +
                        "3. Ask the seller for clean, official software for the device.\n" +
                        "4. If it keeps happening, consider a certified box such as Google TV, Fire TV or Nvidia Shield.",
                    listOf(settings, Action("I've dealt with it") { a -> ActivityLog.clearFlag(a, pkg); a.recreate() })
                )
            } else {
                out += Finding(
                    Level.ACTION, "$name tried to reach known threats",
                    "It tried $times and Devil Shield blocked it every time. " +
                        "If you don't recognise this app or don't need it, removing it is the safest choice.",
                    listOf(
                        Action("Remove $name") { uninstall(it, pkg) },
                        settings,
                        Action("Keep it") { a -> ActivityLog.clearFlag(a, pkg); a.recreate() }
                    )
                )
            }
        }

        // Remote debugging (ADB)
        val adb = globalInt(ctx, Settings.Global.ADB_ENABLED) == 1 || globalInt(ctx, "adb_wifi_enabled") == 1
        if (adb) {
            out += Finding(
                Level.ACTION, "Remote debugging (ADB) is switched on",
                "This developer setting lets other devices on your network control this one, and hackers look for it. " +
                    "Unless you use it on purpose, switch off ADB / USB debugging in Developer options.",
                listOf(Action("Open Developer options") {
                    it.safeStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                })
            )
        } else {
            out += Finding(Level.GOOD, "Remote debugging is off", "Other devices can't take control through developer tools.")
        }

        // Device admin apps
        val admins = try {
            ctx.getSystemService(DevicePolicyManager::class.java)?.activeAdmins
                ?.map { it.packageName }?.distinct()
                ?.filter { it != ctx.packageName && !AppInfo.isSystem(ctx, it) }
        } catch (_: Exception) {
            null
        } ?: emptyList()
        if (admins.isNotEmpty()) {
            out += Finding(
                Level.WATCH, "Apps with device admin control",
                "These apps can lock or control this device: ${names(ctx, admins)}.\n\n" +
                    "If you don't recognise one, remove its admin access in Security settings, then uninstall it.",
                listOf(Action("Security settings") { it.safeStart(Intent(Settings.ACTION_SECURITY_SETTINGS)) })
            )
        }

        // Accessibility (screen-reading) apps
        val access = try {
            (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':')
                .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
                .distinct()
                .filter { !AppInfo.isSystem(ctx, it) }
        } catch (_: Exception) {
            emptyList()
        }
        if (access.isNotEmpty()) {
            out += Finding(
                Level.WATCH, "Apps that can see the screen",
                "These apps use Accessibility, which lets them read what's on screen and press buttons: ${names(ctx, access)}.\n\n" +
                    "That's normal for helper apps you chose. If you don't recognise one, switch it off.",
                listOf(Action("Accessibility settings") { it.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            )
        }

        // Camera / microphone apps from outside an app store
        val risky = try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it != ctx.packageName && !AppInfo.isSystem(ctx, it) }
                .filter { installerOf(pm, it) !in TRUSTED_STORES }
                .filter {
                    pm.checkPermission(Manifest.permission.CAMERA, it) == PackageManager.PERMISSION_GRANTED ||
                        pm.checkPermission(Manifest.permission.RECORD_AUDIO, it) == PackageManager.PERMISSION_GRANTED
                }
        } catch (_: Exception) {
            emptyList()
        }
        if (risky.isNotEmpty()) {
            out += Finding(
                Level.WATCH, "Apps from outside the app store that can use the camera or microphone",
                "${names(ctx, risky)}.\n\nIf you know and use these, that's fine. If you don't recognise one, remove it.",
                risky.take(6).map { pkg ->
                    val label = AppInfo.label(ctx, pkg)
                    Action("Remove $label") { uninstall(it, pkg) }
                }
            )
        }

        // Box model
        val id = "${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} ${Build.DEVICE}".uppercase(Locale.US)
        val reported = REPORTED_MODELS.firstOrNull { Regex("(^|[^A-Z0-9])$it").containsMatchIn(id) }
        if (reported != null) {
            out += Finding(
                Level.WATCH, "This model appears in public malware reports",
                "Some boxes sold as ${Build.MODEL} (or similar) have been named in reports about hidden malware. " +
                    "That doesn't mean yours is infected. Keep Devil Shield on for a day, then run this check again."
            )
        }

        // Security updates
        val patch = Build.VERSION.SECURITY_PATCH ?: ""
        val date = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)
        } catch (_: Exception) {
            null
        }
        if (date != null) {
            val months = ((System.currentTimeMillis() - date.time) / (30L * 86_400_000L)).toInt()
            if (months > 24) {
                out += Finding(
                    Level.WATCH, "Security updates are old",
                    "The last security update was $patch, about ${months / 12} years ago. Older software has known gaps, " +
                        "so Devil Shield matters more here. Check Settings for a system update.",
                    listOf(Action("Open Settings") { it.safeStart(Intent(Settings.ACTION_SETTINGS)) })
                )
            } else {
                out += Finding(Level.GOOD, "Security updates are fairly recent", "Last security update: $patch.")
            }
        }

        // Official app store
        val hasPlay = AppInfo.isInstalled(ctx, "com.android.vending")
        val hasAmazon = AppInfo.isInstalled(ctx, "com.amazon.venezia")
        if (hasPlay || hasAmazon) {
            val store = if (hasPlay) "Google Play" else "the Amazon Appstore"
            out += Finding(Level.GOOD, "Official app store found", "Apps from $store are checked for malware before you install them.")
        } else {
            out += Finding(
                Level.WATCH, "No official app store on this device",
                "Certified devices come with Google Play, which scans apps for malware. This one doesn't, " +
                    "so only install apps from sources you trust."
            )
        }

        // Private DNS can go around Devil Shield
        if (Build.VERSION.SDK_INT >= 28 && globalString(ctx, "private_dns_mode") == "hostname") {
            val host = globalString(ctx, "private_dns_specifier") ?: "another service"
            out += Finding(
                Level.WATCH, "Private DNS is set",
                "This device sends its lookups to $host, which goes around Devil Shield's checks. " +
                    "For full protection, set Private DNS to Automatic or Off.",
                listOf(Action("Network settings") { it.safeStart(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
            )
        }

        return out.sortedBy { it.level.ordinal.let { o -> 2 - o } }
    }

    private fun uninstall(a: Activity, pkg: String) {
        a.safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    private fun names(ctx: Context, pkgs: List<String>): String {
        val labels = pkgs.take(8).map { AppInfo.label(ctx, it) }
        val more = if (pkgs.size > 8) " and ${pkgs.size - 8} more" else ""
        return labels.joinToString(", ") + more
    }

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else pm.getInstallerPackageName(pkg)
    } catch (_: Exception) {
        null
    }

    private fun globalInt(ctx: Context, key: String): Int = try {
        Settings.Global.getInt(ctx.contentResolver, key, 0)
    } catch (_: Exception) {
        0
    }

    private fun globalString(ctx: Context, key: String): String? = try {
        Settings.Global.getString(ctx.contentResolver, key)
    } catch (_: Exception) {
        null
    }
}
