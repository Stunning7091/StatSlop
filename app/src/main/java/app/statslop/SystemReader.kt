package app.statslop

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.io.File

data class CoreInfo(val index: Int, val curKhz: Long?, val maxKhz: Long?)

data class SystemSnapshot(
    val ramTotal: Long,
    val ramAvailable: Long,
    val ramLow: Boolean,
    val storageTotal: Long,
    val storageFree: Long,
    val cores: List<CoreInfo>,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
    val elapsedMs: Long,
    val awakeMs: Long,
) {
    val thermalText get() = when (thermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting down"
        else -> "Unknown"
    }
}

object SystemReader {
    private var lastHeadroom: Float? = null
    private var lastHeadroomAt = 0L

    fun ramUsedPercent(context: Context): Int {
        val mem = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mem)
        return ((mem.totalMem - mem.availMem) * 100 / mem.totalMem).toInt()
    }

    fun read(context: Context): SystemSnapshot {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val fs = StatFs(Environment.getDataDirectory().path)
        val pm = context.getSystemService(PowerManager::class.java)

        return SystemSnapshot(
            ramTotal = mem.totalMem,
            ramAvailable = mem.availMem,
            ramLow = mem.lowMemory,
            storageTotal = fs.totalBytes,
            storageFree = fs.availableBytes,
            cores = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
                val dir = "/sys/devices/system/cpu/cpu$i/cpufreq"
                CoreInfo(i, readLong("$dir/scaling_cur_freq"), readLong("$dir/cpuinfo_max_freq"))
            },
            thermalStatus = pm.currentThermalStatus,
            thermalHeadroom = headroom(pm),
            elapsedMs = SystemClock.elapsedRealtime(),
            // uptimeMillis stops while the CPU is in deep sleep.
            awakeMs = SystemClock.uptimeMillis(),
        )
    }

    // getThermalHeadroom returns NaN when polled more than about once a second.
    private fun headroom(pm: PowerManager): Float? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadroomAt >= 1_500) {
            lastHeadroomAt = now
            lastHeadroom = pm.getThermalHeadroom(10).takeIf { !it.isNaN() }
        }
        return lastHeadroom
    }

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    val deviceName: String get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    val socName: String get() = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
    val androidVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val kernel: String get() = System.getProperty("os.version") ?: "?"
}
