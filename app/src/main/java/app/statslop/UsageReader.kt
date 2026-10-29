package app.statslop

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.time.LocalDate
import java.time.ZoneId

data class AppUsage(val packageName: String, val label: String, val icon: Bitmap?, val durationMs: Long)

data class UsageSummary(
    val apps: List<AppUsage>,
    /** Apps that kept a foreground service running (music, navigation, VPN, sync, ...). */
    val backgroundServices: List<AppUsage>,
    val screenOnMs: Long,
    val unlocks: Int,
)

object UsageReader {
    private val iconCache = HashMap<String, Bitmap?>()

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
        val bootTime = end - SystemClock.elapsedRealtime()
        // The screen can't have been on before the last boot.
        val screenStart = maxOf(start, bootTime)

        val apps = usm.queryAndAggregateUsageStats(start, end).values
            .filter { it.totalTimeVisible >= 60_000 }
            .map { appUsage(pm, it.packageName, it.totalTimeVisible) }
            .sortedByDescending { it.durationMs }
        val backgroundServices = foregroundServiceTimes(usm, start, end, bootTime)
            .filterValues { it >= 60_000 }
            .map { (packageName, ms) -> appUsage(pm, packageName, ms) }
            .sortedByDescending { it.durationMs }

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

        return UsageSummary(apps, backgroundServices, screenOnMs, unlocks)
    }

    private fun appUsage(pm: PackageManager, packageName: String, durationMs: Long): AppUsage {
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
        // Loading an app icon decodes full-size images; keep one small copy per app instead.
        val icon = synchronized(iconCache) {
            iconCache.getOrPut(packageName) { info?.loadIcon(pm)?.toBitmap(96, 96) }
        }
        return AppUsage(packageName, info?.loadLabel(pm)?.toString() ?: packageName, icon, durationMs)
    }

    /**
     * How long each app had at least one foreground service running. Overlapping services
     * of one app count once, and a device shutdown ends every running service.
     */
    private fun foregroundServiceTimes(
        usm: UsageStatsManager,
        start: Long,
        end: Long,
        bootTime: Long,
    ): Map<String, Long> {
        val totals = HashMap<String, Long>()
        val runningServices = HashMap<String, MutableSet<String>>()
        val runningSince = HashMap<String, Long>()
        val seenPackages = HashSet<String>()
        var sawStartup = false
        fun add(packageName: String, ms: Long) {
            if (ms > 0) totals.merge(packageName, ms, Long::plus)
        }

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            val packageName = e.packageName
            val service = e.className ?: ""
            when (e.eventType) {
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    runningServices.forEach { (pkg, services) ->
                        if (services.isNotEmpty()) add(pkg, e.timeStamp - runningSince.getValue(pkg))
                    }
                    runningServices.clear()
                }
                // Without a shutdown event (e.g. the battery died) the end time is unknown: drop them.
                UsageEvents.Event.DEVICE_STARTUP -> {
                    runningServices.clear()
                    sawStartup = true
                }
                UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                    seenPackages += packageName
                    val services = runningServices.getOrPut(packageName) { mutableSetOf() }
                    if (services.isEmpty()) runningSince[packageName] = e.timeStamp
                    services += service
                }
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> {
                    val services = runningServices[packageName]
                    if (services != null && service in services) {
                        services -= service
                        if (services.isEmpty()) add(packageName, e.timeStamp - runningSince.getValue(packageName))
                    } else if (packageName !in seenPackages && !sawStartup) {
                        // Its start lies before the range: it was already running when the range began.
                        add(packageName, e.timeStamp - start)
                    }
                    seenPackages += packageName
                }
            }
        }
        runningServices.forEach { (packageName, services) ->
            // A start before the last boot means the shutdown event was missed; it isn't running now.
            if (services.isNotEmpty() && runningSince.getValue(packageName) >= bootTime) {
                add(packageName, end - runningSince.getValue(packageName))
            }
        }
        return totals
    }
}
