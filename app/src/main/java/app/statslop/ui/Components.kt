package app.statslop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Series blue from the chart palette, stepped for each theme. */
@Composable
fun seriesColor() = if (isSystemInDarkTheme()) Color(0xFF3987E5) else Color(0xFF2A78D6)

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Meter(label: String, used: Long, total: Long, detail: String) {
    val fraction = if (total > 0) used.toFloat() / total else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${(fraction * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = seriesColor(),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            gapSize = 2.dp,
            drawStopIndicator = {},
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class ChartPoint(val ts: Long, val value: Float, val highlighted: Boolean)

/**
 * Line chart over time. [highlighted] points (e.g. plugged in) get a tinted band behind them.
 * Touch and drag to read off a value.
 */
@Composable
fun TimeChart(
    points: List<ChartPoint>,
    fromTs: Long,
    toTs: Long,
    yMin: Float,
    yMax: Float,
    format: (Float) -> String,
    bandLabel: String? = null,
) {
    val line = seriesColor()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val band = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var touchX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }

    val selected = touchX?.let { x ->
        val ts = fromTs + ((x / widthPx).coerceIn(0f, 1f) * (toTs - fromTs)).toLong()
        points.minByOrNull { kotlin.math.abs(it.ts - ts) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            selected?.let { "${format(it.value)} · ${timeLabel(it.ts, toTs - fromTs)}" } ?: "Touch the chart to read a value",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Row {
            Canvas(
                Modifier
                    .weight(1f)
                    .height(160.dp)
                    .pointerInput(points) {
                        detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease() })
                    }
                    .pointerInput(points) {
                        detectDragGestures(
                            onDragStart = { touchX = it.x },
                            onDrag = { change, _ -> touchX = change.position.x },
                        )
                    }
            ) {
                widthPx = size.width
                val span = (toTs - fromTs).coerceAtLeast(1)
                fun x(ts: Long) = (ts - fromTs).toFloat() / span * size.width
                fun y(v: Float) = size.height - (v - yMin) / (yMax - yMin) * size.height

                for (i in 0..2) {
                    val gy = size.height * i / 2f
                    drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                }

                // Bands: each highlighted point covers until the next sample.
                points.zipWithNext().forEach { (a, b) ->
                    if (a.highlighted && b.ts - a.ts <= MAX_GAP_MS) {
                        drawRect(band, Offset(x(a.ts), 0f), Size(x(b.ts) - x(a.ts), size.height))
                    }
                }

                val path = Path()
                var prev: ChartPoint? = null
                points.forEach { p ->
                    val px = x(p.ts)
                    val py = y(p.value.coerceIn(yMin, yMax))
                    if (prev == null || p.ts - prev!!.ts > MAX_GAP_MS) path.moveTo(px, py) else path.lineTo(px, py)
                    prev = p
                }
                drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                selected?.let { p ->
                    val px = x(p.ts)
                    drawLine(muted, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1f)
                    val center = Offset(px, y(p.value.coerceIn(yMin, yMax)))
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
                    drawCircle(line, radius = 4.dp.toPx(), center = center)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(yMax, (yMax + yMin) / 2, yMin).forEach {
                    Text(format(it), style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(timeLabel(fromTs, toTs - fromTs), style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.weight(1f))
            if (bandLabel != null) {
                Canvas(Modifier.padding(top = 3.dp).width(12.dp).height(10.dp)) {
                    drawRoundRect(band, cornerRadius = CornerRadius(2.dp.toPx()))
                }
                Spacer(Modifier.width(4.dp))
                Text(bandLabel, style = MaterialTheme.typography.labelSmall, color = muted)
                Spacer(Modifier.weight(1f))
            }
            Text("now", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

// Doze can delay samples by hours; only a longer gap (phone off) breaks the line.
private const val MAX_GAP_MS = 12 * 3_600_000L

private fun timeLabel(ts: Long, spanMs: Long): String {
    val style = if (spanMs > 26 * 3_600_000L) {
        java.text.SimpleDateFormat("EEE HH:mm", Locale.getDefault())
    } else {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    }
    return style.format(Date(ts))
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h >= 24 -> "${h / 24}d ${h % 24}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) String.format(Locale.getDefault(), "%.1f GB", gb)
    else String.format(Locale.getDefault(), "%.0f MB", bytes / 1_048_576.0)
}
