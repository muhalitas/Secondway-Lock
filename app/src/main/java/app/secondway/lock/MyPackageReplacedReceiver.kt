package app.secondway.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * App update kills running services; restart background monitoring on update.
 */
class MyPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        try {
            InstallMonitor.ensureRunning(context)
        } catch (_: Throwable) {
        }
    }
}
