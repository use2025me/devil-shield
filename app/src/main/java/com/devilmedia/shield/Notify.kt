package com.devilmedia.shield

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Gentle notices: new-app checks and the weekly summary. Never alarming. */
object Notify {
    private const val CH_APPS = "apps"
    private const val CH_SUMMARY = "summary"
    private const val CH_WIFI = "wifi"
    private const val CH_REMOVAL = "removal"
    const val ID_SUMMARY = 1

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_APPS, "New app checks", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUMMARY, "Weekly summary", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WIFI, "Wi-Fi safety", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMOVAL, "Data removal reminders", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun newApp(ctx: Context, pkg: String, title: String, text: String) =
        show(ctx, 1000 + (pkg.hashCode() and 0xFFFF), CH_APPS, title, text, HealthActivity::class.java)

    fun wifi(ctx: Context, text: String) =
        show(ctx, 2, CH_WIFI, "Open Wi-Fi", text, MainActivity::class.java)

    fun removal(ctx: Context, text: String) =
        show(ctx, 3, CH_REMOVAL, "Remove my data", text, RemoveDataActivity::class.java)

    fun summary(ctx: Context, text: String) =
        show(ctx, ID_SUMMARY, CH_SUMMARY, "Devil Shield this week", text, MainActivity::class.java)

    private fun show(ctx: Context, id: Int, channel: String, title: String, text: String, target: Class<*>) {
        if (!canNotify(ctx)) return
        try {
            channels(ctx)
            val pi = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (_: Exception) {
        }
    }
}

/** Looks at an app the moment it's installed and gently flags it if it needs a look. */
object NewAppCheck {
    private val TRUSTED_STORES = setOf(
        "com.android.vending", "com.amazon.venezia",
        "com.sec.android.app.samsungapps", "com.huawei.appmarket"
    )

    // Strong abilities, in plain words.
    private val STRONG = linkedMapOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to "read the screen and press buttons",
        "android.permission.BIND_DEVICE_ADMIN" to "control the device",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to "read your notifications",
        "android.permission.SYSTEM_ALERT_WINDOW" to "show things on top of other apps",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "install other apps",
        "android.permission.READ_SMS" to "read text messages",
        "android.permission.RECEIVE_SMS" to "read text messages",
        "android.permission.CAMERA" to "use the camera",
        "android.permission.RECORD_AUDIO" to "use the microphone"
    )

    /** The strong abilities an app asks for, in plain words (empty if none or from an app store). */
    @Suppress("DEPRECATION")
    fun abilities(ctx: Context, pkg: String): List<String> {
        if (pkg == ctx.packageName || AppInfo.isSystem(ctx, pkg)) return emptyList()
        val pm = ctx.packageManager
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
            else pm.getInstallerPackageName(pkg)
        } catch (_: Exception) {
            null
        }
        if (installer in TRUSTED_STORES) return emptyList()
        val info = try {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        } catch (_: Exception) {
            return emptyList()
        }
        val asked = HashSet<String>()
        info.requestedPermissions?.let { asked.addAll(it) }
        info.services?.forEach { s -> s.permission?.let { asked.add(it) } }
        return STRONG.filterKeys { it in asked }.values.distinct()
    }

    fun check(ctx: Context, pkg: String) {
        val found = abilities(ctx, pkg)
        if (found.isEmpty()) return
        val p = Prefs(ctx)
        p.newAppFlags = p.newAppFlags + pkg
        val name = AppInfo.label(ctx, pkg)
        Notify.newApp(
            ctx, pkg,
            "New app checked: $name",
            "$name came from outside an app store and can ${found.joinToString(", ")}. " +
                "If you know it, that's fine. If not, tap to take a look."
        )
    }
}

/** A short, calm weekly note so people can see Devil Shield working. */
object WeeklySummary {
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    fun maybeSend(ctx: Context) {
        val p = Prefs(ctx)
        val now = System.currentTimeMillis()
        val total = ActivityLog.threatsBlocked
        if (p.summaryAt == 0L) {
            p.summaryAt = now
            p.summaryBase = total
            return
        }
        if (now - p.summaryAt < WEEK) return
        val n = (total - p.summaryBase).coerceAtLeast(0)
        p.summaryAt = now
        p.summaryBase = total
        if (!p.weeklySummary) return
        val text = when (n) {
            0 -> "Nothing needed blocking this week. All quiet, and you're protected."
            1 -> "Devil Shield blocked 1 threat this week. All good, you don't need to do anything."
            else -> "Devil Shield blocked $n threats this week. All good, you don't need to do anything."
        }
        Notify.summary(ctx, text)
    }
}
