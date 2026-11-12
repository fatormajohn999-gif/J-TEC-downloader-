package com.omnidrop.app.plugin

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * AppWidgetProvider for the 2x1 OmniDrop Home Screen Widget.
 * Tapping triggers ClipboardBridgeActivity to auto-paste from clipboard and download.
 */
class OmniDropWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        try {
            val pkg = context.packageName
            val layoutId = context.resources.getIdentifier("omnidrop_widget", "layout", pkg)
            val rootId = context.resources.getIdentifier("widget_root", "id", pkg)

            if (layoutId == 0) return

            val views = RemoteViews(pkg, layoutId)
            val clickIntent = Intent(context, ClipboardBridgeActivity::class.java).apply {
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pendingIntent = PendingIntent.getActivity(context, appWidgetId, clickIntent, flags)

            if (rootId != 0) {
                views.setOnClickPendingIntent(rootId, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (_: Throwable) {}
    }
}
