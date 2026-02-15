package com.secondwaybrowser.app

import android.content.Context

object OnboardingHelper {
    private const val PREFS_NAME = "onboarding_prefs"
    private const val KEY_WELCOME_SHOWN = "welcome_shown"
    private const val KEY_STEP = "onboarding_step"

    fun isWelcomeShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WELCOME_SHOWN, false)

    fun setWelcomeShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WELCOME_SHOWN, true)
            .apply()
    }

    fun getOnboardingStep(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_STEP, 0)

    fun setOnboardingStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STEP, step)
            .apply()
    }

    fun clearOnboardingStep(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STEP)
            .apply()
    }
}
