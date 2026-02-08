package app.secondway.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager

object PolicyHelper {

    /** MainActivity listeyi yenilesin diye: yeni uygulama suspend edildikten sonra çağrılır (main thread). */
    var onNewAppSuspended: (() -> Unit)? = null

    private const val DISALLOW_FACTORY_RESET = UserManager.DISALLOW_FACTORY_RESET
    private const val DISALLOW_DEBUGGING_FEATURES = UserManager.DISALLOW_DEBUGGING_FEATURES

    /** One-time cleanup: remove ADB block restriction if it was set by an older app version. */
    fun clearDebuggingRestrictionIfSet(context: Context) {
        val pair = getDpmAndAdmin(context) ?: return
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        if (um.hasUserRestriction(DISALLOW_DEBUGGING_FEATURES)) {
            val (dpm, admin) = pair
            dpm.clearUserRestriction(admin, DISALLOW_DEBUGGING_FEATURES)
        }
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    private fun getDpmAndAdmin(context: Context): Pair<DevicePolicyManager, ComponentName>? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return null
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        return Pair(dpm, admin)
    }

    fun isFactoryResetRestriction(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        return um.hasUserRestriction(DISALLOW_FACTORY_RESET)
    }

    fun setFactoryResetRestriction(context: Context, enable: Boolean): Boolean {
        val pair = getDpmAndAdmin(context) ?: return false
        val (dpm, admin) = pair
        if (enable) dpm.addUserRestriction(admin, DISALLOW_FACTORY_RESET)
        else dpm.clearUserRestriction(admin, DISALLOW_FACTORY_RESET)
        return true
    }

    /** Tek paket suspend/unsuspend. Device Owner ise çalışır. */
    fun setPackageSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        if (!isDeviceOwner(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val pair = getDpmAndAdmin(context) ?: return false
        val (dpm, admin) = pair
        val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), suspended) ?: emptyArray()
        return failed.isEmpty()
    }

    /**
     * New-app lock primitive.
     * Prefer suspend (keeps app visible in launcher but blocks launching).
     */
    fun setAppBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        val pair = getDpmAndAdmin(context) ?: return false
        val (dpm, admin) = pair
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        // Migration: older builds used "hidden". Some OEMs don't clear it while suspended, so:
        // un-suspend (still hidden => not launchable), unhide, then suspend again.
        val wasHidden = try { dpm.isApplicationHidden(admin, packageName) } catch (_: Throwable) { false }
        if (blocked && wasHidden) {
            try {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
            } catch (_: Throwable) {
            }
            try {
                dpm.setApplicationHidden(admin, packageName, false)
            } catch (_: Throwable) {
            }
        } else if (!blocked) {
            try {
                dpm.setApplicationHidden(admin, packageName, false)
            } catch (_: Throwable) {
            }
        }

        val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), blocked) ?: emptyArray()
        return failed.isEmpty()
    }

    fun isAppBlocked(context: Context, packageName: String): Boolean {
        val pair = getDpmAndAdmin(context) ?: return false
        val (dpm, admin) = pair
        val pm = context.packageManager
        val suspended = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val flags =
                    PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.GET_META_DATA
                val info = pm.getApplicationInfo(packageName, flags)
                (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        val hidden = try {
            dpm.isApplicationHidden(admin, packageName)
        } catch (_: Throwable) {
            false
        }
        return suspended || hidden
    }

    /**
     * Yeni yüklenen uygulama bilgisini alır, suspend eder (kapalı yazar), listeyi yeniletir.
     * Tek giriş noktası: PACKAGE_ADDED receiver sadece bunu çağırır.
     */
    fun onNewPackageInstalled(context: Context, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (!isDeviceOwner(context)) return false
        if (pkg == context.packageName) return false
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        NewAppLockStore.setBlocked(appContext, pkg)
        NewAppLockStore.addNewPackage(appContext, pkg)
        val ok = try {
            val info = pm.getApplicationInfo(pkg, 0)
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                NewAppLockStore.removeTrackedPackage(appContext, pkg)
                return false
            }
            setAppBlocked(appContext, pkg, true)
        } catch (_: Exception) {
            false
        }
        Handler(Looper.getMainLooper()).post { onNewAppSuspended?.invoke() }
        return ok
    }
}
