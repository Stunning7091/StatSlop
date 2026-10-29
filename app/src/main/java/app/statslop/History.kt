package app.statslop

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

data class Sample(
    val ts: Long,
    val level: Int,
    val tempC: Float,
    val currentMa: Int?,
    val charging: Boolean,
)

/** Battery samples recorded by this app; Android keeps no history a normal app can read. */
class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (ts INTEGER PRIMARY KEY, level INTEGER, temp REAL, " +
                "current INTEGER, charging INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(s: BatterySnapshot) {
        if (s.level < 0) return
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO samples VALUES (?, ?, ?, ?, ?)",
            arrayOf(s.timestamp, s.level, s.tempC, s.currentMa, if (s.isPlugged) 1 else 0)
        )
        writableDatabase.delete("samples", "ts < ?", arrayOf((s.timestamp - KEEP_MS).toString()))
    }

    fun since(fromTs: Long): List<Sample> =
        readableDatabase.rawQuery(
            "SELECT ts, level, temp, current, charging FROM samples WHERE ts >= ? ORDER BY ts",
            arrayOf(fromTs.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Sample(
                        ts = c.getLong(0),
                        level = c.getInt(1),
                        tempC = c.getFloat(2),
                        currentMa = if (c.isNull(3)) null else c.getInt(3),
                        charging = c.getInt(4) == 1,
                    )
                )
            }
        }

    fun levelAt(ts: Long): Int? =
        readableDatabase.rawQuery("SELECT level FROM samples WHERE ts = ?", arrayOf(ts.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else null
        }

    /** The most recent sample that was taken while plugged in. */
    fun lastPluggedTs(): Long? =
        readableDatabase.rawQuery("SELECT MAX(ts) FROM samples WHERE charging = 1", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    companion object {
        private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

        @Volatile private var instance: HistoryDb? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: HistoryDb(context.applicationContext).also { instance = it }
        }
    }
}

class SampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BatteryReader.read(applicationContext)?.let { HistoryDb.get(applicationContext).record(it) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "battery-sample",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}

data class DrainStats(
    /** Average %/hour while on battery, over the given samples. */
    val ratePerHour: Float?,
    val hoursOnBattery: Float,
    val sinceUnpluggedTs: Long?,
    val dropSinceUnplugged: Int?,
)

fun drainStats(
    samples: List<Sample>,
    current: BatterySnapshot?,
    lastPluggedTs: Long?,
    levelAtUnplug: Int?,
): DrainStats {
    var drop = 0
    var ms = 0L
    samples.zipWithNext().forEach { (a, b) ->
        val gap = b.ts - a.ts
        // Doze can delay the sampler for hours, so long gaps on battery still count;
        // gaps over 12 h usually mean the phone was off.
        if (!a.charging && !b.charging && gap in 1..(12 * 3_600_000L) && b.level <= a.level) {
            drop += a.level - b.level
            ms += gap
        }
    }
    val hours = ms / 3_600_000f
    val rate = if (hours >= 0.5f) drop / hours else null

    val unplugged = if (current != null && !current.isPlugged && lastPluggedTs != null) lastPluggedTs else null
    return DrainStats(
        ratePerHour = rate,
        hoursOnBattery = hours,
        sinceUnpluggedTs = unplugged,
        dropSinceUnplugged = if (unplugged != null && levelAtUnplug != null) levelAtUnplug - current!!.level else null,
    )
}
