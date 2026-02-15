package app.secondway.lock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper

object PolicyHelper {

    /** MainActivity listeyi yenilesin diye: yeni uygulama kilitlenince çağrılır (main thread). */
    var onNewAppSuspended: (() -> Unit)? = null

    private const val PREFS_NAME = "soft_policy_prefs"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Suppress("UNUSED_PARAMETER")
    fun clearDebuggingRestrictionIfSet(context: Context) {
        // No-op in soft mode.
    }

    fun isFactoryResetRestriction(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROTECTION_ENABLED, false)

    fun setFactoryResetRestriction(context: Context, enable: Boolean): Boolean {
        prefs(context).edit().putBoolean(KEY_PROTECTION_ENABLED, enable).apply()
        return true
    }

    fun setPackageSuspended(context: Context, packageName: String, suspended: Boolean): Boolean =
        setAppBlocked(context, packageName, suspended)

    fun setAppBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        if (packageName == context.packageName && blocked) return false
        val p = prefs(context)
        val current = p.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val changed = if (blocked) current.add(packageName) else current.remove(packageName)
        if (changed) p.edit().putStringSet(KEY_BLOCKED_PACKAGES, current).apply()
        return true
    }

    fun isAppBlocked(context: Context, packageName: String): Boolean =
        prefs(context).getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.contains(packageName) == true

    /**
     * Yeni yüklenen uygulamayı "blocked" listesine ekler.
     * Soft mode: OS-level suspend yok, enforcement Accessibility service tarafından yapılır.
     */
    fun onNewPackageInstalled(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return false
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val ok = try {
            val info = pm.getApplicationInfo(pkg, 0)
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                NewAppLockStore.removeTrackedPackage(appContext, pkg)
                setAppBlocked(appContext, pkg, false)
                return false
            }
            if ((info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                NewAppLockStore.removeTrackedPackage(appContext, pkg)
                setAppBlocked(appContext, pkg, false)
                return false
            }
            NewAppLockStore.setBlocked(appContext, pkg)
            NewAppLockStore.addNewPackage(appContext, pkg)
            setAppBlocked(appContext, pkg, true)
        } catch (_: Exception) {
            false
        }
        Handler(Looper.getMainLooper()).post { onNewAppSuspended?.invoke() }
        return ok
    }
}
