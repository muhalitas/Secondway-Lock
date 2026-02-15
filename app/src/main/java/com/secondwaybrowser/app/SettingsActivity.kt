package com.secondwaybrowser.app

import app.secondway.lock.R

import android.app.DownloadManager
import android.content.Intent
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var accountStatus: TextView
    private lateinit var accountAction: View
    private lateinit var languageValue: TextView
    private lateinit var languageChangeButton: View
    private lateinit var lockDurationValue: TextView
    private lateinit var lockDurationButton: View
    private lateinit var allowlistButton: View
    private lateinit var downloadsButton: View
    private lateinit var historyButton: View
    private lateinit var clearDataButton: View
    private lateinit var defaultBrowserButton: View
    private lateinit var aboutButton: View
    private lateinit var bottomNav: BottomNavigationView

    private var allowlistCountdownHandler: android.os.Handler? = null
    private var allowlistCountdownRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageHelper.applySavedLocale(this)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings_title)

        accountStatus = findViewById(R.id.account_status)
        accountAction = findViewById(R.id.account_action)
        languageValue = findViewById(R.id.language_value)
        languageChangeButton = findViewById(R.id.language_change_button)
        lockDurationValue = findViewById(R.id.lock_duration_value)
        lockDurationButton = findViewById(R.id.lock_duration_button)
        allowlistButton = findViewById(R.id.allowlist_button)
        downloadsButton = findViewById(R.id.downloads_button)
        historyButton = findViewById(R.id.history_button)
        clearDataButton = findViewById(R.id.clear_data_button)
        defaultBrowserButton = findViewById(R.id.default_browser_button)
        aboutButton = findViewById(R.id.about_button)
        bottomNav = findViewById(R.id.bottom_nav)

        val currentNavId = R.id.nav_profile
        bottomNav.menu.findItem(currentNavId)?.isChecked = true
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentNavId) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_browser -> {
                    try {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {
                    }
                }
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
            }
            bottomNav.menu.findItem(currentNavId)?.isChecked = true
            false
        }

        accountAction.setOnClickListener { handleAccountAction() }
        languageChangeButton.setOnClickListener { showLanguagePicker() }
        lockDurationButton.setOnClickListener { showSetLockDurationDialog() }
        allowlistButton.setOnClickListener { showAllowlistDialog() }
        downloadsButton.setOnClickListener { showDownloadsDialog() }
        historyButton.setOnClickListener { showHistoryDialog() }
        clearDataButton.setOnClickListener { showClearBrowsingDataDialog() }
        defaultBrowserButton.setOnClickListener { requestDefaultBrowserRole() }
        aboutButton.setOnClickListener { showAboutDialog() }
    }

    override fun onResume() {
        super.onResume()
        updateAccountRow()
        updateLanguageRow()
        updateLockDurationRow()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        allowlistCountdownRunnable?.let { allowlistCountdownHandler?.removeCallbacks(it) }
        allowlistCountdownHandler = null
        allowlistCountdownRunnable = null
        super.onDestroy()
    }

    private fun updateAccountRow() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            val label = user.email ?: user.uid
            accountStatus.text = getString(R.string.settings_signed_in_as, label)
            (accountAction as? android.widget.Button)?.text = getString(R.string.sign_out)
        } else {
            accountStatus.text = getString(R.string.settings_signed_out)
            (accountAction as? android.widget.Button)?.text = getString(R.string.sign_in)
        }
    }

    private fun handleAccountAction() {
        val user = FirebaseAuth.getInstance().currentUser
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_ACTION, if (user != null) MainActivity.ACTION_SIGN_OUT else MainActivity.ACTION_SIGN_IN)
        }
        startActivity(intent)
        finish()
    }

    private fun updateLanguageRow() {
        val label = LanguageHelper.getCurrentLanguageLabel(this)
        languageValue.text = getString(R.string.settings_language_value, label)
    }

    private fun updateLockDurationRow() {
        val currentMs = AllowlistHelper.getLockDurationMs(this)
        val pending = AllowlistHelper.getPendingLockDuration(this)
        val base = getString(R.string.lock_duration_current, formatLockDuration(currentMs))
        lockDurationValue.text = if (pending == null) {
            base
        } else {
            val remaining = (pending.second - System.currentTimeMillis()).coerceAtLeast(0)
            "$base\n${getString(R.string.lock_duration_reduction_pending, formatLockDurationForMessage(remaining))}"
        }
    }

    private fun showLanguagePicker() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_onboarding_language, null)
        view.findViewById<TextView>(R.id.onboarding_title).text = getString(R.string.settings_language_title)
        view.findViewById<TextView>(R.id.onboarding_message).text = getString(R.string.settings_language_message)
        view.findViewById<TextView>(R.id.onboarding_dots).visibility = View.GONE

        val group = view.findViewById<android.widget.RadioGroup>(R.id.language_group)
        val options = LanguageHelper.getLanguageOptions(this)
        val currentTag = LanguageHelper.getSavedLanguageTag(this)
        var checkedId = View.NO_ID
        for ((index, option) in options.withIndex()) {
            val rb = android.widget.RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = option.label
            rb.layoutParams = android.widget.RadioGroup.LayoutParams(
                android.widget.RadioGroup.LayoutParams.MATCH_PARENT,
                android.widget.RadioGroup.LayoutParams.WRAP_CONTENT
            )
            group.addView(rb)
            if (option.tag == currentTag || (currentTag.isBlank() && index == 0)) {
                checkedId = rb.id
            }
        }
        if (checkedId != View.NO_ID) group.check(checkedId)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedId = group.checkedRadioButtonId
                val selectedIndex = group.indexOfChild(group.findViewById(selectedId))
                val selectedTag = options.getOrNull(selectedIndex)?.tag ?: ""
                LanguageHelper.setAppLanguage(this, selectedTag)
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSetLockDurationDialog() {
        val currentMs = AllowlistHelper.getLockDurationMs(this)
        val pending = AllowlistHelper.getPendingLockDuration(this)
        val msg = buildString {
            append(getString(R.string.lock_duration_current, formatLockDuration(currentMs)))
            if (pending != null) {
                val (pendingMs, effectiveAt) = pending
                val remaining = (effectiveAt - System.currentTimeMillis()).coerceAtLeast(0)
                append("\n\n")
                append(getString(R.string.lock_duration_reduction_pending, formatLockDurationForMessage(remaining)))
                append(" (")
                append(getString(R.string.lock_duration_new_value, formatLockDuration(pendingMs)))
                append(")")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_set_lock_duration)
            .setMessage(msg)
            .setPositiveButton(R.string.lock_duration_change) { _, _ -> showSetLockDurationPicker() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSetLockDurationPicker() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_duration_picker, null)
        val hoursPicker = view.findViewById<NumberPicker>(R.id.duration_hours)
        val minutesPicker = view.findViewById<NumberPicker>(R.id.duration_minutes)
        val secondsPicker = view.findViewById<NumberPicker>(R.id.duration_seconds)
        val pickerColor = getPrimaryTextColor()
        setNumberPickerTextColor(hoursPicker, pickerColor)
        setNumberPickerTextColor(minutesPicker, pickerColor)
        setNumberPickerTextColor(secondsPicker, pickerColor)
        hoursPicker.minValue = 0
        hoursPicker.maxValue = 23
        minutesPicker.minValue = 0
        minutesPicker.maxValue = 59
        secondsPicker.minValue = 0
        secondsPicker.maxValue = 59
        val currentMs = AllowlistHelper.getLockDurationMs(this)
        val totalSec = (currentMs / 1000).toInt()
        hoursPicker.value = totalSec / 3600
        minutesPicker.value = (totalSec % 3600) / 60
        secondsPicker.value = totalSec % 60
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_duration_set)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val h = hoursPicker.value
                val m = minutesPicker.value
                val s = secondsPicker.value
                val newMs = (h * 3600L + m * 60L + s) * 1000L
                val new = newMs.coerceAtLeast(1000L)
                val current = AllowlistHelper.getLockDurationMs(this)
                AllowlistHelper.setLockDurationWithPolicy(this, new)
                pushSettingsToServer()
                if (new >= current) {
                    Toast.makeText(this, getString(R.string.lock_duration_increased), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.lock_duration_reduction_pending, formatLockDurationForMessage(current)), Toast.LENGTH_LONG).show()
                }
                updateLockDurationRow()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun getPrimaryTextColor(): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        return if (typedValue.type == 28) {
            typedValue.data
        } else {
            val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        }
    }

    private fun setNumberPickerTextColor(picker: NumberPicker, color: Int) {
        try {
            for (i in 0 until picker.childCount) {
                val child = picker.getChildAt(i)
                if (child is EditText) child.setTextColor(color)
            }
            val f = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
            f.isAccessible = true
            (f.get(picker) as android.graphics.Paint).color = color
            picker.invalidate()
        } catch (_: Exception) { }
    }

    private fun formatLockDuration(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun formatLockDurationForMessage(ms: Long): String {
        return formatLockDuration(ms)
    }

    private fun pushSettingsToServer() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        val lockMs = AllowlistHelper.getLockDurationMs(this)
        val pending = AllowlistHelper.getPendingLockDuration(this)
        val data = mutableMapOf<String, Any>(
            "lockDurationMs" to lockMs,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (pending != null) {
            data["lockDurationPendingMs"] = pending.first
            data["lockDurationEffectiveAt"] = pending.second
        } else {
            data["lockDurationPendingMs"] = FieldValue.delete()
            data["lockDurationEffectiveAt"] = FieldValue.delete()
        }
        db.collection("users").document(user.uid)
            .set(data, SetOptions.merge())
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    getString(R.string.error_settings_sync_failed, it.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun showAllowlistDialog() {
        val contentView = LayoutInflater.from(this).inflate(R.layout.dialog_allowlist_content, null)
        val allowlistContent = contentView.findViewById<TextView>(R.id.allowlist_section_content)
        val waitlistContent = contentView.findViewById<TextView>(R.id.waitlist_section_content)
        val dateFormat = SimpleDateFormat("MMM d, yyy · HH:mm", Locale.getDefault())

        fun refreshContent() {
            AllowlistHelper.promoteExpiredWaitlist(this)
            val list = AllowlistHelper.getAllowlistWithTimestamps(this)
            allowlistContent.text = if (list.isEmpty()) {
                getString(R.string.allowlist_empty)
            } else {
                list.joinToString("\n\n") { (host, ts) ->
                    val dateStr = if (ts <= 0) getString(R.string.allowlist_date_unknown) else dateFormat.format(Date(ts))
                    "$host\n$dateStr"
                }
            }
            val waitlist = AllowlistHelper.getWaitlistWithEndTimes(this)
            val now = System.currentTimeMillis()
            waitlistContent.text = if (waitlist.isEmpty()) {
                getString(R.string.allowlist_empty)
            } else {
                waitlist.joinToString("\n\n") { (host, endMs) ->
                    val remaining = (endMs - now).coerceAtLeast(0)
                    val h = (remaining / 3600_000) % 24
                    val m = (remaining / 60_000) % 60
                    val s = (remaining / 1_000) % 60
                    "$host\n${getString(R.string.allowlist_remaining_hms, h, m, s)}"
                }
            }
        }

        refreshContent()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.allowlist_dialog_title)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        allowlistCountdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
        allowlistCountdownRunnable = object : Runnable {
            override fun run() {
                if (!isFinishing && dialog.isShowing) {
                    refreshContent()
                    allowlistCountdownHandler?.postDelayed(this, 1000)
                }
            }
        }
        allowlistCountdownHandler?.postDelayed(allowlistCountdownRunnable!!, 1000)
        dialog.setOnDismissListener {
            allowlistCountdownRunnable?.let { allowlistCountdownHandler?.removeCallbacks(it) }
            allowlistCountdownHandler = null
            allowlistCountdownRunnable = null
        }
    }

    private fun showDownloadsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_downloads, null)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_downloads)
        val empty = view.findViewById<TextView>(R.id.downloads_empty)
        val items = queryDownloads()
        val adapter = DownloadsAdapter(items.toMutableList())
        recycler.adapter = adapter
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.downloads)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showHistoryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_history, null)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_history)
        val empty = view.findViewById<TextView>(R.id.history_empty)
        val items = HistoryStore.getEntries(this).toMutableList()
        lateinit var adapter: HistoryAdapter
        adapter = HistoryAdapter(items, { entry ->
            openUrlInBrowser(entry.url)
        }, { entry ->
            HistoryStore.removeEntry(this, entry.id)
            val updated = HistoryStore.getEntries(this)
            adapter.update(updated)
            empty.visibility = if (updated.isEmpty()) View.VISIBLE else View.GONE
        })
        recycler.adapter = adapter
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.history)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openUrlInBrowser(url: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_URL, url)
        }
        startActivity(intent)
        finish()
    }

    private fun showClearBrowsingDataDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_clear_browsing_data, null)
        val radioGroup = view.findViewById<android.widget.RadioGroup>(R.id.clear_time_range)
        val clearHistory = view.findViewById<android.widget.CheckBox>(R.id.clear_history)
        val clearCookies = view.findViewById<android.widget.CheckBox>(R.id.clear_cookies)
        val clearCache = view.findViewById<android.widget.CheckBox>(R.id.clear_cache)
        view.findViewById<android.widget.RadioButton>(R.id.range_last_hour).isChecked = true
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_browsing_data)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_browsing_data) { _, _ ->
                val rangeId = radioGroup.checkedRadioButtonId
                clearBrowsingData(rangeId, clearHistory.isChecked, clearCookies.isChecked, clearCache.isChecked)
                Toast.makeText(this, getString(R.string.clear_done), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun clearBrowsingData(
        rangeId: Int,
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean
    ) {
        val now = System.currentTimeMillis()
        val cutoff = when (rangeId) {
            R.id.range_last_hour -> now - 60 * 60 * 1000L
            R.id.range_last_day -> now - 24 * 60 * 60 * 1000L
            R.id.range_last_week -> now - 7 * 24 * 60 * 60 * 1000L
            R.id.range_last_month -> now - 28 * 24 * 60 * 60 * 1000L
            R.id.range_all_time -> Long.MIN_VALUE
            else -> Long.MIN_VALUE
        }
        if (clearHistory) {
            if (cutoff == Long.MIN_VALUE) HistoryStore.clearAll(this) else HistoryStore.clearSince(this, cutoff)
        }
        if (clearCookies) {
            try {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
            } catch (_: Exception) { }
            try {
                android.webkit.WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) { }
            try {
                android.webkit.WebViewDatabase.getInstance(this).clearFormData()
                android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
            } catch (_: Exception) { }
        }
        if (clearCache) {
            deleteRecursively(java.io.File(cacheDir, "cf_http_cache"))
        }
    }

    private fun deleteRecursively(dir: java.io.File) {
        if (!dir.exists()) return
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { deleteRecursively(it) }
        }
        try { dir.delete() } catch (_: Exception) { }
    }

    private fun queryDownloads(): List<DownloadItem> {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
        val cursor = dm.query(query)
        val items = mutableListOf<DownloadItem>()
        cursor?.use {
            val idCol = it.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val sizeCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val timeCol = it.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val uriCol = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol) ?: ""
                val status = when (it.getInt(statusCol)) {
                    DownloadManager.STATUS_PENDING -> getString(R.string.download_status_pending)
                    DownloadManager.STATUS_RUNNING -> getString(R.string.download_status_downloading)
                    DownloadManager.STATUS_PAUSED -> getString(R.string.download_status_paused)
                    DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.download_status_completed)
                    DownloadManager.STATUS_FAILED -> getString(R.string.download_status_failed)
                    else -> getString(R.string.download_status_unknown)
                }
                val size = it.getLong(sizeCol)
                val time = it.getLong(timeCol)
                val uri = it.getString(uriCol)
                items.add(DownloadItem(id, title.ifBlank { getString(R.string.download_default_title) }, status, size, time, uri))
            }
        }
        return items.sortedByDescending { it.timestampMs }
    }

    private fun showAboutDialog() {
        val versionName: String
        val updatedStr: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pkg = android.webkit.WebView.getCurrentWebViewPackage()
            versionName = pkg?.versionName ?: getString(R.string.about_webview_unknown)
            updatedStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pkg != null) {
                try {
                    val pi = packageManager.getPackageInfo(pkg.packageName, 0)
                    val lastUpdate = pi.lastUpdateTime
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lastUpdate))
                } catch (_: Exception) {
                    "—"
                }
            } else {
                "—"
            }
        } else {
            versionName = getString(R.string.about_webview_unknown)
            updatedStr = "—"
        }
        val versionLine = getString(R.string.about_webview_version, versionName)
        val updatedLine = getString(R.string.about_webview_updated, updatedStr)
        val uiUpdateLine = getString(R.string.about_ui_last_update, getString(R.string.build_time_value))
        val howItWorks = getString(R.string.about_how_it_works)
        val message = "$versionLine\n$updatedLine\n$uiUpdateLine\n\n$howItWorks"
        AlertDialog.Builder(this)
            .setTitle(R.string.about_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openDefaultBrowserSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Throwable) { }
    }

    private fun requestDefaultBrowserRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                    return
                }
            }
        }
        openDefaultBrowserSettings()
    }
}
