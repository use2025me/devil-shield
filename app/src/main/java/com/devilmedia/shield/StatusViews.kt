package com.devilmedia.shield

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Keeps the quick switch and the home-screen widget showing the right status. */
object StatusViews {
    fun refresh(ctx: Context) {
        try {
            TileService.requestListeningState(ctx, ComponentName(ctx, ShieldTileService::class.java))
        } catch (_: Exception) {
        }
        ShieldWidget.refresh(ctx)
    }
}

/** The Devil Shield switch in the phone's pull-down quick settings. */
class ShieldTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        try {
            if (ShieldVpnService.running) {
                // With a family PIN set, turning off happens in the app so the PIN can be asked.
                if (Prefs(this).pin.isNotEmpty()) {
                    openApp(false)
                    return
                }
                ShieldVpnService.stop(this)
            } else {
                if (VpnService.prepare(this) != null || otherVpnActive()) {
                    openApp(true)
                    return
                }
                ShieldVpnService.start(this)
            }
        } catch (_: Exception) {
            openApp(!ShieldVpnService.running)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ update() }, 800)
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = ShieldVpnService.running
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "Protected" else "Off"
        tile.updateTile()
    }

    private fun openApp(start: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_START, start)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Home-screen widget: a shield and "Protected" or "Protection off". Tap to open. */
class ShieldWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        fun refresh(ctx: Context) {
            try {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(ctx, ShieldWidget::class.java))
                if (ids.isNotEmpty()) render(ctx, manager, ids)
            } catch (_: Exception) {
            }
        }

        private fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val on = ShieldVpnService.running
            val views = RemoteViews(ctx.packageName, R.layout.widget_shield)
            views.setTextViewText(R.id.widget_status, if (on) "Protected" else "Protection off")
            views.setTextColor(R.id.widget_status, if (on) Brand.GREEN else Brand.MUTED)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            manager.updateAppWidget(ids, views)
        }
    }
}
