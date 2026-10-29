package app.statslop

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.ceil

/**
 * Home-screen widget: current battery, power, RAM and temperature plus 24-hour graphs.
 * It refreshes only when a sample is recorded (the 15-minute job or the open app),
 * so it never wakes the phone on its own.
 */
class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        updateInBackground(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        updateInBackground(context)

    private fun updateInBackground(context: Context) {
        val pending = goAsync()
        thread {
            try {
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatsWidget::class.java))
            if (ids.isEmpty()) return
            val now = System.currentTimeMillis()
            val battery = BatteryReader.read(context)
            val ramPercent = SystemReader.ramUsedPercent(context)
            val history = HistoryDb.get(context).since(now - DAY_MS)
            for (id in ids) {
                val views = render(context, manager.getAppWidgetOptions(id), battery, ramPercent, history, now)
                manager.updateAppWidget(id, views)
            }
        }

        private fun render(
            context: Context,
            options: Bundle,
            battery: BatterySnapshot?,
            ramPercent: Int,
            history: List<Sample>,
            now: Long,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_stats)
            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)

            views.setTextViewText(R.id.widget_battery, battery?.let { "${it.level}%" } ?: "–")
            views.setTextViewText(R.id.widget_power, battery?.powerW?.let {
                String.format(Locale.getDefault(), if (battery.isCharging) "+%.1f W" else "%.1f W", it)
            } ?: "– W")
            views.setTextViewText(R.id.widget_ram, "$ramPercent% RAM")
            views.setTextViewText(R.id.widget_temp, battery?.let { String.format(Locale.getDefault(), "%.0f °C", it.tempC) } ?: "")
            views.setTextViewText(
                R.id.widget_updated,
                "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
            )

            val hasHistory = history.size >= 2 && history.last().ts - history.first().ts >= 30 * 60_000L
            views.setViewVisibility(R.id.widget_charts, if (hasHistory) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_collecting, if (hasHistory) View.GONE else View.VISIBLE)
            if (!hasHistory) return views

            val density = context.resources.displayMetrics.density
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180
            // Header and three labels take about 100 dp; the graphs share the rest.
            val chart = WidgetChart(
                widthPx = ((widthDp - 24) * density).toInt(),
                heightPx = (((heightDp - 100) / 3).coerceAtLeast(16) * density).toInt(),
                dark = context.resources.configuration.isNightModeActive,
            )
            val from = now - DAY_MS

            views.setImageViewBitmap(
                R.id.widget_chart_battery,
                chart.draw(history.map { ChartValue(it.ts, it.level.toFloat(), it.charging) }, from, now, 100f),
            )

            val peakW = history.mapNotNull { it.dischargeW }.maxOrNull()
            val powerMax = ceil(peakW ?: 1f).coerceAtLeast(1f)
            views.setTextViewText(
                R.id.widget_label_power,
                peakW?.let { String.format(Locale.getDefault(), "Power on battery · peak %.1f W", it) } ?: "Power on battery",
            )
            views.setImageViewBitmap(
                R.id.widget_chart_power,
                chart.draw(history.map { ChartValue(it.ts, it.dischargeW, it.charging) }, from, now, powerMax),
            )

            views.setImageViewBitmap(
                R.id.widget_chart_ram,
                chart.draw(history.map { ChartValue(it.ts, it.ramPercent?.toFloat(), it.charging) }, from, now, 100f),
            )
            return views
        }
    }
}

/** A null [value] breaks the line, e.g. power while charging or RAM from older samples. */
private class ChartValue(val ts: Long, val value: Float?, val plugged: Boolean)

private class WidgetChart(val widthPx: Int, val heightPx: Int, val dark: Boolean) {
    private val ink = if (dark) 0xFFF0EFEC.toInt() else 0xFF0B0B0B.toInt()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFF3987E5.toInt() else 0xFF2A78D6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = heightPx / 24f + 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bandPaint = Paint().apply { color = ink; alpha = 20 }
    private val baselinePaint = Paint().apply { color = ink; alpha = 50 }

    /** Line chart from 0 to [yMax] over [fromTs]..[toTs]; plugged-in time is a neutral band. */
    fun draw(values: List<ChartValue>, fromTs: Long, toTs: Long, yMax: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val inset = linePaint.strokeWidth / 2
        fun x(ts: Long) = (ts - fromTs).toFloat() / (toTs - fromTs) * widthPx
        fun y(v: Float) = heightPx - inset - v.coerceIn(0f, yMax) / yMax * (heightPx - 2 * inset)

        values.zipWithNext().forEach { (a, b) ->
            if (a.plugged && b.ts - a.ts <= MAX_SAMPLE_GAP_MS) {
                canvas.drawRect(x(a.ts), 0f, x(b.ts), heightPx.toFloat(), bandPaint)
            }
        }
        canvas.drawLine(0f, heightPx - 1f, widthPx.toFloat(), heightPx - 1f, baselinePaint)

        val path = Path()
        var previous: ChartValue? = null
        for (point in values) {
            val value = point.value
            if (value == null) {
                previous = null
                continue
            }
            val gapTooLong = previous != null && point.ts - previous.ts > MAX_SAMPLE_GAP_MS
            if (previous == null || gapTooLong) path.moveTo(x(point.ts), y(value)) else path.lineTo(x(point.ts), y(value))
            previous = point
        }
        canvas.drawPath(path, linePaint)
        return bitmap
    }
}
