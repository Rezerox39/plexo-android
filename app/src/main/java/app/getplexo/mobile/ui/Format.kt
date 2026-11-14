package app.getplexo.mobile.ui

import app.getplexo.mobile.core.SpeedUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit++
    }
    return if (unit == 0) {
        "${size.toInt()} B"
    } else {
        String.format(Locale.US, "%.1f %s", size, units[unit])
    }
}

fun formatRate(bytesPerSecond: Long, unit: SpeedUnit = SpeedUnit.MEGABYTES): String {
    if (bytesPerSecond <= 0) return if (unit == SpeedUnit.MEGABITS) "0 Mbps" else "0 B/s"
    if (unit == SpeedUnit.MEGABITS) {
        val bits = bytesPerSecond * 8.0
        return when {
            bits >= 1_000_000_000 -> String.format(Locale.US, "%.2f Gbps", bits / 1_000_000_000)
            bits >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bits / 1_000_000)
            bits >= 1_000 -> String.format(Locale.US, "%.0f Kbps", bits / 1_000)
            else -> "${bits.toInt()} bps"
        }
    }
    return "${formatBytes(bytesPerSecond)}/s"
}

/** "12.3 MB/s" -> "12.3" and "MB/s", so the number can use the display face. */
fun speedNumber(bytesPerSecond: Long, unit: SpeedUnit): String {
    if (unit == SpeedUnit.MEGABITS) {
        val bits = bytesPerSecond * 8.0
        return when {
            bits >= 1_000_000_000 -> String.format(Locale.US, "%.2f", bits / 1_000_000_000)
            bits >= 1_000_000 -> String.format(Locale.US, "%.1f", bits / 1_000_000)
            bits >= 1_000 -> String.format(Locale.US, "%.0f", bits / 1_000)
            else -> bits.toInt().toString()
        }
    }
    return formatBytes(bytesPerSecond).substringBeforeLast(' ')
}

fun speedSuffix(bytesPerSecond: Long, unit: SpeedUnit): String {
    if (unit == SpeedUnit.MEGABITS) {
        val bits = bytesPerSecond * 8.0
        return when {
            bits >= 1_000_000_000 -> "Gbps"
            bits >= 1_000_000 -> "Mbps"
            bits >= 1_000 -> "Kbps"
            else -> "bps"
        }
    }
    return formatBytes(bytesPerSecond).substringAfterLast(' ', "B") + "/s"
}

fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> "0s"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

fun formatLimit(mbPerSecond: Int, unit: SpeedUnit): String {
    if (mbPerSecond <= 0) return "Unlimited"
    return if (unit == SpeedUnit.MEGABITS) {
        String.format(Locale.US, "%.0f Mbps", mbPerSecond * 8.388608)
    } else {
        "$mbPerSecond MB/s"
    }
}

fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))

fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

fun weekdayShort(index: Int): String = arrayOf("M", "T", "W", "T", "F", "S", "S")[index]
