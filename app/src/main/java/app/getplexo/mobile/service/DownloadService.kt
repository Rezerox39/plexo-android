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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.getplexo.mobile.MainActivity
import app.getplexo.mobile.PlexoApp
import app.getplexo.mobile.R
import app.getplexo.mobile.core.DownloadRepository
import app.getplexo.mobile.core.DownloadSnapshot
import app.getplexo.mobile.core.DownloadStatus
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

    override fun onCreate() {
        super.onCreate()
        repo = (application as PlexoApp).downloads
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(DownloadSnapshot.idle(), "Preparing download"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        scope.launch {
            repo.state.collect { snapshot ->
                if (snapshot == null) return@collect
                if (!snapshot.status.finished) sawWork = true
                notify(snapshot)
                // Only tear the service down for a download this service actually ran,
                // never for a result left over from a previous session.
                if (sawWork && snapshot.status.finished) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank() && !repo.busy) {
                    sawWork = true
                    repo.start(
                        url = url,
                        name = intent.getStringExtra(EXTRA_NAME),
                        streams = intent.getIntExtra(EXTRA_STREAMS, 3),
                        chunkSize = intent.getLongExtra(EXTRA_CHUNK, 8L * 1024 * 1024),
                    )
                }
            }

            ACTION_CANCEL -> {
                repo.cancel()
                if (!repo.busy) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        repo.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(snapshot: DownloadSnapshot) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(snapshot, title(snapshot)))
        }
    }

    private fun title(snapshot: DownloadSnapshot): String = when (snapshot.status) {
        DownloadStatus.PREPARING -> "Preparing download"
        DownloadStatus.RUNNING -> snapshot.name.ifBlank { "Downloading" }
        DownloadStatus.SAVING -> "Saving ${snapshot.name}"
        DownloadStatus.DONE -> "${snapshot.name} saved"
        DownloadStatus.ERROR -> "Download failed"
        DownloadStatus.CANCELLED -> "Download cancelled"
    }

    private fun buildNotification(snapshot: DownloadSnapshot, title: String): Notification {
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

        val text = when (snapshot.status) {
            DownloadStatus.DONE -> snapshot.savedTo ?: "Saved to Downloads"
            DownloadStatus.ERROR -> snapshot.message ?: "Something went wrong"
            DownloadStatus.CANCELLED -> "Cancelled"
            DownloadStatus.SAVING -> "Writing to Downloads/Plexo"
            else -> buildString {
                append(formatBytes(snapshot.bytesDone))
                if (snapshot.totalBytes > 0) append(" of ").append(formatBytes(snapshot.totalBytes))
                if (snapshot.speedBps > 0) append(" · ").append(formatRate(snapshot.speedBps))
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!snapshot.status.finished)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!snapshot.status.finished) {
            if (snapshot.totalBytes > 0) {
                builder.setProgress(1000, (snapshot.fraction * 1000).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            builder.addAction(0, "Cancel", cancel)
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
        private const val EXTRA_STREAMS = "streams"
        private const val EXTRA_CHUNK = "chunk"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 41

        fun startIntent(context: Context, url: String, name: String?, streams: Int, chunkMb: Int): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_STREAMS, streams)
                putExtra(EXTRA_CHUNK, chunkMb.toLong() * 1024L * 1024L)
            }

        fun start(context: Context, url: String, name: String?, streams: Int, chunkMb: Int) {
            ContextCompat.startForegroundService(context, startIntent(context, url, name, streams, chunkMb))
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
