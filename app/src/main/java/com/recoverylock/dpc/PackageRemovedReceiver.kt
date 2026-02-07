package com.recoverylock.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Intent.ACTION_PACKAGE_REMOVED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        NewAppUnlockScheduler.cancel(context.applicationContext, pkg)
        NewAppLockStore.removeTrackedPackage(context.applicationContext, pkg)
        Handler(Looper.getMainLooper()).post { PolicyHelper.onNewAppSuspended?.invoke() }
    }
}
