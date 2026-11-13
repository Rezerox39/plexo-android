package app.getplexo.mobile.core

import android.content.Context

class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("plexo_settings", Context.MODE_PRIVATE)

    /** Concurrent connections per link. More streams help on high-latency links. */
    var streamsPerLink: Int
        get() = prefs.getInt("streams", 3).coerceIn(1, 8)
        set(value) = prefs.edit().putInt("streams", value.coerceIn(1, 8)).apply()

    /** Size of each byte range. Small chunks balance better, large ones add less overhead. */
    var chunkMb: Int
        get() = prefs.getInt("chunk_mb", 8).coerceIn(1, 64)
        set(value) = prefs.edit().putInt("chunk_mb", value.coerceIn(1, 64)).apply()
}
