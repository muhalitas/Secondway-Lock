package com.recoverylock.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NewAppBlockRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RETRY_BLOCK) return
        if (!PolicyHelper.isDeviceOwner(context)) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 1).coerceIn(1, 8)

        // If it's already blocked or no longer tracked, stop retrying.
        if (pkg !in NewAppLockStore.getTrackedPackages(context)) return
        if (NewAppLockStore.isAllowed(context, pkg)) return
        if (PolicyHelper.isAppBlocked(context, pkg)) return

        val ok = PolicyHelper.onNewPackageInstalled(context, pkg)
        if (ok || PolicyHelper.isAppBlocked(context, pkg)) return

        if (attempt < 8) NewAppBlockRetryScheduler.schedule(context, pkg, attempt + 1)
    }

    companion object {
        const val ACTION_RETRY_BLOCK = "com.recoverylock.dpc.action.RETRY_BLOCK_NEW_APP"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ATTEMPT = "attempt"
    }
}
