package app.getplexo.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.getplexo.mobile.MainActivity
import app.getplexo.mobile.PlexoApp
import app.getplexo.mobile.R
import app.getplexo.mobile.core.DownloadEntry
import app.getplexo.mobile.core.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: DownloadRepository
    private var sawWork = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        repo = (application as PlexoApp).downloads
        createChannel()
        // A foreground service alone does not keep the CPU awake; with the screen off
        // a download can be suspended mid-range. Six hours is a safety valve.
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plexo:download")
            ?.apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        startForeground(
            NOTIFICATION_ID,
            buildNotification(emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        scope.launch {
            repo.entries.collect { entries ->
                if (entries.any { !it.isFinished }) sawWork = true
                if (sawWork && entries.none { !it.isFinished }) {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notify(entries)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    sawWork = true
                    val networks = intent.getStringArrayListExtra(EXTRA_NETWORKS)?.toSet()
                    val streams = intent.getIntExtra(EXTRA_STREAMS, 0).takeIf { it > 0 }
                    repo.start(
                        url = url,
                        name = intent.getStringExtra(EXTRA_NAME),
                        networkIds = networks,
                        streams = streams,
                    )
                }
            }

            ACTION_CANCEL -> {
                repo.cancelAll()
                if (!repo.busy) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(entries: List<DownloadEntry>) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(entries)) }
    }

    private fun buildNotification(entries: List<DownloadEntry>): Notification {
        val active = entries.filter { it.isActive || it.isQueued }
        val running = entries.filter { it.isActive }
        val speed = running.sumOf { it.snapshot.speedBps }
        val first = running.firstOrNull()

        val title = when {
            active.isEmpty() -> "Plexo"
            active.size == 1 -> active.first().snapshot.name.ifBlank { "Downloading" }
            else -> "Downloading ${active.size} files"
        }

        val text = when {
            active.isEmpty() -> "Nothing in the queue"
            speed > 0 -> buildString {
                append(formatRate(speed))
                append(" · ")
                append("${running.size} active")
                if (active.size > running.size) append(", ${active.size - running.size} queued")
            }

            first != null -> "Preparing ${first.snapshot.name}"
            else -> "Waiting for schedule"
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(active.isNotEmpty())
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (active.isNotEmpty()) {
            val total = running.sumOf { it.snapshot.totalBytes }
            val done = running.sumOf { it.snapshot.bytesDone }
            if (total > 0) {
                builder.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            builder.addAction(0, "Cancel all", cancel)
        }

        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for downloads running across your links"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "app.getplexo.mobile.action.START"
        const val ACTION_CANCEL = "app.getplexo.mobile.action.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_NETWORKS = "networks"
        private const val EXTRA_STREAMS = "streams"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 41

        fun startIntent(
            context: Context,
            url: String,
            name: String?,
            networks: Set<String>?,
            streams: Int?,
        ): Intent = Intent(context, DownloadService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_NAME, name)
            networks?.let { putStringArrayListExtra(EXTRA_NETWORKS, ArrayList(it)) }
            putExtra(EXTRA_STREAMS, streams ?: 0)
        }

        fun start(
            context: Context,
            url: String,
            name: String?,
            networks: Set<String>?,
            streams: Int?,
        ) {
            ContextCompat.startForegroundService(
                context,
                startIntent(context, url, name, networks, streams),
            )
        }

        fun cancel(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit++
    }
    return if (unit == 0) {
        "${size.toInt()} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", size, units[unit])
    }
}

private fun formatRate(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"
