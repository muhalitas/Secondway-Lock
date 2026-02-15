package app.secondway.lock

import android.content.Context

/**
 * Soft-mode guard sometimes needs to *allow* entering certain system screens briefly
 * (e.g. overlay permission) so the user can grant required permissions from inside the app.
 *
 * This is not a security boundary; it just prevents our own interception from blocking setup.
 */
object GuardTamperGate {

    private const val PREFS = "guard_tamper_gate"
    private const val KEY_ALLOW_OVERLAY_SETTINGS_UNTIL = "allow_overlay_settings_until"
    private const val KEY_ALLOW_OVERLAY_SETTINGS_STARTED_AT = "allow_overlay_settings_started_at"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beginOverlayGrantFlow(context: Context, ttlMillis: Long = 60_000L) {
        val now = System.currentTimeMillis()
        val until = now + ttlMillis.coerceAtLeast(2_000L)
        prefs(context).edit()
            .putLong(KEY_ALLOW_OVERLAY_SETTINGS_STARTED_AT, now)
            .putLong(KEY_ALLOW_OVERLAY_SETTINGS_UNTIL, until)
            .apply()
    }

    fun isOverlayGrantFlowActive(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val until = prefs(context).getLong(KEY_ALLOW_OVERLAY_SETTINGS_UNTIL, 0L)
        return until > nowMillis
    }

    fun isOverlayGrantFlowInEarlyPhase(
        context: Context,
        earlyMillis: Long = 6_000L,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isOverlayGrantFlowActive(context, nowMillis)) return false
        val startedAt = prefs(context).getLong(KEY_ALLOW_OVERLAY_SETTINGS_STARTED_AT, 0L)
        if (startedAt <= 0L) return false
        return nowMillis - startedAt <= earlyMillis.coerceAtLeast(500L)
    }

    fun clearOverlayGrantFlow(context: Context) {
        prefs(context).edit()
            .remove(KEY_ALLOW_OVERLAY_SETTINGS_UNTIL)
            .remove(KEY_ALLOW_OVERLAY_SETTINGS_STARTED_AT)
            .apply()
    }
}
