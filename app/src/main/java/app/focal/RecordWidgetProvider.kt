package app.focal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

/**
 * Static, offline home-screen widget ("Record"): keeps the dark instrument aesthetic across both system themes,
 * carries zero live recording state so crash or process death never leaves a false "Recording" badge on screen, and
 * launches the non-exported [ShortcutActivity] through an immutable [PendingIntent].
 */
class RecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, options))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, newOptions))
    }

    private fun buildViews(context: Context, options: Bundle): RemoteViews {
        val small = singleLayout(context, R.layout.widget_record_small)
        val wide = singleLayout(context, R.layout.widget_record_wide)
        if (Build.VERSION.SDK_INT >= 31) {
            return RemoteViews(
                mapOf(
                    SizeF(SMALL_MIN_DP, HEIGHT_MIN_DP) to small,
                    SizeF(WIDE_MIN_DP, HEIGHT_MIN_DP) to wide,
                ),
            )
        }
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        return if (minWidth >= WIDE_MIN_DP.toInt()) wide else small
    }

    private fun singleLayout(context: Context, layoutId: Int): RemoteViews {
        val intent = Intent(context, ShortcutActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            WIDGET_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteViews(context.packageName, layoutId).apply {
            setOnClickPendingIntent(R.id.widget_root, pending)
        }
    }

    private companion object {
        const val WIDGET_REQUEST_CODE = 301
        const val SMALL_MIN_DP = 40f
        const val WIDE_MIN_DP = 110f
        const val HEIGHT_MIN_DP = 40f
    }
}
