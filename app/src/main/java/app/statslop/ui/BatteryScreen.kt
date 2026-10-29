package app.statslop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.statslop.BatterySnapshot
import app.statslop.DrainStats
import app.statslop.Sample
import java.util.Locale

val historyRanges = listOf("24 h" to 1, "3 days" to 3, "7 days" to 7)

@Composable
fun BatteryScreen(
    battery: BatterySnapshot?,
    history: List<Sample>,
    rangeDays: Int,
    onRangeChange: (Int) -> Unit,
    drain: DrainStats,
) {
    if (battery == null) {
        Text("Battery information is not available.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { battery.level / 100f },
                        modifier = Modifier.size(104.dp),
                        color = seriesColor(),
                        strokeWidth = 10.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        gapSize = 2.dp,
                    )
                    Text("${battery.level}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(battery.statusText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(battery.pluggedText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    battery.currentMa?.let { ma ->
                        val sign = if (battery.isCharging) "+" else "−"
                        val watts = battery.powerW?.let { String.format(Locale.getDefault(), " · %.1f W", it) } ?: ""
                        Text("$sign$ma mA$watts", style = MaterialTheme.typography.titleMedium)
                    }
                    battery.chargeTimeRemainingMs?.let {
                        Text("Full in ${formatDuration(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!battery.isPlugged && drain.ratePerHour != null && drain.ratePerHour > 0.05f) {
                        val left = (battery.level / drain.ratePerHour * 3_600_000).toLong()
                        Text("About ${formatDuration(left)} left", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SectionCard("Battery level") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                historyRanges.forEachIndexed { i, (label, days) ->
                    SegmentedButton(
                        selected = rangeDays == days,
                        onClick = { onRangeChange(days) },
                        shape = SegmentedButtonDefaults.itemShape(i, historyRanges.size),
                    ) { Text(label) }
                }
            }
            val now = System.currentTimeMillis()
            val from = now - rangeDays * 86_400_000L
            if (history.size < 2) {
                Text(
                    "History builds up as StatSlop records a sample every 15 minutes. Check back in an hour.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TimeChart(
                    points = history.map { ChartPoint(it.ts, it.level.toFloat(), it.charging) },
                    fromTs = from, toTs = now, yMin = 0f, yMax = 100f,
                    format = { "${it.toInt()}%" },
                    bandLabel = "Plugged in",
                )
            }
            drain.ratePerHour?.let {
                StatRow("Average drain on battery", String.format(Locale.getDefault(), "%.1f %%/h", it))
            }
            if (drain.sinceUnpluggedTs != null && drain.dropSinceUnplugged != null) {
                StatRow(
                    "Since unplugged (${formatDuration(System.currentTimeMillis() - drain.sinceUnpluggedTs)})",
                    "−${drain.dropSinceUnplugged}%",
                )
            }
        }

        if (history.size >= 2) {
            SectionCard("Temperature") {
                val now = System.currentTimeMillis()
                val temps = history.map { it.tempC }
                val lo = (temps.min() - 2).let { kotlin.math.floor(it / 5) * 5 }
                val hi = (temps.max() + 2).let { kotlin.math.ceil(it / 5) * 5 }
                TimeChart(
                    points = history.map { ChartPoint(it.ts, it.tempC, it.charging) },
                    fromTs = now - rangeDays * 86_400_000L, toTs = now, yMin = lo, yMax = hi,
                    format = { String.format(Locale.getDefault(), "%.0f °C", it) },
                    bandLabel = "Plugged in",
                )
            }
        }

        SectionCard("Details") {
            StatRow("Health", battery.healthText)
            StatRow("Temperature", String.format(Locale.getDefault(), "%.1f °C", battery.tempC))
            StatRow("Voltage", String.format(Locale.getDefault(), "%.2f V", battery.voltageMv / 1000f))
            battery.cycleCount?.let { StatRow("Charge cycles", it.toString()) }
            battery.chargeCounterMah?.let { StatRow("Charge remaining", "$it mAh") }
            battery.estimatedFullMah?.let { StatRow("Estimated full capacity", "$it mAh") }
            battery.designCapacityMah?.let { design ->
                StatRow("Design capacity", "$design mAh")
                battery.estimatedFullMah?.let { est ->
                    StatRow("Estimated wear level", "${(est * 100 / design).coerceAtMost(100)}%")
                }
            }
            battery.technology?.let { StatRow("Technology", it) }
        }
    }
}
