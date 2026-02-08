package app.secondway.lock

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

object InstallMonitor {

    fun ensureRunning(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, InstallMonitorService::class.java)
            .setAction(InstallMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
    }
}

