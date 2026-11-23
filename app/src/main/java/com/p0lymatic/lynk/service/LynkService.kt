package com.p0lymatic.lynk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.p0lymatic.lynk.R
import com.p0lymatic.lynk.util.plural

/**
 * Keeps the process alive while at least one SSH session, forward or terminal is in use:
 * the process is what holds the JSch sessions, so it must outlive the UI.
 */
class LynkService : Service() {

    private var active = 0

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        active = intent?.getIntExtra(EXTRA_ACTIVE, active) ?: active
        if (active <= 0) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_STICKY
        }
        ensureChannel(this)
        val notification = buildNotification(active)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(count: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lynk)
            .setContentTitle(TITLE)
            .setContentText(connectionText(count))
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

    private fun contentIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.MainActivity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "lynk_state"
        private const val CHANNEL_NAME = "Lynk — соединения"
        private const val TITLE = "Lynk активен"
        private const val NOTIF_ID = 41
        private const val EXTRA_ACTIVE = "active"

        /**
         * Starts or stops the foreground service so it matches the live connection count.
         * Returns true when the service is (being) started.
         */
        fun sync(context: Context, active: Int): Boolean {
            val intent = Intent(context, LynkService::class.java)
                .putExtra(EXTRA_ACTIVE, active.coerceAtLeast(0))
            return if (active > 0) {
                runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
            } else {
                runCatching { context.stopService(intent) }
                false
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }

        private fun connectionText(count: Int): String =
            if (count <= 0) "нет активных соединений"
            else "$count ${plural(count, "соединение", "соединения", "соединений")}"
    }
}
