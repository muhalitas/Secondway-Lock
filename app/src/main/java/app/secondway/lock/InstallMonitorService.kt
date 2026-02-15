package app.secondway.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps a lightweight listener alive so newly installed apps can be suspended
 * without opening the UI.
 *
 * Note: Foreground service is required on many devices/OEMs; it needs a small
 * persistent notification (Android 8+).
 */
class InstallMonitorService : Service() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != Intent.ACTION_PACKAGE_ADDED) return
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            executor.execute {
                // Retry a bit: package manager can lag briefly after the broadcast.
                var ok = false
                for (attempt in 1..8) {
                    ok = PolicyHelper.onNewPackageInstalled(context.applicationContext, pkg)
                    if (ok) break
                    try {
                        Thread.sleep(250L * attempt)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                if (!ok) {
                    NewAppLockStore.setBlocked(context.applicationContext, pkg)
                    NewAppLockStore.addNewPackage(context.applicationContext, pkg)
                    NewAppBlockRetryScheduler.schedule(context.applicationContext, pkg, 1)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Keep service alive on OEMs that aggressively kill background work.
        try {
            startInForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            // If notifications are blocked (Android 13+), we can't run as FGS.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(
                this,
                packageReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        // Fallback sweep in case we missed broadcasts while the service was down.
        executor.execute { NewAppReconciler.reconcile(applicationContext) }

        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(packageReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            // Some channel settings are only applied at creation time, but sound/vibration/etc can be updated.
            existing.description = getString(R.string.monitor_channel_description)
            existing.setShowBadge(false)
            existing.setSound(null, null)
            existing.enableVibration(false)
            existing.lockscreenVisibility = Notification.VISIBILITY_SECRET
            nm.createNotificationChannel(existing)
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_MIN
        )
        channel.description = getString(R.string.monitor_channel_description)
        channel.setShowBadge(false)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_text))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "InstallMonitorService"
        const val ACTION_START = "app.secondway.lock.action.MONITOR_START"
        const val ACTION_STOP = "app.secondway.lock.action.MONITOR_STOP"

        private const val CHANNEL_ID = "install_monitor"
        private const val NOTIFICATION_ID = 1001
    }
}
