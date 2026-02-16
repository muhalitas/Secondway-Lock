package app.secondway.lock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

object NewAppReconciler {

    fun reconcile(context: Context): Int {
        val alreadyTracked = NewAppLockStore.getTrackedPackages(context)

        val pm = context.packageManager
        val baseline = NewAppLockStore.getOrInitBaselineTimeMillis(context)
        val launcherPkgs = getLauncherPackages(pm)

        var newlyLocked = 0
        for (pkg in launcherPkgs) {
            if (pkg == context.packageName) continue

            val pi: PackageInfo = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            } catch (_: Throwable) {
                continue
            }
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

    private fun getLauncherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val resolveInfo = try {
            pm.queryIntentActivities(intent, flags)
        } catch (_: Exception) {
            emptyList<ResolveInfo>()
        }
        return resolveInfo.mapNotNull { it.activityInfo?.packageName }.toSet()
    }
}
