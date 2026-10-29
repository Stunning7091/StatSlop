package app.statslop.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.statslop.AppUsage
import app.statslop.UsageSummary

val usageRanges = listOf("Today" to 0, "Last 7 days" to 6)

@Composable
fun AppsScreen(
    hasPermission: Boolean,
    usage: UsageSummary?,
    daysBack: Int,
    onRangeChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    if (!hasPermission) {
        SectionCard("Screen time per app") {
            Text(
                "Android shows apps how long each app was on screen once you allow usage access. " +
                    "StatSlop reads it on the phone only and sends nothing anywhere.",
            )
            Button(onClick = {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                    .recoverCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }) { Text("Allow usage access") }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            usageRanges.forEachIndexed { i, (label, days) ->
                SegmentedButton(
                    selected = daysBack == days,
                    onClick = { onRangeChange(days) },
                    shape = SegmentedButtonDefaults.itemShape(i, usageRanges.size),
                ) { Text(label) }
            }
        }
        if (usage == null) return@Column

        SectionCard {
            StatRow("Screen on", formatDuration(usage.screenOnMs))
            StatRow("Unlocks", usage.unlocks.toString())
        }

        SectionCard("Apps by screen time") {
            if (usage.apps.isEmpty()) {
                Text("No app was used for more than a minute yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val top = usage.apps.firstOrNull()?.foregroundMs ?: 1
            usage.apps.forEach { AppRow(it, top) }
        }
    }
}

@Composable
private fun AppRow(app: AppUsage, topMs: Long) {
    val bitmap = remember(app.packageName) { app.icon?.toBitmap(96, 96)?.asImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) Image(bitmap, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDuration(app.foregroundMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { app.foregroundMs.toFloat() / topMs },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = seriesColor(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 2.dp,
                drawStopIndicator = {},
            )
        }
    }
}
