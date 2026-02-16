package com.secondwaybrowser.app

import app.secondway.lock.BatteryOptimizationHelper
import app.secondway.lock.GuardServiceHelper
import app.secondway.lock.MasterLockDuration
import app.secondway.lock.R
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider

class OnboardingActivity : AppCompatActivity() {

    private lateinit var progress: LinearProgressIndicator
    private lateinit var closeButton: ImageButton
    private lateinit var content: View
    private lateinit var primaryButton: MaterialButton
    private lateinit var secondaryButton: TextView

    private var step = 0
    private val totalSteps = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageHelper.applySavedLocale(this)
        setContentView(R.layout.activity_onboarding)

        progress = findViewById(R.id.onboarding_progress)
        closeButton = findViewById(R.id.onboarding_close)
        primaryButton = findViewById(R.id.onboarding_primary)
        secondaryButton = findViewById(R.id.onboarding_secondary)

        closeButton.setOnClickListener { finishOnboarding(skip = true) }
        secondaryButton.setOnClickListener { finishOnboarding(skip = true) }
        primaryButton.setOnClickListener { onPrimary() }

        step = intent.getIntExtra(EXTRA_STEP, 0).coerceIn(0, totalSteps - 1)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Permission step needs to refresh statuses after returning from Settings.
        if (step == STEP_PERMISSIONS) {
            render()
        }
    }

    private fun onPrimary() {
        when (step) {
            STEP_SETUP -> {
                val selected = content.findViewById<TextView>(R.id.profile_selected).tag as? String
                    ?: SecondwayOnboardingPrefs.PROFILE_BALANCED
                SecondwayOnboardingPrefs.setRecoveryProfile(this, selected)
            }
            STEP_TIME -> {
                val slider = content.findViewById<Slider>(R.id.daily_hours_slider)
                SecondwayOnboardingPrefs.setDailyHours(this, slider.value)
            }
            STEP_TRIGGER -> {
                val t = content.findViewById<TextView>(R.id.trigger_selected).tag as? String
                SecondwayOnboardingPrefs.setPrimaryTrigger(this, t ?: "")
            }
            STEP_PERMISSIONS -> {
                // Allow continuing even if not all are granted, but show live statuses.
            }
        }

        if (step >= totalSteps - 1) {
            finishOnboarding(skip = false)
        } else {
            step++
            render()
        }
    }

    private fun render() {
        progress.max = 100
        progress.progress = (((step + 1) * 100f) / totalSteps).toInt().coerceIn(1, 100)

        val inflater = LayoutInflater.from(this)
        val container = findViewById<View>(R.id.onboarding_content) as android.widget.FrameLayout
        container.removeAllViews()

        val layoutId = when (step) {
            STEP_WELCOME -> R.layout.onboarding_step_welcome
            STEP_SETUP -> R.layout.onboarding_step_setup
            STEP_TIME -> R.layout.onboarding_step_time
            STEP_STATS -> R.layout.onboarding_step_stats
            STEP_TRIGGER -> R.layout.onboarding_step_trigger
            STEP_PERMISSIONS -> R.layout.onboarding_step_permissions
            else -> R.layout.onboarding_step_finish
        }

        content = inflater.inflate(layoutId, container, false)
        container.addView(content)

        secondaryButton.visibility = if (step <= STEP_SETUP) View.VISIBLE else View.INVISIBLE
        secondaryButton.text = getString(R.string.onboarding_skip)

        primaryButton.text = when (step) {
            STEP_WELCOME -> getString(R.string.onboarding_cta_start)
            STEP_FINISH -> getString(R.string.onboarding_cta_finish)
            else -> getString(R.string.onboarding_cta_next)
        }

        when (step) {
            STEP_SETUP -> bindSetupStep()
            STEP_TIME -> bindTimeStep()
            STEP_STATS -> bindStatsStep()
            STEP_TRIGGER -> bindTriggerStep()
            STEP_PERMISSIONS -> bindPermissionsStep()
        }
    }

    private fun bindSetupStep() {
        val strict = content.findViewById<TextView>(R.id.profile_strict)
        val balanced = content.findViewById<TextView>(R.id.profile_balanced)
        val focus = content.findViewById<TextView>(R.id.profile_focus)
        val selectedLabel = content.findViewById<TextView>(R.id.profile_selected)

        fun applySelection(profile: String) {
            selectedLabel.tag = profile
            selectedLabel.text = when (profile) {
                SecondwayOnboardingPrefs.PROFILE_STRICT -> getString(R.string.onboarding_profile_selected_strict)
                SecondwayOnboardingPrefs.PROFILE_FOCUS -> getString(R.string.onboarding_profile_selected_focus)
                else -> getString(R.string.onboarding_profile_selected_balanced)
            }
            strict.alpha = if (profile == SecondwayOnboardingPrefs.PROFILE_STRICT) 1f else 0.72f
            balanced.alpha = if (profile == SecondwayOnboardingPrefs.PROFILE_BALANCED) 1f else 0.72f
            focus.alpha = if (profile == SecondwayOnboardingPrefs.PROFILE_FOCUS) 1f else 0.72f
        }

        val existing = SecondwayOnboardingPrefs.getRecoveryProfile(this)
        applySelection(existing)

        strict.setOnClickListener { applySelection(SecondwayOnboardingPrefs.PROFILE_STRICT) }
        balanced.setOnClickListener { applySelection(SecondwayOnboardingPrefs.PROFILE_BALANCED) }
        focus.setOnClickListener { applySelection(SecondwayOnboardingPrefs.PROFILE_FOCUS) }
    }

    private fun bindTimeStep() {
        val slider = content.findViewById<Slider>(R.id.daily_hours_slider)
        val valueTv = content.findViewById<TextView>(R.id.daily_hours_value)
        val initial = SecondwayOnboardingPrefs.getDailyHours(this).coerceIn(0f, 12f)
        slider.valueFrom = 0f
        slider.valueTo = 12f
        slider.stepSize = 0.5f
        slider.value = initial
        fun update(v: Float) {
            valueTv.text = formatHours(v)
        }
        update(slider.value)
        slider.addOnChangeListener { _, v, _ -> update(v) }
    }

    private fun bindStatsStep() {
        val daily = SecondwayOnboardingPrefs.getDailyHours(this)
        val dailyTv = content.findViewById<TextView>(R.id.stats_daily_value)
        val yearsTv = content.findViewById<TextView>(R.id.stats_years_value)
        dailyTv.text = formatHours(daily)
        yearsTv.text = formatYears(daily)
    }

    private fun bindTriggerStep() {
        val selected = content.findViewById<TextView>(R.id.trigger_selected)
        fun setTrigger(id: Int, value: String) {
            content.findViewById<View>(id).setOnClickListener {
                selected.text = value
                selected.tag = value
            }
        }
        setTrigger(R.id.trigger_stress, getString(R.string.trigger_stress))
        setTrigger(R.id.trigger_lonely, getString(R.string.trigger_lonely))
        setTrigger(R.id.trigger_night, getString(R.string.trigger_night))
        setTrigger(R.id.trigger_bored, getString(R.string.trigger_bored))

        val existing = SecondwayOnboardingPrefs.getPrimaryTrigger(this)
        if (existing.isNotBlank()) {
            selected.text = existing
            selected.tag = existing
        }
    }

    private fun bindPermissionsStep() {
        val acc = content.findViewById<TextView>(R.id.perm_accessibility_state)
        val ov = content.findViewById<TextView>(R.id.perm_overlay_state)
        val batt = content.findViewById<TextView>(R.id.perm_battery_state)

        val accOn = GuardServiceHelper.isAccessibilityGuardEnabled(this)
        val ovOn = GuardServiceHelper.canDrawOverlays(this)
        val battOn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        } else {
            true
        }

        acc.text = if (accOn) getString(R.string.state_on) else getString(R.string.state_off)
        ov.text = if (ovOn) getString(R.string.state_on) else getString(R.string.state_off)
        batt.text = if (battOn) getString(R.string.state_on) else getString(R.string.state_off)

        content.findViewById<View>(R.id.perm_accessibility_button).setOnClickListener {
            try {
                GuardServiceHelper.openAccessibilitySettings(this)
                overridePendingTransition(0, 0)
            } catch (_: Throwable) {
            }
        }
        content.findViewById<View>(R.id.perm_overlay_button).setOnClickListener {
            try {
                app.secondway.lock.GuardTamperGate.beginOverlayGrantFlow(this)
                GuardServiceHelper.openOverlaySettings(this)
                overridePendingTransition(0, 0)
            } catch (_: Throwable) {
            }
        }
        content.findViewById<View>(R.id.perm_battery_button).setOnClickListener {
            try {
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
                overridePendingTransition(0, 0)
            } catch (_: Throwable) {
                try {
                    BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
                    overridePendingTransition(0, 0)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun finishOnboarding(skip: Boolean) {
        SecondwayOnboardingPrefs.setCompleted(this, true)
        // Disable legacy dialog onboarding from the old browser build.
        OnboardingHelper.setWelcomeShown(this)
        OnboardingHelper.clearOnboardingStep(this)

        if (!skip) applyPersonaDefaults()

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        overridePendingTransition(0, 0)
        finish()
    }

    private fun applyPersonaDefaults() {
        val profile = SecondwayOnboardingPrefs.getRecoveryProfile(this)
        val waitMs = when (profile) {
            SecondwayOnboardingPrefs.PROFILE_STRICT -> 60 * 60 * 1000L
            SecondwayOnboardingPrefs.PROFILE_FOCUS -> 15 * 60 * 1000L
            else -> 30 * 60 * 1000L
        }
        val lockSec = when (profile) {
            SecondwayOnboardingPrefs.PROFILE_STRICT -> 60 * 60
            SecondwayOnboardingPrefs.PROFILE_FOCUS -> 15 * 60
            else -> 30 * 60
        }
        AllowlistHelper.setLockDurationMs(this, waitMs)
        AllowlistHelper.setLockDurationSet(this)
        MasterLockDuration.applyDurationChange(this, lockSec)
    }

    private fun formatHours(hours: Float): String {
        val totalMin = (hours * 60f).toInt().coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h <= 0) {
            "${m}m"
        } else if (m == 0) {
            "${h}h"
        } else {
            "${h}h ${m}m"
        }
    }

    private fun formatYears(dailyHours: Float): String {
        // Years spent over 30 years at this daily pace: dailyHours*30/24.
        val years = (dailyHours.coerceAtLeast(0f) * 30f) / 24f
        val rounded = String.format(java.util.Locale.US, "%.1f", years)
        return rounded
    }

    companion object {
        private const val STEP_WELCOME = 0
        private const val STEP_SETUP = 1
        private const val STEP_TIME = 2
        private const val STEP_STATS = 3
        private const val STEP_TRIGGER = 4
        private const val STEP_PERMISSIONS = 5
        private const val STEP_FINISH = 6

        private const val EXTRA_STEP = "extra_step"
    }
}
