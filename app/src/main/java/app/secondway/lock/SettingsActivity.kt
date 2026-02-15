package app.secondway.lock

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var modeStatusText: TextView
    private lateinit var fullProtectionCard: View
    private lateinit var fullProtectionNoticeText: TextView
    private lateinit var defaultBrowserDesc: TextView
    private lateinit var defaultBrowserAction: Button
    private lateinit var languageValue: TextView
    private lateinit var languageChangeButton: Button

    private lateinit var batteryOptCard: View
    private lateinit var batteryOptTitle: TextView
    private lateinit var batteryOptAllow: Button
    private lateinit var batteryOptDismiss: Button

    private lateinit var readmeButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var overlayButton: Button
    private lateinit var testGuardButton: Button
    private lateinit var appInfoButton: Button
    private lateinit var bottomNav: BottomNavigationView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = Runnable { tickCountdowns() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.lock_activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)

        modeStatusText = findViewById(R.id.mode_status)
        fullProtectionCard = findViewById(R.id.full_protection_card)
        fullProtectionNoticeText = findViewById(R.id.full_protection_notice_text)
        defaultBrowserDesc = findViewById(R.id.default_browser_desc)
        defaultBrowserAction = findViewById(R.id.default_browser_action)
        languageValue = findViewById(R.id.language_value)
        languageChangeButton = findViewById(R.id.language_change_button)

        batteryOptCard = findViewById(R.id.battery_opt_card)
        batteryOptTitle = findViewById(R.id.battery_opt_title)
        batteryOptAllow = findViewById(R.id.battery_opt_allow)
        batteryOptDismiss = findViewById(R.id.battery_opt_dismiss)

        readmeButton = findViewById(R.id.readme_button)
        accessibilityButton = findViewById(R.id.button_enable_accessibility_guard)
        overlayButton = findViewById(R.id.button_allow_overlay)
        testGuardButton = findViewById(R.id.button_test_guard_screen)
        appInfoButton = findViewById(R.id.button_open_app_info)
        bottomNav = findViewById(R.id.bottom_nav)

        val currentNavId = R.id.nav_info
        bottomNav.menu.findItem(currentNavId)?.isChecked = true
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentNavId) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_blocker -> {
                    try {
                        startActivity(
                            Intent(this, app.secondway.lock.MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {
                    }
                }
                R.id.nav_browser -> {
                    try {
                        startActivity(
                            Intent(this, com.secondwaybrowser.app.MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {
                    }
                }
                R.id.nav_profile -> {
                    try {
                        startActivity(
                            Intent(this, com.secondwaybrowser.app.SettingsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {
                    }
                }
            }
            bottomNav.menu.findItem(currentNavId)?.isChecked = true
            false
        }

        fullProtectionNoticeText.movementMethod = LinkMovementMethod.getInstance()
        fullProtectionNoticeText.text = getString(R.string.soft_mode_notice)

        batteryOptAllow.setOnClickListener {
            try {
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
            } catch (_: Throwable) {
                try {
                    BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
                } catch (_: Throwable) {
                }
            }
        }
        batteryOptDismiss.setOnClickListener {
            LockHelper.setBatteryOptCardDismissed(this, true)
            updateBatteryOptimizationUi()
        }

        languageChangeButton.setOnClickListener { showLanguagePicker() }
        defaultBrowserAction.setOnClickListener { handleDefaultBrowserAction() }
        accessibilityButton.setOnClickListener {
            try {
                GuardServiceHelper.openAccessibilitySettings(this)
            } catch (_: Throwable) {
            }
        }
        overlayButton.setOnClickListener {
            try {
                // Allow the user to grant overlay permission without our guard kicking them out immediately.
                // Once permission is granted, the guard will start protecting this screen again.
                GuardTamperGate.beginOverlayGrantFlow(this)
                GuardServiceHelper.openOverlaySettings(this)
            } catch (_: Throwable) {
            }
        }
        testGuardButton.setOnClickListener {
            try {
                startActivity(
                    Intent(this, GuardInterventionActivity::class.java)
                        .putExtra(
                            GuardInterventionActivity.EXTRA_MESSAGE,
                            getString(R.string.guard_intervention_test_message)
                        )
                )
            } catch (_: Throwable) {
            }
        }
        appInfoButton.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (_: Throwable) {
            }
        }

        readmeButton.setOnClickListener {
            startActivity(Intent(this, ReadmeActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateModeStatus()
        updateFullProtectionNotice()
        updateBatteryOptimizationUi()
        updateLanguageRow()
        updateDefaultBrowserRow()
        updatePermissionButtons()
        PolicyHelper.clearDebuggingRestrictionIfSet(this)
        ensureCountdownRunning()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(countdownRunnable)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateModeStatus() {
        val active = PolicyHelper.isFactoryResetRestriction(this)
        val accessibilityEnabled = GuardServiceHelper.isAccessibilityGuardEnabled(this)
        val overlayEnabled = GuardServiceHelper.canDrawOverlays(this)
        modeStatusText.text = getString(
            R.string.status_soft_mode_format,
            if (active) getString(R.string.protection_active) else getString(R.string.protection_passive),
            if (accessibilityEnabled) getString(R.string.state_on) else getString(R.string.state_off),
            if (overlayEnabled) getString(R.string.state_on) else getString(R.string.state_off)
        )
    }

    private fun updatePermissionButtons() {
        val accessibilityEnabled = GuardServiceHelper.isAccessibilityGuardEnabled(this)
        accessibilityButton.alpha = if (accessibilityEnabled) 0.7f else 1f
    }

    private fun updateLanguageRow() {
        val label = LanguageHelper.getCurrentLanguageLabel(this)
        languageValue.text = getString(R.string.settings_language_value, label)
    }

    private fun updateDefaultBrowserRow() {
        defaultBrowserDesc.text = getString(R.string.settings_default_browser_desc)
        defaultBrowserAction.text = getString(R.string.settings_default_browser_action)
    }

    private fun handleDefaultBrowserAction() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Throwable) {
        }
    }

    private fun updateFullProtectionNotice() {
        fullProtectionCard.visibility = View.VISIBLE
    }

    private fun updateBatteryOptimizationUi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryOptCard.visibility = View.GONE
            batteryOptTitle.visibility = View.GONE
            return
        }
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            batteryOptCard.visibility = View.GONE
            batteryOptTitle.visibility = View.GONE
            return
        }
        if (LockHelper.isBatteryOptCardDismissed(this)) {
            batteryOptCard.visibility = View.GONE
            batteryOptTitle.visibility = View.GONE
            return
        }
        batteryOptCard.visibility = View.VISIBLE
        batteryOptTitle.visibility = View.VISIBLE
    }

    private fun ensureCountdownRunning() {
        val shouldRun = MasterLockDuration.isCountdownActive(this)
        if (shouldRun) {
            mainHandler.removeCallbacks(countdownRunnable)
            mainHandler.post(countdownRunnable)
        }
    }

    private fun tickCountdowns() {
        if (isFinishing) return
        val now = System.currentTimeMillis()

        val pendingEnd = MasterLockDuration.getPendingEndTimeMillis(this)
        if (pendingEnd > 0L && now >= pendingEnd) {
            MasterLockDuration.clearCountdown(this)
            val activity = this
            executor.execute {
                val ok = PolicyHelper.setFactoryResetRestriction(activity, false)
                mainHandler.post {
                    if (activity.isFinishing) return@post
                    if (ok) Toast.makeText(activity, getString(R.string.restrictions_removed), Toast.LENGTH_SHORT).show()
                    updateModeStatus()
                }
            }
        }

        val shouldContinue = MasterLockDuration.isCountdownActive(this)
        if (shouldContinue) mainHandler.postDelayed(countdownRunnable, 1000L)
    }

    private fun showLanguagePicker() {
        val options = LanguageHelper.getLanguageOptions(this)
        val labels = options.map { it.label }.toTypedArray()
        val currentTag = LanguageHelper.getSavedLanguageTag(this)
        val checked = options.indexOfFirst { it.tag == currentTag }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selectedTag = options[which].tag
                LanguageHelper.setAppLanguage(this, selectedTag)
                updateLanguageRow()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
