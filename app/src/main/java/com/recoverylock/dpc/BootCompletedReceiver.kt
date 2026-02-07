package com.recoverylock.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PolicyHelper.isDeviceOwner(context)) return

        // Keep background install monitoring alive after reboot.
        try {
            InstallMonitor.ensureRunning(context)
        } catch (_: Throwable) {
        }

        val now = System.currentTimeMillis()
        for (pkg in NewAppLockStore.getTrackedPackages(context)) {
            val end = NewAppLockStore.getPendingUnlockEndMillis(context, pkg)
            if (end > now) {
                NewAppUnlockScheduler.schedule(context, pkg, end)
            } else if (!NewAppLockStore.isAllowed(context, pkg)) {
                NewAppBlockRetryScheduler.schedule(context, pkg, 1)
            }
        }
    }
}
