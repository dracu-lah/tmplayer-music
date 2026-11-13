package com.tmplayer.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * The launcher's side of the now playing widget. [onUpdate] comes when a widget is placed and at
 * boot; [onAppWidgetOptionsChanged] when it is resized, which may switch the layout. The widget
 * asks for no periodic updates (`updatePeriodMillis` is 0): everything else is [WidgetSync].
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        NowPlayingWidget.refresh(context, goAsync(), appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        NowPlayingWidget.refresh(context, goAsync(), intArrayOf(appWidgetId))
    }
}
