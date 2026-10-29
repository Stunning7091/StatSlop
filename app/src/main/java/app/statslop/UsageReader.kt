package app.statslop

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.time.LocalDate
import java.time.ZoneId

data class AppUsage(val packageName: String, val label: String, val icon: Drawable?, val foregroundMs: Long)

data class UsageSummary(
    val apps: List<AppUsage>,
    val screenOnMs: Long,
    val unlocks: Int,
)

object UsageReader {
    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** Usage from midnight [daysBack] days ago until now. */
    fun read(context: Context, daysBack: Int): UsageSummary {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val pm = context.packageManager
        val start = LocalDate.now().minusDays(daysBack.toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        // The screen can't have been on before the last boot.
        val screenStart = maxOf(start, end - SystemClock.elapsedRealtime())

        val apps = usm.queryAndAggregateUsageStats(start, end).values
            .filter { it.totalTimeVisible >= 60_000 }
            .map { stats ->
                val info = runCatching { pm.getApplicationInfo(stats.packageName, 0) }.getOrNull()
                AppUsage(
                    packageName = stats.packageName,
                    label = info?.loadLabel(pm)?.toString() ?: stats.packageName,
                    icon = info?.loadIcon(pm),
                    foregroundMs = stats.totalTimeVisible,
                )
            }
            .sortedByDescending { it.foregroundMs }

        var screenOnMs = 0L
        var unlocks = 0
        var onSince: Long? = null
        var sawScreenEvent = false
        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    sawScreenEvent = true
                    onSince = e.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // The first event being "off" means the screen was on at the start of the range.
                    screenOnMs += e.timeStamp - (onSince ?: if (!sawScreenEvent) screenStart else e.timeStamp)
                    sawScreenEvent = true
                    onSince = null
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks++
            }
        }
        onSince?.let { screenOnMs += end - it }
        // No screen events yet (e.g. just after boot): on since boot or start of range.
        if (!sawScreenEvent && context.getSystemService(PowerManager::class.java).isInteractive) {
            screenOnMs += end - screenStart
        }

        return UsageSummary(apps, screenOnMs, unlocks)
    }
}
