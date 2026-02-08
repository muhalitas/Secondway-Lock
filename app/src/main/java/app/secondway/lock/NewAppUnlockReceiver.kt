package app.secondway.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class NewAppUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNLOCK) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        if (!PolicyHelper.isDeviceOwner(context)) return
        try {
            @Suppress("DEPRECATION")
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
            val info = context.packageManager.getApplicationInfo(pkg, flags)
            if ((info.flags and ApplicationInfo.FLAG_INSTALLED) == 0) {
                NewAppUnlockScheduler.cancel(context, pkg)
                NewAppLockStore.removeTrackedPackage(context, pkg)
                return
            }
        } catch (_: Exception) {
            NewAppUnlockScheduler.cancel(context, pkg)
            NewAppLockStore.removeTrackedPackage(context, pkg)
            return
        }
        val now = System.currentTimeMillis()
        val end = NewAppLockStore.getPendingUnlockEndMillis(context, pkg)
        if (end <= 0L) return
        if (!NewAppLockStore.isAllowed(context, pkg)) {
            NewAppUnlockScheduler.cancel(context, pkg)
            NewAppLockStore.clearPendingUnlock(context, pkg)
            return
        }
        if (now < end) {
            NewAppUnlockScheduler.schedule(context, pkg, end)
            return
        }
        val ok = PolicyHelper.setAppBlocked(context, pkg, false)
        if (ok) {
            NewAppUnlockScheduler.cancel(context, pkg)
            NewAppLockStore.clearPendingUnlock(context, pkg)
            NewAppLockStore.removeNewPackage(context, pkg)
        } else {
            NewAppUnlockScheduler.schedule(context, pkg, now + 60_000L)
        }
    }

    companion object {
        const val ACTION_UNLOCK = "app.secondway.lock.action.UNLOCK_NEW_APP"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
