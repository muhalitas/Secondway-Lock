package app.secondway.lock

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import android.os.Process

class MainActivity : AppCompatActivity() {

    private val reqPostNotifications = 1001

    private lateinit var helpButton: ImageButton
    private lateinit var settingsButton: ImageButton

    private lateinit var protectionPassiveLabel: TextView
    private lateinit var protectionActiveLabel: TextView
    private lateinit var protectionCountdown: TextView
    private lateinit var masterSwitch: Switch

    private lateinit var lockDurationCard: View
    private lateinit var lockDurationDisplay: TextView
    private lateinit var lockDurationDeferRow: View
    private lateinit var lockDurationDeferText: TextView
    private lateinit var lockDurationDeferCancel: Button

    private lateinit var allAppsRecycler: RecyclerView
    private val allAppRows = mutableListOf<NewAppRow>()
    private lateinit var allAppsAdapter: NewAppsAdapter
    private val pendingFinalizePkgs = mutableSetOf<String>()
    private lateinit var bottomNav: BottomNavigationView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = Runnable { tickCountdowns() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.lock_activity_main)

        ensureInstallMonitor()

        bottomNav = findViewById(R.id.bottom_nav)
        val currentNavId = R.id.nav_blocker
        bottomNav.menu.findItem(currentNavId)?.isChecked = true
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentNavId) return@setOnItemSelectedListener true
            when (item.itemId) {
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
                R.id.nav_info -> {
                    try {
                        startActivity(
                            Intent(this, app.secondway.lock.SettingsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {
                    }
                }
                R.id.nav_profile -> {
                    try {
                        startActivity(Intent(this, com.secondwaybrowser.app.SettingsActivity::class.java))
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {
                    }
                }
            }
            bottomNav.menu.findItem(currentNavId)?.isChecked = true
            false
        }

        helpButton = findViewById(R.id.help_button)
        settingsButton = findViewById(R.id.settings_button)
        helpButton.setOnClickListener {
            startActivity(Intent(this, FaqActivity::class.java))
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        protectionPassiveLabel = findViewById(R.id.protection_passive_label)
        protectionActiveLabel = findViewById(R.id.protection_active_label)
        protectionCountdown = findViewById(R.id.protection_countdown)
        masterSwitch = findViewById(R.id.master_switch)

        lockDurationCard = findViewById(R.id.lock_duration_card)
        lockDurationDisplay = findViewById(R.id.lock_duration_display)
        lockDurationDeferRow = findViewById(R.id.lock_duration_defer_row)
        lockDurationDeferText = findViewById(R.id.lock_duration_defer_text)
        lockDurationDeferCancel = findViewById(R.id.lock_duration_defer_cancel)
        lockDurationCard.setOnClickListener { showLockDurationPicker() }
        lockDurationDeferCancel.setOnClickListener {
            LockHelper.cancelDeferredLockDurationChange(this)
            updateLockDurationDisplay()
            ensureCountdownRunning()
            Toast.makeText(this, R.string.toast_lock_duration_defer_canceled, Toast.LENGTH_SHORT).show()
        }

        allAppsRecycler = findViewById(R.id.all_apps_list)
        allAppsRecycler.isNestedScrollingEnabled = false
        allAppsRecycler.layoutManager = LinearLayoutManager(this)
        allAppsAdapter = NewAppsAdapter(allAppRows) { row, allowed -> onNewAppSwitchChanged(row, allowed) }
        allAppsRecycler.adapter = allAppsAdapter

        // Onboarding/welcome screens are disabled for now (single-app build).
        // We mark onboarding as completed silently to avoid blocking lock functionality.
        if (!LockHelper.isWelcomeShown(this)) {
            LockHelper.setWelcomeShown(this)
            LockHelper.clearOnboardingStep(this)
        }
        loadAllAppsList()
        ensureCountdownRunning()
    }

    override fun onResume() {
        super.onResume()
        ensureInstallMonitor()
        PolicyHelper.onNewAppSuspended = {
            loadAllAppsList()
        }
        masterSwitch.setOnCheckedChangeListener(null)
        updateProtectionStatusAndMaster()
        maybeWarnMissingGuards()
        updateLockDurationDisplay()
        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            val activity = this@MainActivity
            if (isChecked) {
                MasterLockDuration.clearCountdown(activity)
                executor.execute {
                    val ok = PolicyHelper.setFactoryResetRestriction(activity, true)
                    mainHandler.post {
                        if (activity.isFinishing) return@post
                        updateProtectionStatusAndMaster()
                        if (ok) Toast.makeText(activity, getString(R.string.restrictions_applied), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val duration = MasterLockDuration.getDurationSeconds(activity)
                if (duration <= 0) {
                    executor.execute {
                        val ok = PolicyHelper.setFactoryResetRestriction(activity, false)
                        mainHandler.post {
                            if (activity.isFinishing) return@post
                            updateProtectionStatusAndMaster()
                            if (ok) Toast.makeText(activity, getString(R.string.restrictions_removed), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    MasterLockDuration.setCountdownEnd(activity, System.currentTimeMillis() + duration * 1000L)
                    updateProtectionStatusAndMaster()
                    val sec = MasterLockDuration.getRemainingSeconds(activity)
                    protectionCountdown.visibility = View.VISIBLE
                    val label = formatCountdownHms(sec)
                    protectionCountdown.text = label
                    Toast.makeText(activity, label, Toast.LENGTH_SHORT).show()
                    ensureCountdownRunning()
                }
            }
        }
        val activity = this
        executor.execute {
            val locked = NewAppReconciler.reconcile(activity)
            mainHandler.post {
                if (activity.isFinishing) return@post
                loadAllAppsList()
                ensureCountdownRunning()
                if (locked > 0) {
                    Toast.makeText(activity, getString(R.string.toast_new_apps_locked, locked), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        PolicyHelper.onNewAppSuspended = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(countdownRunnable)
        executor.shutdown()
        super.onDestroy()
    }

    private fun ensureInstallMonitor() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), reqPostNotifications)
                return
            }
        }
        try {
            InstallMonitor.ensureRunning(this)
        } catch (_: Throwable) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPostNotifications) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED
        if (granted) ensureInstallMonitor()
    }

    private fun ensureCountdownRunning() {
        val hasAppCountdown = NewAppLockStore.hasAnyActivePendingUnlock(this)
        val hasProtectionCountdown = MasterLockDuration.isCountdownActive(this)
        val durationDeferActive = LockHelper.getDurationDisplayDeferUntilMillis(this) > System.currentTimeMillis()
        if (hasAppCountdown || hasProtectionCountdown || durationDeferActive) {
            mainHandler.removeCallbacks(countdownRunnable)
            mainHandler.post(countdownRunnable)
        }
    }

    private fun tickCountdowns() {
        if (isFinishing) return
        val now = System.currentTimeMillis()
        allAppsAdapter.nowMillis = now
        finalizeDueNewApps(now)
        tickProtectionCountdown(now)
        updateLockDurationDisplay()

        val hasAppCountdown = NewAppLockStore.hasAnyActivePendingUnlock(this, now)
        val hasProtectionCountdown = MasterLockDuration.isCountdownActive(this)
        val durationDeferActive = LockHelper.getDurationDisplayDeferUntilMillis(this) > now
        if (hasAppCountdown || hasProtectionCountdown || durationDeferActive) {
            mainHandler.postDelayed(countdownRunnable, 1000L)
        }
    }

    private fun loadAllAppsList() {
        val activity = this
        executor.execute {
            val pm = activity.packageManager
            val tracked = NewAppLockStore.getTrackedPackages(activity)
            val rows = mutableListOf<NewAppRow>()
            val launcherPkgs = getLauncherPackages(pm)
            for (pkg in launcherPkgs) {
                if (pkg == activity.packageName) continue
                val ai = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: Throwable) {
                    continue
                }
                if (!isUserFacingApp(ai)) continue
                val label = try { ai.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                val icon = try { ai.loadIcon(pm) } catch (_: Exception) { null }
                val desiredAllowed = if (tracked.contains(pkg)) {
                    NewAppLockStore.isAllowed(activity, pkg)
                } else {
                    true
                }
                val isBlocked = PolicyHelper.isAppBlocked(activity, pkg)
                val pending = NewAppLockStore.getPendingUnlockEndMillis(activity, pkg)
                rows.add(NewAppRow(pkg, label, icon, desiredAllowed, isBlocked, pending))
            }
            rows.sortBy { it.label.lowercase() }
            mainHandler.post {
                if (activity.isFinishing) return@post
                allAppRows.clear()
                allAppRows.addAll(rows)
                allAppsAdapter.nowMillis = System.currentTimeMillis()
                allAppsAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun onNewAppSwitchChanged(row: NewAppRow, allow: Boolean) {
        if (pendingFinalizePkgs.contains(row.packageName)) return
        val activity = this
        pendingFinalizePkgs.add(row.packageName)
        val nowMillis = System.currentTimeMillis()
        executor.execute {
            val pkg = row.packageName
            val durationSec = MasterLockDuration.getDurationSeconds(activity)
            if (allow) {
                NewAppLockStore.setAllowed(activity, pkg, true)
                if (durationSec <= 0) {
                    PolicyHelper.setAppBlocked(activity, pkg, false)
                    NewAppUnlockScheduler.cancel(activity, pkg)
                    NewAppLockStore.clearPendingUnlock(activity, pkg)
                    NewAppLockStore.removeNewPackage(activity, pkg)
                } else {
                    NewAppLockStore.setPendingUnlockEndMillis(activity, pkg, nowMillis + durationSec * 1000L)
                    NewAppUnlockScheduler.schedule(activity, pkg, nowMillis + durationSec * 1000L)
                }
            } else {
                NewAppLockStore.setBlocked(activity, pkg)
                PolicyHelper.setAppBlocked(activity, pkg, true)
                NewAppUnlockScheduler.cancel(activity, pkg)
                NewAppLockStore.clearPendingUnlock(activity, pkg)
                NewAppLockStore.removeNewPackage(activity, pkg)
            }
            mainHandler.post {
                pendingFinalizePkgs.remove(pkg)
                if (activity.isFinishing) return@post
                loadAllAppsList()
                ensureCountdownRunning()
            }
        }
    }

    private fun finalizeDueNewApps(nowMillis: Long) {
        val activity = this
        executor.execute {
            val tracked = NewAppLockStore.getTrackedPackages(activity)
            for (pkg in tracked) {
                val end = NewAppLockStore.getPendingUnlockEndMillis(activity, pkg)
                if (end <= 0L || end > nowMillis) continue
                if (pendingFinalizePkgs.contains(pkg)) continue
                pendingFinalizePkgs.add(pkg)
                val ok = PolicyHelper.setAppBlocked(activity, pkg, false)
                if (ok) {
                    NewAppUnlockScheduler.cancel(activity, pkg)
                    NewAppLockStore.clearPendingUnlock(activity, pkg)
                    NewAppLockStore.removeNewPackage(activity, pkg)
                } else {
                    NewAppUnlockScheduler.schedule(activity, pkg, nowMillis + 60_000L)
                }
                mainHandler.post {
                    pendingFinalizePkgs.remove(pkg)
                    if (activity.isFinishing) return@post
                    loadAllAppsList()
                }
            }
        }
    }

    private fun onLockDurationChanged(newDurationSec: Int) {
        MasterLockDuration.applyDurationChange(this, newDurationSec)
        val now = System.currentTimeMillis()
        val updatedAppCountdowns =
            NewAppLockStore.applyDurationChangeToPendingUnlocks(this, newDurationSec, now)
        if (updatedAppCountdowns > 0) loadAllAppsList()
        updateLockDurationDisplay()
        ensureCountdownRunning()
    }

    private fun updateLockDurationDisplay() {
        val now = System.currentTimeMillis()
        val deferUntil = LockHelper.getDurationDisplayDeferUntilMillis(this)
        val effective = MasterLockDuration.getDurationSeconds(this)
        val pendingOff = MasterLockDuration.isCountdownActive(this)
        lockDurationDisplay.text = formatLockDuration(effective)

        val pending = MasterLockDuration.getConfiguredDurationSeconds(this)
        val deferActive = deferUntil > now && pending < effective
        if (deferActive) {
            val remainingSec = ((deferUntil - now) / 1000L).toInt().coerceAtLeast(1)
            lockDurationDeferRow.visibility = View.VISIBLE
            lockDurationDeferText.text = getString(R.string.lock_duration_defer_text, formatLockDuration(pending), remainingSec)
            lockDurationDeferCancel.isEnabled = !pendingOff
        } else {
            if (deferUntil > 0L && deferUntil <= now) LockHelper.clearDurationDisplayDeferUntil(this)
            lockDurationDeferRow.visibility = View.GONE
        }
        if (pendingOff) {
            lockDurationCard.isEnabled = false
            lockDurationCard.alpha = 0.6f
        } else {
            lockDurationCard.isEnabled = !deferActive
            lockDurationCard.alpha = if (deferActive) 0.6f else 1f
        }
    }

    private fun showLockDurationPicker() {
        if (MasterLockDuration.isCountdownActive(this)) return
        val totalSeconds = MasterLockDuration.getConfiguredDurationSeconds(this)
            .coerceIn(0, MasterLockDuration.getMaxDurationSeconds())
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60

        val view = LayoutInflater.from(this).inflate(R.layout.lock_dialog_duration_picker, null)
        val hoursPicker = view.findViewById<NumberPicker>(R.id.duration_hours)
        val minutesPicker = view.findViewById<NumberPicker>(R.id.duration_minutes)
        val secondsPicker = view.findViewById<NumberPicker>(R.id.duration_seconds)
        val previewText = view.findViewById<TextView>(R.id.duration_preview)
        val countdownTv = view.findViewById<TextView>(R.id.duration_dialog_countdown)

        hoursPicker.minValue = 0
        hoursPicker.maxValue = 24
        hoursPicker.value = h
        minutesPicker.minValue = 0
        minutesPicker.maxValue = 59
        minutesPicker.value = m
        secondsPicker.minValue = 0
        secondsPicker.maxValue = 59
        secondsPicker.value = s

        fun updatePreview() {
            val sec = hoursPicker.value * 3600 + minutesPicker.value * 60 + secondsPicker.value
            previewText.text = formatLockDuration(sec)
        }
        hoursPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        minutesPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        secondsPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        updatePreview()

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val sec = (hoursPicker.value * 3600 + minutesPicker.value * 60 + secondsPicker.value)
                    .coerceIn(0, MasterLockDuration.getMaxDurationSeconds())
                onLockDurationChanged(sec)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        startDialogCountdown(dialog, countdownTv)
        dialog.show()
    }
    private fun updateProtectionStatusAndMaster() {
        val factoryResetOn = PolicyHelper.isFactoryResetRestriction(this)
        val pendingOff = MasterLockDuration.isCountdownActive(this)
        val active = factoryResetOn && !pendingOff
        masterSwitch.isEnabled = true
        masterSwitch.isChecked = if (pendingOff) false else factoryResetOn
        protectionPassiveLabel.setTypeface(null, if (active) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        protectionActiveLabel.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        applyMasterSwitchTint(pendingOff)

        if (pendingOff) {
            val sec = MasterLockDuration.getRemainingSeconds(this)
            protectionCountdown.visibility = View.VISIBLE
            protectionCountdown.text = formatCountdownHms(sec)
        } else {
            protectionCountdown.visibility = View.GONE
        }
    }

    private fun maybeWarnMissingGuards() {
        if (!PolicyHelper.isFactoryResetRestriction(this)) return
        if (!GuardServiceHelper.isAccessibilityGuardEnabled(this)) {
            Toast.makeText(this, R.string.toast_enable_accessibility_guard, Toast.LENGTH_LONG).show()
            return
        }
        if (!GuardServiceHelper.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_enable_overlay_permission, Toast.LENGTH_LONG).show()
        }
    }

    private fun tickProtectionCountdown(now: Long) {
        val pendingEnd = MasterLockDuration.getPendingEndTimeMillis(this)
        if (pendingEnd <= 0L || now >= pendingEnd) {
            if (pendingEnd > 0L && now >= pendingEnd) {
                MasterLockDuration.clearCountdown(this)
                val activity = this
                executor.execute {
                    val ok = PolicyHelper.setFactoryResetRestriction(activity, false)
                    mainHandler.post {
                        if (activity.isFinishing) return@post
                        updateProtectionStatusAndMaster()
                        if (ok) Toast.makeText(activity, getString(R.string.restrictions_removed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            protectionCountdown.visibility = View.GONE
        } else {
            val sec = MasterLockDuration.getRemainingSeconds(this)
            protectionCountdown.visibility = View.VISIBLE
            protectionCountdown.text = formatCountdownHms(sec)
        }
    }

    private fun applyMasterSwitchTint(pendingOff: Boolean) {
        if (pendingOff) {
            val thumb = ContextCompat.getColor(this, R.color.switch_pending_thumb)
            val track = ContextCompat.getColor(this, R.color.switch_pending_track)
            masterSwitch.thumbTintList = ColorStateList.valueOf(thumb)
            masterSwitch.trackTintList = ColorStateList.valueOf(track)
            return
        }
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val thumbColors = intArrayOf(
            ContextCompat.getColor(this, R.color.switch_on_thumb),
            ContextCompat.getColor(this, R.color.switch_off_thumb)
        )
        val trackColors = intArrayOf(
            ContextCompat.getColor(this, R.color.switch_on_track),
            ContextCompat.getColor(this, R.color.switch_off_track)
        )
        masterSwitch.thumbTintList = ColorStateList(states, thumbColors)
        masterSwitch.trackTintList = ColorStateList(states, trackColors)
    }

    private fun isSystemApp(info: ApplicationInfo): Boolean {
        val flags = info.flags
        if ((flags and ApplicationInfo.FLAG_SYSTEM) != 0) return true
        if ((flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true
        if (info.uid < Process.FIRST_APPLICATION_UID) return true
        val source = info.sourceDir ?: return true
        return !source.startsWith("/data/")
    }

    private fun isUserFacingApp(info: ApplicationInfo): Boolean {
        if (!isSystemApp(info)) return true
        if ((info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true
        if ((info.flags and ApplicationInfo.FLAG_PERSISTENT) != 0) return false
        val pkg = info.packageName
        if (CORE_SYSTEM_PACKAGES.contains(pkg)) return false
        if (CORE_SYSTEM_PACKAGE_PREFIXES.any { pkg.startsWith(it) }) return false
        return true
    }

    private fun getLauncherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val resolveInfo = try {
            pm.queryIntentActivities(intent, flags)
        } catch (_: Exception) {
            emptyList<ResolveInfo>()
        }
        return resolveInfo.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun cleanupTrackedPackage(context: Context, packageName: String) {
        NewAppUnlockScheduler.cancel(context, packageName)
        NewAppLockStore.removeTrackedPackage(context, packageName)
    }

    // Onboarding screens are currently disabled.

    private companion object {
        private val CORE_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.shell",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher"
        )

        private val CORE_SYSTEM_PACKAGE_PREFIXES = listOf(
            "com.android.providers.",
            "com.android.internal."
        )
    }

    // (Onboarding methods removed.)

    private fun startDialogCountdown(dialog: AlertDialog, countdownTv: TextView) {
        val handler = Handler(Looper.getMainLooper())
        val dialogTick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val sec = MasterLockDuration.getRemainingSeconds(this@MainActivity)
                if (sec <= 0) {
                    countdownTv.visibility = View.GONE
                    return
                }
                countdownTv.visibility = View.VISIBLE
                countdownTv.text = formatCountdownHms(sec)
                handler.postDelayed(this, 1000L)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(dialogTick) }
        if (MasterLockDuration.isCountdownActive(this)) handler.post(dialogTick)
    }

    private fun formatLockDuration(seconds: Int): String {
        if (seconds <= 0) return getString(R.string.duration_preview_immediate)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return getString(R.string.duration_preview_format, h, m, s)
    }

    private fun formatCountdownHms(seconds: Int): String {
        val sec = seconds.coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return getString(R.string.countdown_unlocking_hms, h, m, s)
    }

    private fun buildDots(step: Int, total: Int): String {
        val sb = StringBuilder()
        for (i in 1..total) {
            if (i > 1) sb.append(" ")
            sb.append(if (i == step) "●" else "○")
        }
        return sb.toString()
    }
}
