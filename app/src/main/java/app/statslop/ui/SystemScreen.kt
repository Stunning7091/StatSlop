package app.statslop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.statslop.SystemReader
import app.statslop.SystemSnapshot
import kotlin.math.roundToInt

@Composable
fun SystemScreen(sys: SystemSnapshot?) {
    if (sys == null) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Memory & storage") {
            val ramUsed = sys.ramTotal - sys.ramAvailable
            Meter(
                "RAM", ramUsed, sys.ramTotal,
                "${formatBytes(ramUsed)} used of ${formatBytes(sys.ramTotal)} · ${formatBytes(sys.ramAvailable)} free" +
                    if (sys.ramLow) " · low memory" else "",
            )
            val storageUsed = sys.storageTotal - sys.storageFree
            Meter(
                "Storage", storageUsed, sys.storageTotal,
                "${formatBytes(storageUsed)} used of ${formatBytes(sys.storageTotal)} · ${formatBytes(sys.storageFree)} free",
            )
        }

        SectionCard("CPU") {
            val known = sys.cores.filter { it.curKhz != null }
            if (known.isEmpty()) {
                Text("This device does not let apps read CPU clock speeds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            known.forEach { core ->
                val cur = core.curKhz!!
                val max = core.maxKhz ?: cur
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Core ${core.index}", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (max > 0) cur.toFloat() / max else 0f },
                        modifier = Modifier.weight(1f).height(6.dp),
                        color = seriesColor(),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        gapSize = 2.dp,
                        drawStopIndicator = {},
                    )
                    Text(
                        "${cur / 1000} / ${max / 1000} MHz",
                        Modifier.width(120.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
            Text(
                "Current vs. maximum clock per core. Android does not let apps read CPU load.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Thermal") {
            StatRow("Status", sys.thermalText)
            sys.thermalHeadroom?.let {
                StatRow("Headroom before throttling", "${((1 - it).coerceIn(0f, 1f) * 100).roundToInt()}%")
            }
        }

        SectionCard("Since boot") {
            StatRow("Uptime", formatDuration(sys.elapsedMs))
            StatRow("CPU awake", formatDuration(sys.awakeMs))
            val deepSleep = if (sys.elapsedMs > 0) 100 - sys.awakeMs * 100 / sys.elapsedMs else 0
            StatRow("Deep sleep", "$deepSleep%")
            Text(
                "A low deep-sleep share with the screen mostly off means something keeps the phone awake. " +
                    "Apps > Running in the background shows the usual suspects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Device") {
            StatRow("Model", SystemReader.deviceName)
            StatRow("Chip", SystemReader.socName)
            StatRow("Cores", sys.cores.size.toString())
            StatRow("System", SystemReader.androidVersion)
            StatRow("Kernel", SystemReader.kernel.substringBefore('-'))
        }
    }
}
