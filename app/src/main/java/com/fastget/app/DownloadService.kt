package com.fastget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps downloads alive in the background and shows "N downloading • speed" in the notification. */
class DownloadService : Service() {

    companion object {
        private const val CH_PROGRESS = "fg_progress"
        private const val CH_DONE = "fg_done"

        fun ensure(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watching = HashSet<Long>()
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        ServiceCompat.startForeground(
            this, 1, progressNotification("Starting…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (!running) {
            running = true
            handler.post(tick)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun update() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = Engine.items.filter { it.status == Engine.DOWNLOADING }
        for (a in active) watching.add(a.id)

        val iter = watching.iterator()
        while (iter.hasNext()) {
            val id = iter.next()
            val item = Engine.items.firstOrNull { it.id == id }
            if (item == null) {
                iter.remove()
                continue
            }
            when (item.status) {
                Engine.COMPLETED -> {
                    doneNotification(nm, item, true)
                    iter.remove()
                }
                Engine.FAILED -> {
                    doneNotification(nm, item, false)
                    iter.remove()
                }
                Engine.PAUSED -> iter.remove()
            }
        }

        if (active.isEmpty()) {
            running = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        var speed = 0L
        var done = 0L
        var total = 0L
        for (a in active) {
            speed += a.speed
            if (a.total > 0) {
                done += a.downloaded()
                total += a.total
            }
        }
        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
        val text = "${active.size} downloading • ${Util.formatSpeed(speed)}"
        nm.notify(1, progressNotification(text, pct, total <= 0))
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun progressNotification(text: String, pct: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("FastGet")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(contentIntent())
            .build()

    private fun doneNotification(nm: NotificationManager, item: DlItem, ok: Boolean) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(item.fileName)
            .setContentText(if (ok) "Download complete • ${Util.formatSize(item.total)}" else "Failed: ${item.error ?: "error"}")
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        nm.notify((item.id % 100000).toInt() + 2000, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
