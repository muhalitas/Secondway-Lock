package app.secondway.lock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo

object NewAppReconciler {

    fun reconcile(context: Context): Int {
        if (!PolicyHelper.isDeviceOwner(context)) return 0
        val alreadyTracked = NewAppLockStore.getTrackedPackages(context)

        val pm = context.packageManager
        val baseline = NewAppLockStore.getOrInitBaselineTimeMillis(context)
        @Suppress("DEPRECATION")
        val packages: List<PackageInfo> = try { pm.getInstalledPackages(0) } catch (_: Throwable) { emptyList() }

        var newlyLocked = 0
        for (pi in packages) {
            val pkg = pi.packageName ?: continue
            if (pkg == context.packageName) continue
            if (pi.firstInstallTime <= baseline) continue
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                continue
            }
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if ((info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue
            if (pkg in alreadyTracked) continue

            NewAppLockStore.setBlocked(context, pkg)
            NewAppLockStore.addNewPackage(context, pkg)
            PolicyHelper.setAppBlocked(context, pkg, true)
            newlyLocked++
        }
        return newlyLocked
    }
}
