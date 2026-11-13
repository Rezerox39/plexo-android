package app.getplexo.mobile.core

import android.content.Context

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    BLACK("black");

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DARK
    }
}

class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("plexo_settings", Context.MODE_PRIVATE)

    /** Concurrent connections per link. More streams help on high-latency links. */
    var streamsPerLink: Int
        get() = prefs.getInt("streams", 8).coerceIn(1, 16)
        set(value) = prefs.edit().putInt("streams", value.coerceIn(1, 16)).apply()

    /** Upper bound for each byte range. The engine sizes ranges per link below this. */
    var chunkMb: Int
        get() = prefs.getInt("chunk_mb", 8).coerceIn(1, 64)
        set(value) = prefs.edit().putInt("chunk_mb", value.coerceIn(1, 64)).apply()

    /** Total download cap in MB/s. 0 means unlimited. */
    var speedLimitMbps: Int
        get() = prefs.getInt("speed_limit_mbps", 0).coerceIn(0, 500)
        set(value) = prefs.edit().putInt("speed_limit_mbps", value.coerceIn(0, 500)).apply()

    var theme: ThemeMode
        get() = ThemeMode.from(prefs.getString("theme", ThemeMode.DARK.key))
        set(value) = prefs.edit().putString("theme", value.key).apply()

    /** Wait for a dropped link to come back instead of failing the download. */
    var autoRetry: Boolean
        get() = prefs.getBoolean("auto_retry", true)
        set(value) = prefs.edit().putBoolean("auto_retry", value).apply()
}
