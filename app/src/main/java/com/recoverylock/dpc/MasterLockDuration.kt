package com.recoverylock.dpc

import android.content.Context

/**
 * Tek merkez: lock duration + protection-off geri sayımı.
 * Süre değişince (kısa/uzun): geri sayım = şimdi + yeni süre (her zaman yeni süre kadar bekle).
 */
object MasterLockDuration {

    fun getDurationSeconds(context: Context): Int =
        LockHelper.getLockDurationSeconds(context)

    fun getConfiguredDurationSeconds(context: Context): Int =
        LockHelper.getConfiguredLockDurationSeconds(context)

    fun getPendingEndTimeMillis(context: Context): Long =
        LockHelper.getPendingProtectionOffEndTime(context)

    fun getRemainingSeconds(context: Context): Int {
        val end = getPendingEndTimeMillis(context)
        val now = System.currentTimeMillis()
        if (end <= now) return 0
        return ((end - now) / 1000).toInt().coerceAtLeast(0)
    }

    fun isCountdownActive(context: Context): Boolean =
        getPendingEndTimeMillis(context) > System.currentTimeMillis()

    /** Yeni lock duration: geri sayım varken bitiş = şimdi + yeni süre (kısalırsa da uzarsa da). */
    fun applyDurationChange(context: Context, newDurationSec: Int) {
        val now = System.currentTimeMillis()
        val pendingEnd = getPendingEndTimeMillis(context)
        LockHelper.setLockDurationWithPendingRule(context, newDurationSec, pendingEnd, now)
    }

    fun setCountdownEnd(context: Context, endTimeMillis: Long) {
        LockHelper.setPendingProtectionOff(context, endTimeMillis)
    }

    fun clearCountdown(context: Context) {
        LockHelper.clearPendingProtectionOff(context)
    }

    fun getMaxDurationSeconds(): Int = LockHelper.getMaxDurationSeconds()
}
