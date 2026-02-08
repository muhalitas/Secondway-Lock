package app.secondway.lock

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.graphics.Typeface
import android.widget.Button
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val reqPostNotifications = 1001

    private lateinit var fullProtectionCard: View
    private lateinit var fullProtectionNoticeText: TextView
    private lateinit var deviceOwnerStatusText: TextView
    private lateinit var protectionPassiveLabel: TextView
    private lateinit var protectionActiveLabel: TextView
    private lateinit var protectionCountdown: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var lockDurationCard: View
    private lateinit var lockDurationDisplay: TextView
    private lateinit var lockDurationDeferRow: View
    private lateinit var lockDurationDeferText: TextView
    private lateinit var lockDurationDeferCancel: Button
    private lateinit var batteryOptCard: View
    private lateinit var batteryOptAllow: Button
    private lateinit var batteryOptDismiss: Button

    private lateinit var newAppsRecycler: RecyclerView
    private lateinit var newAppsEmpty: TextView
    private val newAppRows = mutableListOf<NewAppRow>()
    private lateinit var newAppsAdapter: NewAppsAdapter
    private lateinit var allAppsRecycler: RecyclerView
    private val allAppRows = mutableListOf<NewAppRow>()
    private lateinit var allAppsAdapter: NewAppsAdapter
    private val pendingFinalizePkgs = mutableSetOf<String>()

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = Runnable { tickCountdowns() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ensureInstallMonitor()

        fullProtectionCard = findViewById(R.id.full_protection_card)
        fullProtectionNoticeText = findViewById(R.id.full_protection_notice_text)
        deviceOwnerStatusText = findViewById(R.id.device_owner_status)
        protectionPassiveLabel = findViewById(R.id.protection_passive_label)
        protectionActiveLabel = findViewById(R.id.protection_active_label)
        protectionCountdown = findViewById(R.id.protection_countdown)
        masterSwitch = findViewById(R.id.master_switch)
        lockDurationDisplay = findViewById(R.id.lock_duration_display)
        lockDurationCard = findViewById(R.id.lock_duration_card)
        lockDurationDeferRow = findViewById(R.id.lock_duration_defer_row)
        lockDurationDeferText = findViewById(R.id.lock_duration_defer_text)
        lockDurationDeferCancel = findViewById(R.id.lock_duration_defer_cancel)
        batteryOptCard = findViewById(R.id.battery_opt_card)
        batteryOptAllow = findViewById(R.id.battery_opt_allow)
        batteryOptDismiss = findViewById(R.id.battery_opt_dismiss)

        fullProtectionNoticeText.movementMethod = LinkMovementMethod.getInstance()
        fullProtectionNoticeText.text = HtmlCompat.fromHtml(
            getString(R.string.full_protection_notice),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        findViewById<Button>(R.id.readme_button).setOnClickListener {
            startActivity(Intent(this, ReadmeActivity::class.java))
        }

        newAppsRecycler = findViewById(R.id.new_apps_list)
        newAppsEmpty = findViewById(R.id.new_apps_empty)
        newAppsRecycler.layoutManager = LinearLayoutManager(this)
        newAppsAdapter = NewAppsAdapter(newAppRows) { row, allowed -> onNewAppSwitchChanged(row, allowed) }
        newAppsRecycler.adapter = newAppsAdapter

        allAppsRecycler = findViewById(R.id.all_apps_list)
        allAppsRecycler.layoutManager = LinearLayoutManager(this)
        allAppsAdapter = NewAppsAdapter(allAppRows) { row, allowed -> onNewAppSwitchChanged(row, allowed) }
        allAppsRecycler.adapter = allAppsAdapter

        updateLockDurationDisplay()
        lockDurationCard.setOnClickListener { showLockDurationPicker() }
        lockDurationDeferCancel.setOnClickListener {
            LockHelper.cancelDeferredLockDurationChange(this)
            updateLockDurationDisplay()
            mainHandler.removeCallbacks(countdownRunnable)
            ensureCountdownRunning()
            Toast.makeText(this, R.string.toast_lock_duration_defer_canceled, Toast.LENGTH_SHORT).show()
        }

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

        if (!LockHelper.isWelcomeShown(this)) {
            showWelcomeThenDurationPicker()
        } else {
            refreshUi()
            ensureCountdownRunning()
        }
    }

    override fun onResume() {
        super.onResume()
        ensureInstallMonitor()
        updateFullProtectionNotice()
        updateBatteryOptimizationUi()
        PolicyHelper.onNewAppSuspended = {
            loadNewAppsList()
            loadAllAppsList()
        }
        refreshUi()
        if (MasterLockDuration.isCountdownActive(this)) ensureCountdownRunning()
        val activity = this
        if (PolicyHelper.isDeviceOwner(activity)) {
            executor.execute {
                val locked = NewAppReconciler.reconcile(activity)
                mainHandler.post {
                    if (activity.isFinishing) return@post
                    loadNewAppsList()
                    loadAllAppsList()
                    ensureCountdownRunning()
                    if (locked > 0) {
                        Toast.makeText(activity, getString(R.string.toast_new_apps_locked, locked), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            loadNewAppsList()
            loadAllAppsList()
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
        if (!PolicyHelper.isDeviceOwner(this)) return
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

    private fun updateFullProtectionNotice() {
        val activity = this
        executor.execute {
            val hasBrowser = hasSecondwayBrowserInstalled(activity)
            mainHandler.post {
                if (activity.isFinishing) return@post
                // Show only if Secondway Browser is missing.
                fullProtectionCard.visibility = if (hasBrowser) View.GONE else View.VISIBLE
            }
        }
    }

    private fun hasSecondwayBrowserInstalled(context: android.content.Context): Boolean {
        val pm = context.packageManager
        // Preferred: known package ids (if you rename it later, keep the old ids too).
        val candidatePackages = listOf(
            "com.secondway.browser",
            "com.secondway.secondwaybrowser",
            "com.safebrowser.app"
        )
        for (pkg in candidatePackages) {
            try {
                pm.getApplicationInfo(pkg, 0)
                return true
            } catch (_: Exception) {
            }
        }

        // Fallback: match the user-visible app label.
        val apps = try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }
        for (ai in apps) {
            val label = try { ai.loadLabel(pm).toString() } catch (_: Throwable) { "" }
            if (label.equals("Secondway Browser", ignoreCase = true)) return true
            if (label.equals("Safe Browser Beta", ignoreCase = true)) return true
        }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPostNotifications) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED
        if (granted) ensureInstallMonitor()
    }

    private fun ensureCountdownRunning() {
        val now = System.currentTimeMillis()
        val durationDeferActive = LockHelper.getDurationDisplayDeferUntilMillis(this) > now
        val shouldRun =
            MasterLockDuration.isCountdownActive(this) ||
                NewAppLockStore.hasAnyActivePendingUnlock(this) ||
                durationDeferActive
        if (shouldRun) {
            mainHandler.removeCallbacks(countdownRunnable)
            mainHandler.post(countdownRunnable)
        }
    }

    private fun tickCountdowns() {
        if (isFinishing) return

        val now = System.currentTimeMillis()

        // Protection OFF countdown (factory-reset restriction removal)
        val pendingEnd = MasterLockDuration.getPendingEndTimeMillis(this)
        if (pendingEnd <= 0L || now >= pendingEnd) {
            if (pendingEnd > 0L && now >= pendingEnd) {
                MasterLockDuration.clearCountdown(this)
                val activity = this
                executor.execute {
                    val ok = PolicyHelper.setFactoryResetRestriction(activity, false)
                    mainHandler.post {
                        if (activity.isFinishing) return@post
                        refreshUi()
                        if (ok) Toast.makeText(activity, getString(R.string.restrictions_removed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            protectionCountdown.visibility = View.GONE
        } else {
            val sec = MasterLockDuration.getRemainingSeconds(this)
            protectionCountdown.visibility = View.VISIBLE
            protectionCountdown.text = getString(R.string.countdown_unlocking, sec)
        }

        // New-app delayed unlock countdowns (per-package)
        newAppsAdapter.nowMillis = now
        finalizeDueNewApps(now)

        updateLockDurationDisplay()

        val durationDeferActive = LockHelper.getDurationDisplayDeferUntilMillis(this) > now
        val shouldContinue =
            MasterLockDuration.isCountdownActive(this) ||
                NewAppLockStore.hasAnyActivePendingUnlock(this, now) ||
                durationDeferActive
        if (shouldContinue) mainHandler.postDelayed(countdownRunnable, 1000L)
    }

    private fun refreshUi() {
        val isOwner = PolicyHelper.isDeviceOwner(this)
        deviceOwnerStatusText.text = if (isOwner) getString(R.string.status_device_owner) else getString(R.string.status_not_device_owner)
        masterSwitch.isEnabled = isOwner

        masterSwitch.setOnCheckedChangeListener(null)

        updateProtectionStatusAndMaster()

        if (MasterLockDuration.isCountdownActive(this)) {
            val sec = MasterLockDuration.getRemainingSeconds(this)
            protectionCountdown.visibility = View.VISIBLE
            protectionCountdown.text = getString(R.string.countdown_unlocking, sec)
        } else {
            protectionCountdown.visibility = View.GONE
        }

        PolicyHelper.clearDebuggingRestrictionIfSet(this)
        updateLockDurationDisplay()
        updateBatteryOptimizationUi()

        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!PolicyHelper.isDeviceOwner(this@MainActivity)) return@setOnCheckedChangeListener
            val activity = this@MainActivity
            if (isChecked) {
                MasterLockDuration.clearCountdown(activity)
                executor.execute {
                    val ok = PolicyHelper.setFactoryResetRestriction(activity, true)
                    mainHandler.post {
                        if (activity.isFinishing) return@post
                        refreshUi()
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
                            refreshUi()
                            if (ok) Toast.makeText(activity, getString(R.string.restrictions_removed), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    MasterLockDuration.setCountdownEnd(activity, System.currentTimeMillis() + duration * 1000L)
                    updateProtectionStatusAndMaster()
                    val sec = MasterLockDuration.getRemainingSeconds(activity)
                    protectionCountdown.visibility = View.VISIBLE
                    protectionCountdown.text = getString(R.string.countdown_unlocking, sec)
                    Toast.makeText(activity, getString(R.string.countdown_unlocking, sec), Toast.LENGTH_SHORT).show()
                    ensureCountdownRunning()
                }
            }
        }
    }

    private fun updateBatteryOptimizationUi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryOptCard.visibility = View.GONE
            return
        }
        if (!PolicyHelper.isDeviceOwner(this)) {
            batteryOptCard.visibility = View.GONE
            return
        }

        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            batteryOptCard.visibility = View.GONE
            return
        }

        if (LockHelper.isBatteryOptCardDismissed(this)) {
            batteryOptCard.visibility = View.GONE
            return
        }

        batteryOptCard.visibility = View.VISIBLE
    }

    private fun updateProtectionStatusAndMaster() {
        val isOwner = PolicyHelper.isDeviceOwner(this)
        val factoryResetOn = PolicyHelper.isFactoryResetRestriction(this)
        val pendingOff = MasterLockDuration.isCountdownActive(this)
        val active = isOwner && factoryResetOn && !pendingOff
        masterSwitch.isChecked = if (pendingOff) false else factoryResetOn
        protectionPassiveLabel.setTypeface(null, if (active) Typeface.NORMAL else Typeface.BOLD)
        protectionActiveLabel.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun formatLockDuration(seconds: Int): String {
        if (seconds <= 0) return getString(R.string.duration_preview_immediate)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return getString(R.string.duration_preview_format, h, m, s)
    }

    private fun updateLockDurationDisplay() {
        val now = System.currentTimeMillis()
        val deferUntil = LockHelper.getDurationDisplayDeferUntilMillis(this)
        val effective = MasterLockDuration.getDurationSeconds(this)
        lockDurationDisplay.text = formatLockDuration(effective)

        val pending = MasterLockDuration.getConfiguredDurationSeconds(this)
        val deferActive = deferUntil > now && pending < effective
        if (deferActive) {
            val remainingSec = ((deferUntil - now) / 1000L).toInt().coerceAtLeast(1)
            lockDurationDeferRow.visibility = View.VISIBLE
            lockDurationDeferText.text = getString(R.string.lock_duration_defer_text, formatLockDuration(pending), remainingSec)
            lockDurationCard.isEnabled = false
            lockDurationCard.alpha = 0.6f
        } else {
            if (deferUntil > 0L && deferUntil <= now) LockHelper.clearDurationDisplayDeferUntil(this)
            lockDurationDeferRow.visibility = View.GONE
            lockDurationCard.isEnabled = true
            lockDurationCard.alpha = 1f
        }
    }

    private fun loadNewAppsList() {
        if (!PolicyHelper.isDeviceOwner(this)) {
            newAppRows.clear()
            newAppsAdapter.notifyDataSetChanged()
            newAppsEmpty.visibility = View.VISIBLE
            newAppsEmpty.text = getString(R.string.status_not_device_owner)
            newAppsRecycler.visibility = View.GONE
            return
        }
        newAppsRecycler.visibility = View.VISIBLE

        val activity = this
        executor.execute {
            val pm = activity.packageManager
            val newPkgs = NewAppLockStore.getNewPackages(activity)
            val now = System.currentTimeMillis()
            val rows = mutableListOf<NewAppRow>()
            for (pkg in newPkgs) {
                if (pkg == activity.packageName) {
                    NewAppUnlockScheduler.cancel(activity, pkg)
                    NewAppLockStore.removeTrackedPackage(activity, pkg)
                    continue
                }
                @Suppress("DEPRECATION")
                val flags =
                    PackageManager.MATCH_UNINSTALLED_PACKAGES or
                        PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.GET_META_DATA
                val info = try { pm.getApplicationInfo(pkg, flags) } catch (_: Exception) { null }
                val installed =
                    info != null && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                if (!installed) {
                    NewAppUnlockScheduler.cancel(activity, pkg)
                    NewAppLockStore.removeTrackedPackage(activity, pkg)
                    continue
                }

                val desiredAllowed = NewAppLockStore.isAllowed(activity, pkg)
                val label = info?.loadLabel(pm)?.toString() ?: pkg
                val icon = if (info == null) null else try { info.loadIcon(pm) } catch (_: Exception) { null }
                val pendingEnd = NewAppLockStore.getPendingUnlockEndMillis(activity, pkg)
                val effectivePending = if (pendingEnd > now) pendingEnd else 0L

                // Enforce desired state so it persists across app restarts.
                val isBlocked = PolicyHelper.isAppBlocked(activity, pkg)
                if (!desiredAllowed) {
                    if (pendingEnd > 0L) {
                        NewAppUnlockScheduler.cancel(activity, pkg)
                        NewAppLockStore.clearPendingUnlock(activity, pkg)
                    }
                    if (!isBlocked) PolicyHelper.setAppBlocked(activity, pkg, true)
                } else {
                    if (effectivePending > 0L) {
                        NewAppUnlockScheduler.schedule(activity, pkg, effectivePending)
                        if (!isBlocked) PolicyHelper.setAppBlocked(activity, pkg, true)
                    } else {
                        if (isBlocked) PolicyHelper.setAppBlocked(activity, pkg, false)
                        NewAppLockStore.removeNewPackage(activity, pkg)
                    }
                }

                val blockedAfter = PolicyHelper.isAppBlocked(activity, pkg)
                rows.add(NewAppRow(pkg, label, icon, desiredAllowed, blockedAfter, effectivePending))
            }
            rows.sortBy { it.label.lowercase() }
            mainHandler.post {
                if (activity.isFinishing) return@post
                newAppRows.clear()
                newAppRows.addAll(rows)
                newAppsAdapter.nowMillis = System.currentTimeMillis()
                newAppsAdapter.notifyDataSetChanged()
                val empty = newAppRows.isEmpty()
                newAppsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                newAppsEmpty.text = getString(R.string.new_apps_empty)
                newAppsRecycler.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    private fun loadAllAppsList() {
        if (!PolicyHelper.isDeviceOwner(this)) {
            allAppRows.clear()
            allAppsAdapter.notifyDataSetChanged()
            allAppsRecycler.visibility = View.GONE
            return
        }
        allAppsRecycler.visibility = View.VISIBLE

        val activity = this
        executor.execute {
            val pm = activity.packageManager
            val now = System.currentTimeMillis()
            val tracked = NewAppLockStore.getTrackedPackages(activity)

            val launcherPkgs = try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it != activity.packageName }
            } catch (_: Exception) {
                emptyList()
            }

            val rows = mutableListOf<NewAppRow>()
            for (pkg in launcherPkgs) {
                val info = try { pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA) } catch (_: Exception) { continue }
                if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                ) continue

                val isTracked = pkg in tracked
                val desiredAllowed = if (isTracked) NewAppLockStore.isAllowed(activity, pkg) else true
                val label = info.loadLabel(pm)?.toString() ?: pkg
                val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }

                val pendingEnd = if (!isTracked) 0L else NewAppLockStore.getPendingUnlockEndMillis(activity, pkg)
                val effectivePending = if (pendingEnd > now) pendingEnd else 0L

                // Enforce tracked state to persist across app restarts.
                var blocked = if (!isTracked) false else PolicyHelper.isAppBlocked(activity, pkg)
                if (isTracked) {
                    if (!desiredAllowed) {
                        if (pendingEnd > 0L) {
                            NewAppUnlockScheduler.cancel(activity, pkg)
                            NewAppLockStore.clearPendingUnlock(activity, pkg)
                        }
                        if (!blocked) {
                            PolicyHelper.setAppBlocked(activity, pkg, true)
                            blocked = true
                        }
                    } else {
                        if (effectivePending > 0L) {
                            NewAppUnlockScheduler.schedule(activity, pkg, effectivePending)
                            if (!blocked) {
                                PolicyHelper.setAppBlocked(activity, pkg, true)
                                blocked = true
                            }
                        } else {
                            if (blocked) {
                                PolicyHelper.setAppBlocked(activity, pkg, false)
                                blocked = false
                            }
                        }
                    }
                }

                rows.add(NewAppRow(pkg, label, icon, desiredAllowed, blocked, effectivePending))
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

    private fun onNewAppSwitchChanged(row: NewAppRow, allowed: Boolean) {
        if (!PolicyHelper.isDeviceOwner(this)) return

        val durationSec = MasterLockDuration.getDurationSeconds(this).coerceAtLeast(0)
        val now = System.currentTimeMillis()

        if (!allowed) {
            NewAppUnlockScheduler.cancel(this, row.packageName)
            NewAppLockStore.setBlocked(this, row.packageName)
            val activity = this
            executor.execute {
                val ok = PolicyHelper.setAppBlocked(activity, row.packageName, true)
                mainHandler.post {
                    if (activity.isFinishing) return@post
                    loadNewAppsList()
                    loadAllAppsList()
                    if (ok) Toast.makeText(activity, R.string.toast_app_blocked, Toast.LENGTH_SHORT).show()
                    else Toast.makeText(activity, R.string.toast_app_suspend_failed, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val delayUnlock = masterSwitch.isChecked && durationSec > 0
        if (delayUnlock) {
            val end = now + durationSec * 1000L
            NewAppLockStore.setPendingUnlockEndMillis(this, row.packageName, end)
            NewAppUnlockScheduler.schedule(this, row.packageName, end)
            // Keep it blocked while counting down.
            executor.execute { PolicyHelper.setAppBlocked(this, row.packageName, true) }
            loadNewAppsList()
            loadAllAppsList()
            ensureCountdownRunning()
            Toast.makeText(this, getString(R.string.countdown_unlocking, durationSec), Toast.LENGTH_SHORT).show()
        } else {
            NewAppLockStore.setAllowed(this, row.packageName, true)
            NewAppLockStore.clearPendingUnlock(this, row.packageName)
            val activity = this
            executor.execute {
                val ok = PolicyHelper.setAppBlocked(activity, row.packageName, false)
                if (ok) NewAppLockStore.removeNewPackage(activity, row.packageName)
                mainHandler.post {
                    if (activity.isFinishing) return@post
                    loadNewAppsList()
                    loadAllAppsList()
                    if (ok) Toast.makeText(activity, R.string.toast_app_allowed, Toast.LENGTH_SHORT).show()
                    else Toast.makeText(activity, R.string.toast_app_suspend_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun finalizeDueNewApps(nowMillis: Long) {
        val activity = this
        for (pkg in NewAppLockStore.getTrackedPackages(activity)) {
            val end = NewAppLockStore.getPendingUnlockEndMillis(activity, pkg)
            if (end <= 0L || nowMillis < end) continue
            if (!NewAppLockStore.isAllowed(activity, pkg)) continue
            if (!pendingFinalizePkgs.add(pkg)) continue
            executor.execute {
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
                    loadNewAppsList()
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

        updateLockDurationDisplay()
        refreshUi()
        ensureCountdownRunning()
        if (updatedAppCountdowns > 0) loadNewAppsList()
    }

    private fun showWelcomeThenDurationPicker() {
        AlertDialog.Builder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> showLockDurationPickerForFirstRun() }
            .show()
    }

    private fun showLockDurationPickerForFirstRun() {
        val totalSeconds = MasterLockDuration.getConfiguredDurationSeconds(this).coerceIn(0, MasterLockDuration.getMaxDurationSeconds())
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_duration_picker, null)
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
            .setTitle(R.string.duration_picker_title)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val sec = (hoursPicker.value * 3600 + minutesPicker.value * 60 + secondsPicker.value)
                    .coerceIn(0, MasterLockDuration.getMaxDurationSeconds())
                onLockDurationChanged(sec)
                LockHelper.setWelcomeShown(this)
                ensureCountdownRunning()
            }
            .create()
        startDialogCountdown(dialog, countdownTv)
        dialog.show()
    }

    private fun showLockDurationPicker() {
        val totalSeconds = MasterLockDuration.getConfiguredDurationSeconds(this).coerceIn(0, MasterLockDuration.getMaxDurationSeconds())
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_duration_picker, null)
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
                ensureCountdownRunning()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        startDialogCountdown(dialog, countdownTv)
        dialog.show()
    }

    /** Picker açıkken geri sayım varsa TextView'ı saniyede bir günceller; kapatılınca durur. */
    private fun startDialogCountdown(dialog: AlertDialog, countdownTv: TextView) {
        val dialogTick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val sec = MasterLockDuration.getRemainingSeconds(this@MainActivity)
                if (sec <= 0) {
                    countdownTv.visibility = View.GONE
                    return
                }
                countdownTv.visibility = View.VISIBLE
                countdownTv.text = getString(R.string.countdown_unlocking, sec)
                mainHandler.postDelayed(this, 1000L)
            }
        }
        dialog.setOnDismissListener { mainHandler.removeCallbacks(dialogTick) }
        if (MasterLockDuration.isCountdownActive(this)) mainHandler.post(dialogTick)
    }
}
