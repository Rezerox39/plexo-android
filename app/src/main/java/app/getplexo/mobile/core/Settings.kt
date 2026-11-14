package app.getplexo.mobile.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    BLACK("black", "Pure black");

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** Desktop's preference: decimal MB/s or decimal megabits per second. */
enum class SpeedUnit(val key: String) {
    MEGABYTES("mbs"),
    MEGABITS("mbps");

    companion object {
        fun from(key: String?): SpeedUnit = entries.firstOrNull { it.key == key } ?: MEGABYTES
    }
}

enum class DataPeriod(val key: String, val label: String) {
    DAY("day", "Day"),
    WEEK("week", "Week"),
    MONTH("month", "Month");

    companion object {
        fun from(key: String?): DataPeriod = entries.firstOrNull { it.key == key } ?: MONTH
    }
}

/** Desktop schedule: start/end minute of day, selected weekdays, optional end date. */
data class Schedule(
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val days: Int,
    val endEpochDay: Long,
) {
    fun daySelected(day: DayOfWeek): Boolean = days and (1 shl (day.value - 1)) != 0

    fun isOpen(now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        if (!enabled) return true
        if (endEpochDay > 0 && now.toLocalDate().toEpochDay() > endEpochDay) return false
        if (startMinute == endMinute) return true
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek
        if (startMinute < endMinute) {
            return daySelected(today) && minute in startMinute until endMinute
        }
        // Window crosses midnight: the evening belongs to today, the morning to
        // the previous day's selection.
        val morningOwner = today.minus(1)
        return (daySelected(today) && minute >= startMinute) ||
            (daySelected(morningOwner) && minute < endMinute)
    }

    companion object {
        const val EVERY_DAY = 0b1111111
    }
}

class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("plexo_settings", Context.MODE_PRIVATE)

    init {
        // 0.4.1 and older tuned small adaptive ranges. 0.4.2 switches to one large
        // equal range per stream (Via's model), so an old 8 MB ceiling would put
        // the request count right back where it caused throttling.
        if (!prefs.contains("chunk_mb_v2")) {
            val old = prefs.getInt("chunk_mb", 8)
            val migrated = if (old <= 8) 256 else old.coerceIn(16, 256)
            prefs.edit().putInt("chunk_mb_v2", migrated).apply()
        }
    }

    // --- engine -------------------------------------------------------------

    /** Concurrent connections per link. More streams help on high-latency links. */
    var streamsPerLink: Int
        get() = prefs.getInt("streams", 8).coerceIn(1, 32)
        set(value) = prefs.edit().putInt("streams", value.coerceIn(1, 32)).apply()

    /**
     * Upper bound for one byte range. The parallel pass splits the file into one
     * equal range per stream, capped here; smaller values mean more requests.
     */
    var chunkMb: Int
        get() = prefs.getInt("chunk_mb_v2", 256).coerceIn(16, 256)
        set(value) = prefs.edit().putInt("chunk_mb_v2", value.coerceIn(16, 256)).apply()

    /** Total download cap in MB/s. 0 means unlimited. */
    var speedLimitMbps: Int
        get() = prefs.getInt("speed_limit_mbps", 0).coerceIn(0, 1000)
        set(value) = prefs.edit().putInt("speed_limit_mbps", value.coerceIn(0, 1000)).apply()

    /** Desktop Slow mode: one switch replaces the limit with a gentle default. */
    var slowMode: Boolean
        get() = prefs.getBoolean("slow_mode", false)
        set(value) = prefs.edit().putBoolean("slow_mode", value).apply()

    var slowModeMbps: Int
        get() = prefs.getInt("slow_mode_mbps", 2).coerceIn(1, 100)
        set(value) = prefs.edit().putInt("slow_mode_mbps", value.coerceIn(1, 100)).apply()

    /** Bytes per second, or 0 for unlimited; Slow mode wins when it is on. */
    val effectiveLimitBytes: Long
        get() {
            val mb = if (slowMode) slowModeMbps else speedLimitMbps
            return mb.toLong() * 1024L * 1024L
        }

    /** Desktop default is two files at once; Android keeps the same range. */
    var downloadsAtOnce: Int
        get() = prefs.getInt("downloads_at_once", 2).coerceIn(1, 4)
        set(value) = prefs.edit().putInt("downloads_at_once", value.coerceIn(1, 4)).apply()

    /** Wait for a dropped link to come back instead of failing the download. */
    var autoRetry: Boolean
        get() = prefs.getBoolean("auto_retry", true)
        set(value) = prefs.edit().putBoolean("auto_retry", value).apply()

    // --- appearance ---------------------------------------------------------

    private val _theme = MutableStateFlow(ThemeMode.from(prefs.getString("theme", ThemeMode.DARK.key)))
    val themeFlow: StateFlow<ThemeMode> = _theme.asStateFlow()

    var theme: ThemeMode
        get() = _theme.value
        set(value) {
            _theme.value = value
            prefs.edit().putString("theme", value.key).apply()
        }

    var speedUnit: SpeedUnit
        get() = SpeedUnit.from(prefs.getString("speed_unit", SpeedUnit.MEGABYTES.key))
        set(value) = prefs.edit().putString("speed_unit", value.key).apply()

    // --- schedule -----------------------------------------------------------

    val schedule: Schedule
        get() = Schedule(
            enabled = prefs.getBoolean("schedule_enabled", false),
            startMinute = prefs.getInt("schedule_start", 0).coerceIn(0, 1439),
            endMinute = prefs.getInt("schedule_end", 7 * 60).coerceIn(0, 1439),
            days = prefs.getInt("schedule_days", Schedule.EVERY_DAY),
            endEpochDay = prefs.getLong("schedule_end_date", 0L),
        )

    fun saveSchedule(schedule: Schedule) {
        prefs.edit()
            .putBoolean("schedule_enabled", schedule.enabled)
            .putInt("schedule_start", schedule.startMinute.coerceIn(0, 1439))
            .putInt("schedule_end", schedule.endMinute.coerceIn(0, 1439))
            .putInt("schedule_days", schedule.days and Schedule.EVERY_DAY)
            .putLong("schedule_end_date", schedule.endEpochDay)
            .apply()
    }

    // --- per-network controls ----------------------------------------------

    /** A link's own MB/s cap; 0 means the total limit (or unlimited) applies. */
    fun linkSpeedLimitMbps(id: String): Int =
        prefs.getInt("link_speed_${id}", 0).coerceIn(0, 1000)

    fun setLinkSpeedLimitMbps(id: String, value: Int) {
        prefs.edit().putInt("link_speed_$id", value.coerceIn(0, 1000)).apply()
    }

    /** Streams for one link: 0 follows the global choice, else 4/8/16/32. */
    fun linkStreams(id: String, fallback: Int): Int {
        val chosen = prefs.getInt("link_streams_$id", 0)
        return if (chosen <= 0) fallback else chosen.coerceIn(1, 32)
    }

    fun setLinkStreams(id: String, value: Int) {
        prefs.edit().putInt("link_streams_$id", value.coerceIn(0, 32)).apply()
    }

    /** Raw per-link choice: 0 means Auto (follow the global default). */
    fun linkStreamsOverride(id: String): Int = prefs.getInt("link_streams_$id", 0).coerceIn(0, 32)

    fun linkDataLimitMb(id: String): Int = prefs.getInt("link_data_${id}", 0).coerceIn(0, 1_000_000)

    fun setLinkDataLimitMb(id: String, value: Int) {
        prefs.edit().putInt("link_data_$id", value.coerceIn(0, 1_000_000)).apply()
    }

    fun linkDataPeriod(id: String): DataPeriod =
        DataPeriod.from(prefs.getString("link_period_$id", DataPeriod.MONTH.key))

    fun setLinkDataPeriod(id: String, period: DataPeriod) {
        prefs.edit().putString("link_period_$id", period.key).apply()
    }

    /** Bytes Plexo itself has carried over a link in the current period. */
    fun linkDataUsed(id: String, period: DataPeriod, now: LocalDate = LocalDate.now()): Long {
        val storedPeriod = prefs.getString("link_used_period_$id", null)
        if (storedPeriod != period.key) return 0L
        val reset = prefs.getLong("link_used_reset_$id", 0L)
        val boundary = periodStart(period, now).toEpochDay()
        return if (reset != boundary) 0L else prefs.getLong("link_used_$id", 0L)
    }

    fun addLinkDataUsed(id: String, period: DataPeriod, bytes: Long, now: LocalDate = LocalDate.now()) {
        if (bytes <= 0) return
        val boundary = periodStart(period, now).toEpochDay()
        val storedPeriod = prefs.getString("link_used_period_$id", null)
        val reset = prefs.getLong("link_used_reset_$id", 0L)
        val base = if (storedPeriod == period.key && reset == boundary) {
            prefs.getLong("link_used_$id", 0L)
        } else {
            0L
        }
        prefs.edit()
            .putString("link_used_period_$id", period.key)
            .putLong("link_used_reset_$id", boundary)
            .putLong("link_used_$id", base + bytes)
            .apply()
    }

    fun linkDataBlocked(id: String, now: LocalDate = LocalDate.now()): Boolean {
        val capMb = linkDataLimitMb(id)
        if (capMb <= 0) return false
        return linkDataUsed(id, linkDataPeriod(id), now) >= capMb.toLong() * 1024L * 1024L
    }

    private fun periodStart(period: DataPeriod, now: LocalDate): LocalDate = when (period) {
        DataPeriod.DAY -> now
        DataPeriod.WEEK -> now.minusDays((now.dayOfWeek.value - 1).toLong())
        DataPeriod.MONTH -> now.withDayOfMonth(1)
    }

    // --- tiny helpers -------------------------------------------------------

    fun scheduleLabel(): String {
        val s = schedule
        if (!s.enabled) return "Off"
        return "%02d:%02d – %02d:%02d".format(s.startMinute / 60, s.startMinute % 60, s.endMinute / 60, s.endMinute % 60)
    }
}
