package app.statslop

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs

data class BatterySnapshot(
    val timestamp: Long,
    val level: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val tempC: Float,
    val voltageMv: Int,
    /** Absolute current in mA; direction follows [isCharging]. */
    val currentMa: Int?,
    val chargeCounterMah: Int?,
    val cycleCount: Int?,
    val technology: String?,
    val chargeTimeRemainingMs: Long?,
    val designCapacityMah: Int?,
) {
    val isCharging get() = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val isPlugged get() = plugged != 0

    val powerW: Float? get() = currentMa?.let { it * voltageMv / 1_000_000f }

    /** Full capacity estimated from the fuel gauge's charge counter. */
    val estimatedFullMah: Int?
        get() = chargeCounterMah?.takeIf { level >= 15 }?.let { it * 100 / level }

    val statusText get() = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    val pluggedText get() = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        else -> "On battery"
    }

    val healthText get() = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }
}

object BatteryReader {
    private var designCapacity: Int? = null
    private var designCapacityLoaded = false

    fun read(context: Context): BatterySnapshot? {
        // ACTION_BATTERY_CHANGED is sticky: a null receiver just returns the last value.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val bm = context.getSystemService(BatteryManager::class.java)

        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val level = if (rawLevel >= 0 && scale > 0) rawLevel * 100 / scale else -1

        val cycles = if (Build.VERSION.SDK_INT >= 34) {
            intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
        } else null

        val remaining = bm.computeChargeTimeRemaining().takeIf { it > 0 }

        return BatterySnapshot(
            timestamp = System.currentTimeMillis(),
            level = level,
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1),
            tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            currentMa = readCurrentMa(bm),
            chargeCounterMah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .takeIf { it > 0 && it != Int.MIN_VALUE }?.let { it / 1000 },
            cycleCount = cycles,
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            chargeTimeRemainingMs = remaining,
            designCapacityMah = designCapacity(context),
        )
    }

    private fun readCurrentMa(bm: BatteryManager): Int? {
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return null
        // The API says microamps, but some kernels report milliamps. A real phone
        // never draws under 5 mA while this app is running, so small values are mA.
        val ma = if (abs(raw) < 5_000) abs(raw) else abs(raw) / 1000
        return ma
    }

    /** Design capacity from the framework power profile, if the device exposes it. */
    private fun designCapacity(context: Context): Int? {
        if (designCapacityLoaded) return designCapacity
        designCapacityLoaded = true
        designCapacity = runCatching {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val profile = cls.getConstructor(Context::class.java).newInstance(context)
            val mah = cls.getMethod("getBatteryCapacity").invoke(profile) as Double
            mah.toInt().takeIf { it > 500 }
        }.getOrNull()
        return designCapacity
    }
}
