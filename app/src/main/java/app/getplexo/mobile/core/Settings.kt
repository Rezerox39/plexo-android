package app.getplexo.mobile.core

import android.content.Context

class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("plexo_settings", Context.MODE_PRIVATE)

    /** Concurrent connections per link. More streams help on high-latency links. */
    var streamsPerLink: Int
        get() = prefs.getInt("streams", 4).coerceIn(1, 8)
        set(value) = prefs.edit().putInt("streams", value.coerceIn(1, 8)).apply()

    /** Upper bound for each byte range. The engine sizes ranges per link below this. */
    var chunkMb: Int
        get() = prefs.getInt("chunk_mb", 8).coerceIn(1, 64)
        set(value) = prefs.edit().putInt("chunk_mb", value.coerceIn(1, 64)).apply()
}
