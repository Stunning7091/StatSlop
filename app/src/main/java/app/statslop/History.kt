package app.statslop

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.concurrent.thread

/** Doze can delay samples by hours; a longer gap usually means the phone was off. */
const val MAX_SAMPLE_GAP_MS = 12 * 3_600_000L

data class Sample(
    val ts: Long,
    val level: Int,
    val tempC: Float,
    val currentMa: Int?,
    val charging: Boolean,
    /** Null for samples recorded before version 10.2026.3. */
    val voltageMv: Int?,
    val ramPercent: Int?,
) {
    /** Power drawn from the battery; null while plugged in or when unknown. */
    val dischargeW: Float?
        get() = if (charging || currentMa == null || voltageMv == null) null else currentMa * voltageMv / 1_000_000f
}

/** Battery samples recorded by this app; Android keeps no history a normal app can read. */
class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "history.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (ts INTEGER PRIMARY KEY, level INTEGER, temp REAL, " +
                "current INTEGER, charging INTEGER, voltage INTEGER, ram INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE samples ADD COLUMN voltage INTEGER")
            db.execSQL("ALTER TABLE samples ADD COLUMN ram INTEGER")
        }
    }

    fun record(s: BatterySnapshot, ramPercent: Int) {
        if (s.level < 0) return
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO samples (ts, level, temp, current, charging, voltage, ram) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(s.timestamp, s.level, s.tempC, s.currentMa, if (s.isPlugged) 1 else 0, s.voltageMv, ramPercent)
        )
        writableDatabase.delete("samples", "ts < ?", arrayOf((s.timestamp - KEEP_MS).toString()))
    }

    fun since(fromTs: Long): List<Sample> =
        readableDatabase.rawQuery(
            "SELECT ts, level, temp, current, charging, voltage, ram FROM samples WHERE ts >= ? ORDER BY ts",
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
                        voltageMv = if (c.isNull(5)) null else c.getInt(5),
                        ramPercent = if (c.isNull(6)) null else c.getInt(6),
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

/** Records one battery sample every 15 minutes via the platform JobScheduler. */
class SampleJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            BatteryReader.read(this)?.let { HistoryDb.get(this).record(it, SystemReader.ramUsedPercent(this)) }
            StatsWidget.updateAll(this)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, SampleJob::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build()
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
        if (!a.charging && !b.charging && gap in 1..MAX_SAMPLE_GAP_MS && b.level <= a.level) {
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
