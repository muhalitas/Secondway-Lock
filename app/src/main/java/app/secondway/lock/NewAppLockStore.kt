package app.secondway.lock

import android.content.Context

object NewAppLockStore {

    private const val PREFS_NAME = "new_app_lock_prefs"
    private const val KEY_TRACKED_PACKAGES = "locked_packages" // keep key for backward compatibility
    private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
    private const val KEY_NEW_PACKAGES = "new_packages"
    private const val KEY_BASELINE_TIME_MILLIS = "baseline_time_millis"
    private const val KEY_PENDING_UNLOCK_PREFIX = "pending_unlock_end:"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTrackedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TRACKED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun getNewPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_NEW_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun addNewPackage(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        addTrackedPackage(context, packageName)
        val p = prefs(context)
        val current = p.getStringSet(KEY_NEW_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(packageName)) p.edit().putStringSet(KEY_NEW_PACKAGES, current).apply()
    }

    fun removeNewPackage(context: Context, packageName: String) {
        val p = prefs(context)
        val current = p.getStringSet(KEY_NEW_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(packageName)) p.edit().putStringSet(KEY_NEW_PACKAGES, current).apply()
    }

    fun isAllowed(context: Context, packageName: String): Boolean =
        prefs(context).getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.contains(packageName) == true

    fun setAllowed(context: Context, packageName: String, allowed: Boolean) {
        if (packageName == context.packageName) return
        addTrackedPackage(context, packageName)
        val p = prefs(context)
        val current = p.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val changed = if (allowed) current.add(packageName) else current.remove(packageName)
        if (changed) p.edit().putStringSet(KEY_ALLOWED_PACKAGES, current).apply()
    }

    fun getOrInitBaselineTimeMillis(context: Context): Long {
        val p = prefs(context)
        val existing = p.getLong(KEY_BASELINE_TIME_MILLIS, 0L)
        if (existing > 0L) return existing
        val baseline = try {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        p.edit().putLong(KEY_BASELINE_TIME_MILLIS, baseline).apply()
        return baseline
    }

    fun addTrackedPackage(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        val p = prefs(context)
        val current = p.getStringSet(KEY_TRACKED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(packageName)) {
            p.edit().putStringSet(KEY_TRACKED_PACKAGES, current).apply()
        }
    }

    fun removeTrackedPackage(context: Context, packageName: String) {
        val p = prefs(context)
        val tracked = p.getStringSet(KEY_TRACKED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val allowed = p.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val newPkgs = p.getStringSet(KEY_NEW_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val removedTracked = tracked.remove(packageName)
        val removedAllowed = allowed.remove(packageName)
        val removedNew = newPkgs.remove(packageName)
        val edit = p.edit()
        edit.remove(KEY_PENDING_UNLOCK_PREFIX + packageName)
        if (removedTracked) edit.putStringSet(KEY_TRACKED_PACKAGES, tracked)
        if (removedAllowed) edit.putStringSet(KEY_ALLOWED_PACKAGES, allowed)
        if (removedNew) edit.putStringSet(KEY_NEW_PACKAGES, newPkgs)
        edit.apply()
    }

    fun getPendingUnlockEndMillis(context: Context, packageName: String): Long =
        prefs(context).getLong(KEY_PENDING_UNLOCK_PREFIX + packageName, 0L)

    fun setPendingUnlockEndMillis(context: Context, packageName: String, endTimeMillis: Long) {
        if (packageName == context.packageName) return
        addTrackedPackage(context, packageName)
        setAllowed(context, packageName, true)
        prefs(context).edit().putLong(KEY_PENDING_UNLOCK_PREFIX + packageName, endTimeMillis).apply()
    }

    fun clearPendingUnlock(context: Context, packageName: String) {
        prefs(context).edit().remove(KEY_PENDING_UNLOCK_PREFIX + packageName).apply()
    }

    fun hasAnyActivePendingUnlock(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        for (pkg in getTrackedPackages(context)) {
            val end = getPendingUnlockEndMillis(context, pkg)
            if (end > nowMillis) return true
        }
        return false
    }

    fun setBlocked(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        setAllowed(context, packageName, false)
        clearPendingUnlock(context, packageName)
        addTrackedPackage(context, packageName)
        // caller decides whether it's "newly installed"
    }

    /**
     * When lock duration changes while unlock countdown(s) are active:
     * - Higher duration: extend end = now + newDurationSec
     * - Lower duration: keep existing end (don't shorten)
     */
    fun applyDurationChangeToPendingUnlocks(
        context: Context,
        newDurationSec: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        val coerced = newDurationSec.coerceAtLeast(0)
        if (coerced <= 0) return 0
        var updated = 0
        for (pkg in getTrackedPackages(context)) {
            if (!isAllowed(context, pkg)) continue
            val end = getPendingUnlockEndMillis(context, pkg)
            if (end <= nowMillis) continue
            val remainingSec = ((end - nowMillis) / 1000L).toInt().coerceAtLeast(0)
            if (coerced > remainingSec) {
                val newEnd = nowMillis + coerced * 1000L
                setPendingUnlockEndMillis(context, pkg, newEnd)
                NewAppUnlockScheduler.schedule(context, pkg, newEnd)
                updated++
            }
        }
        return updated
    }

    fun getMaxActivePendingUnlockEndMillis(context: Context, nowMillis: Long = System.currentTimeMillis()): Long {
        var maxEnd = 0L
        for (pkg in getTrackedPackages(context)) {
            val end = getPendingUnlockEndMillis(context, pkg)
            if (end > nowMillis && end > maxEnd) maxEnd = end
        }
        return maxEnd
    }
}
