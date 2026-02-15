import android.content.Context

object LockHelper {

    private const val PREFS_NAME = "lock_prefs"
    private const val KEY_WELCOME_SHOWN = "welcome_shown"
    private const val KEY_ONBOARDING_STEP = "onboarding_step"
    private const val KEY_LOCK_DURATION_SECONDS = "lock_duration_seconds"
    private const val KEY_LOCK_DURATION_PENDING_SECONDS = "lock_duration_pending_seconds"
    private const val KEY_PENDING_PROTECTION_OFF_END = "pending_protection_off_end"
    private const val KEY_DURATION_DISPLAY_DEFER_UNTIL = "duration_display_defer_until"
    private const val KEY_BATTERY_OPT_CARD_DISMISSED = "battery_opt_card_dismissed"
    private const val MAX_DURATION = 86400 // 1 day

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isWelcomeShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WELCOME_SHOWN, false)

    fun setWelcomeShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_WELCOME_SHOWN, true).apply()
    }

    fun getOnboardingStep(context: Context): Int =
        prefs(context).getInt(KEY_ONBOARDING_STEP, 0)

    fun setOnboardingStep(context: Context, step: Int) {
        prefs(context).edit().putInt(KEY_ONBOARDING_STEP, step.coerceAtLeast(0)).apply()
    }

    fun clearOnboardingStep(context: Context) {
        prefs(context).edit().remove(KEY_ONBOARDING_STEP).apply()
    }

    fun getLockDurationSeconds(context: Context): Int {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val deferUntil = p.getLong(KEY_DURATION_DISPLAY_DEFER_UNTIL, 0L)
        if (deferUntil > 0L && now >= deferUntil) {
            val pending = p.getInt(KEY_LOCK_DURATION_PENDING_SECONDS, -1)
            if (pending >= 0) {
                p.edit()
                    .putInt(KEY_LOCK_DURATION_SECONDS, pending.coerceIn(0, MAX_DURATION))
                    .remove(KEY_LOCK_DURATION_PENDING_SECONDS)
                    .remove(KEY_DURATION_DISPLAY_DEFER_UNTIL)
                    .apply()
            } else {
                p.edit().remove(KEY_DURATION_DISPLAY_DEFER_UNTIL).apply()
            }
        }
        val v = p.getInt(KEY_LOCK_DURATION_SECONDS, 0)
        return v.coerceIn(0, MAX_DURATION)
    }

    /** What the user last selected (may be pending while a decrease is deferred). */
    fun getConfiguredLockDurationSeconds(context: Context): Int {
        val p = prefs(context)
        val pending = p.getInt(KEY_LOCK_DURATION_PENDING_SECONDS, -1)
        if (pending >= 0) return pending.coerceIn(0, MAX_DURATION)
        return p.getInt(KEY_LOCK_DURATION_SECONDS, 0).coerceIn(0, MAX_DURATION)
    }

    fun setLockDurationSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(KEY_LOCK_DURATION_SECONDS, seconds.coerceIn(0, MAX_DURATION))
            .apply()
    }

    fun getDurationDisplayDeferUntilMillis(context: Context): Long =
        prefs(context).getLong(KEY_DURATION_DISPLAY_DEFER_UNTIL, 0L)

    fun setDurationDisplayDeferUntilMillis(context: Context, endTimeMillis: Long) {
        prefs(context).edit().putLong(KEY_DURATION_DISPLAY_DEFER_UNTIL, endTimeMillis).apply()
    }

    fun clearDurationDisplayDeferUntil(context: Context) {
        prefs(context).edit().remove(KEY_DURATION_DISPLAY_DEFER_UNTIL).apply()
    }

    fun cancelDeferredLockDurationChange(context: Context) {
        prefs(context).edit()
            .remove(KEY_LOCK_DURATION_PENDING_SECONDS)
            .remove(KEY_DURATION_DISPLAY_DEFER_UNTIL)
            .apply()
    }

    fun isBatteryOptCardDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_OPT_CARD_DISMISSED, false)

    fun setBatteryOptCardDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_OPT_CARD_DISMISSED, dismissed).apply()
    }

    /**
     * Yeni lock duration kaydeder.
     * Geri sayım varken:
     * - Yeni süre daha YÜKSEK ise: bitiş = şimdi + yeni süre (anında uzat).
     * - Yeni süre daha DÜŞÜK ise: mevcut bitişi KISALTMA (mevcut geri sayım bitince yeni süre devreye girer).
     */
    fun setLockDurationWithPendingRule(
        context: Context,
        newDurationSec: Int,
        pendingEndTimeMillis: Long,
        nowMillis: Long
    ) {
        val coerced = newDurationSec.coerceIn(0, MAX_DURATION)
        val p = prefs(context)
        val currentEffective = p.getInt(KEY_LOCK_DURATION_SECONDS, 0).coerceIn(0, MAX_DURATION)
        val edit = p.edit()

        if (coerced >= currentEffective) {
            // Increase (or equal): apply immediately, clear any pending-decrease deferral.
            edit.putInt(KEY_LOCK_DURATION_SECONDS, coerced)
            edit.remove(KEY_LOCK_DURATION_PENDING_SECONDS)
            edit.remove(KEY_DURATION_DISPLAY_DEFER_UNTIL)
        } else {
            // Decrease: defer. Keep current effective until (now + currentEffective).
            val deferUntil = nowMillis + currentEffective * 1000L
            edit.putInt(KEY_LOCK_DURATION_PENDING_SECONDS, coerced)
            val existingDefer = p.getLong(KEY_DURATION_DISPLAY_DEFER_UNTIL, 0L)
            if (existingDefer <= nowMillis) {
                edit.putLong(KEY_DURATION_DISPLAY_DEFER_UNTIL, deferUntil)
            }
        }

        // If there's an active "protection off" countdown, only extend it (never shorten).
        if (pendingEndTimeMillis > nowMillis) {
            val remainingSec = ((pendingEndTimeMillis - nowMillis) / 1000L).toInt().coerceAtLeast(0)
            if (coerced > remainingSec) {
                edit.putLong(KEY_PENDING_PROTECTION_OFF_END, nowMillis + coerced * 1000L)
            }
        }

        edit.commit()
    }

    fun getPendingProtectionOffEndTime(context: Context): Long =
        prefs(context).getLong(KEY_PENDING_PROTECTION_OFF_END, 0L)

    fun setPendingProtectionOff(context: Context, endTimeMillis: Long) {
        prefs(context).edit().putLong(KEY_PENDING_PROTECTION_OFF_END, endTimeMillis).apply()
    }

    fun clearPendingProtectionOff(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_PROTECTION_OFF_END).apply()
    }

    fun getMaxDurationSeconds(): Int = MAX_DURATION
}
