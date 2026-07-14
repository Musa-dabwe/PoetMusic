package com.musa.poetmusic.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * Home screen widget entry point. All launcher-driven updates (placement,
 * reboot, resize) funnel into WidgetRenderer, which also receives pushes
 * from the playback refresher and the theme settings routes.
 */
class PoetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.requestUpdate(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        // Pre-API-31 resize path: re-pick compact vs full from the new height.
        WidgetRenderer.requestUpdate(context, intArrayOf(appWidgetId))
    }
}
