package com.secondwaybrowser.app

import android.content.Context

object SecondwayOnboardingPrefs {

    private const val PREFS = "secondway_onboarding"
    private const val KEY_COMPLETED = "completed"
    private const val KEY_DAILY_HOURS = "daily_hours"
    private const val KEY_PRIMARY_TRIGGER = "primary_trigger"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context, completed: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, completed).apply()
    }

    fun setDailyHours(context: Context, hours: Float) {
        prefs(context).edit().putFloat(KEY_DAILY_HOURS, hours.coerceIn(0f, 24f)).apply()
    }

    fun getDailyHours(context: Context): Float =
        prefs(context).getFloat(KEY_DAILY_HOURS, 1f).coerceIn(0f, 24f)

    fun setPrimaryTrigger(context: Context, trigger: String) {
        prefs(context).edit().putString(KEY_PRIMARY_TRIGGER, trigger.take(80)).apply()
    }

    fun getPrimaryTrigger(context: Context): String =
        prefs(context).getString(KEY_PRIMARY_TRIGGER, "").orEmpty()
}

