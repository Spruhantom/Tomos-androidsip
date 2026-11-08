package uk.co.tomos.sip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps the application's single SIP engine alive independently of MainActivity. */
class TomosSipForegroundService : Service() {
    // Holding this reference makes the service lifecycle explicitly retain the same
    // Core instance used by the UI. This service must never create another Core.
    private var engine: TomSipEngine? = null

    override fun onCreate() {
        super.onCreate()
        engine = (application as TomosSipApplication).sipEngine
        createNotificationChannel()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tomos)
            .setContentTitle("TomOS SIP")
            .setContentText("SIP service is running")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SIP service",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps TomOS SIP registered while in the background."
                })
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground service running when the UI task is dismissed.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Do not stop the process-scoped Core when Android tears down this service.
        engine = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tomos_sip_service"
        private const val NOTIFICATION_ID = 1002
    }
}
