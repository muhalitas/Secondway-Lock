package com.secondwaybrowser.app

import app.secondway.lock.R

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.app.role.RoleManager
import android.provider.Settings
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.webkit.URLUtil
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.NumberPicker
import android.widget.ListPopupWindow
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.secondwaybrowser.app.dns.CloudflareFamilyDns
import com.secondwaybrowser.app.proxy.WebViewProxyManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import okhttp3.Request
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), TabFragment.Listener, TabSwitcherDialogFragment.Callback {

    companion object {
        const val GOOGLE_SAFE_SEARCH_HOME = "https://www.google.com"
        private const val TAB_SWITCHER_TAG = "tab_switcher"
        private const val SUGGEST_DELAY_MS = 300L
        private const val SUGGEST_URL = "https://suggestqueries.google.com/complete/search?client=firefox&hl=en&q="
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_SIGN_IN = "action_sign_in"
        const val ACTION_SIGN_OUT = "action_sign_out"
        const val EXTRA_OPEN_URL = "extra_open_url"
        private val URL_REGEX: Pattern =
            Pattern.compile("((?:https?://|www\\.)[^\\s]+)", Pattern.CASE_INSENSITIVE)
    }

    private lateinit var urlBar: EditText
    private lateinit var btnHome: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnAddTab: ImageButton
    private lateinit var btnAllowlist: ImageButton
    private lateinit var btnTabs: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var viewPager: ViewPager2
    private lateinit var tabsAdapter: TabsAdapter
    private lateinit var tabCountBadge: TextView
    private lateinit var loadProgress: ProgressBar
    private lateinit var toolbarUrlContainer: View
    private lateinit var toolbarTabsFrame: View
    private var urlBarSelectAllActive = false
    private val resolveClient by lazy {
        val cache = File(cacheDir, "resolve_cache")
        CloudflareFamilyDns.createClientWithFamilyDns(cacheDir = cache)
    }
    private val resolveTag = "SafeBrowserResolve"

    private val tabs = mutableListOf<TabItem>()
    private var currentWebView: WebView? = null
    private var suggestionPopup: ListPopupWindow? = null
    private val suggestHandler = Handler(Looper.getMainLooper())
    private var suggestRunnable: Runnable? = null
    private val tabPreviewCache: LruCache<String, android.graphics.Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxKb / 12).coerceAtLeast(2048)
        object : LruCache<String, android.graphics.Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int {
                return value.byteCount / 1024
            }
        }
    }
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var syncManager: AllowlistSyncManager
    private val loginPromptPrefs by lazy { getSharedPreferences("login_prefs", MODE_PRIVATE) }
    private var authChecked = false
    private var pendingLoginPrompt = false
    private var pendingSettingsPush = false
    private var pendingOpenUrl: String? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener {
                    syncManager.start()
                    loginPromptPrefs.edit().putBoolean("login_prompt_shown", true).apply()
                    bootstrapRemoteIfEmpty()
                    val uid = auth.currentUser?.uid ?: "?"
                    Toast.makeText(this, "${getString(R.string.sign_in)} ($uid)", Toast.LENGTH_SHORT).show()
                    FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .set(mapOf("lastLogin" to com.google.firebase.firestore.FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
                        .addOnFailureListener {
                            Toast.makeText(
                                this,
                                getString(R.string.error_firestore_write_failed, it.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
                .addOnFailureListener {
                    Toast.makeText(this, getString(R.string.error_sign_in_failed), Toast.LENGTH_SHORT).show()
                }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.error_sign_in_failed), Toast.LENGTH_SHORT).show()
        }
    }
    private val defaultBrowserRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageHelper.applySavedLocale(this)
        setContentView(R.layout.activity_main)
        auth = FirebaseAuth.getInstance()
        authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            authChecked = true
            val signedIn = firebaseAuth.currentUser != null
            if (pendingLoginPrompt && !signedIn) {
                pendingLoginPrompt = false
                showLoginPromptNow()
            }
        }
        auth.addAuthStateListener(authListener!!)
        setupGoogleSignIn()
        syncManager = AllowlistSyncManager(
            this,
            { onRemoteApplied() },
            { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        )
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) { }
        AllowlistHelper.ensureDefaultAllowlist(this)
        initUI()
        CloudflareFamilyDns.prefetchHost("www.google.com")
        CloudflareFamilyDns.prefetchHost("google.com")
        val step = OnboardingHelper.getOnboardingStep(this)
        if (step in 1..4) {
            startOnboardingFromStep(step)
        } else if (!AllowlistHelper.isLockDurationSet(this)) {
            showOnboardingWelcome()
        } else {
            maybeShowLoginPrompt()
        }
        handleIntent(intent)
        WebViewProxyManager.ensureProxyAndOverride { }
    }

    override fun onStart() {
        super.onStart()
        // Apply pending lock-duration change if its time has come.
        AllowlistHelper.getLockDurationMs(this)
        if (syncManager.isSignedIn()) {
            syncManager.start()
            bootstrapRemoteIfEmpty()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_SIGN_IN -> startSignIn()
            ACTION_SIGN_OUT -> {
                if (syncManager.isSignedIn()) {
                    syncManager.signOut()
                    Toast.makeText(this, getString(R.string.sign_out), Toast.LENGTH_SHORT).show()
                }
            }
        }
        val openUrl = intent.getStringExtra(EXTRA_OPEN_URL)
        if (!openUrl.isNullOrBlank()) {
            openUrlSmart(openUrl, openInNewTab = true)
            return
        }
        extractUrlFromIntent(intent)?.let { url ->
            openUrlSmart(url, openInNewTab = true)
        }
    }

    private fun extractUrlFromIntent(intent: Intent): String? {
        val dataUrl = intent.dataString
        if (!dataUrl.isNullOrBlank() && (dataUrl.startsWith("http://") || dataUrl.startsWith("https://"))) {
            return dataUrl
        }
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        extractUrlFromText(extraText)?.let { return it }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                val uriStr = item.uri?.toString()
                if (!uriStr.isNullOrBlank() && (uriStr.startsWith("http://") || uriStr.startsWith("https://"))) {
                    return uriStr
                }
                val text = item.text?.toString()
                extractUrlFromText(text)?.let { return it }
            }
        }
        return null
    }

    private fun extractUrlFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = URL_REGEX.matcher(text)
        if (!m.find()) return null
        val raw = m.group(1) ?: return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }


    private fun onRemoteApplied() {
        updateAllowlistButtonState()
        if (pendingSettingsPush && syncManager.isSettingsLoaded()) {
            pendingSettingsPush = false
            if (!syncManager.hasRemoteLockDuration()) {
                pushSettingsToServer()
            }
        }
    }

    override fun onStop() {
        syncManager.stop()
        super.onStop()
    }

    private fun startOnboardingFromStep(step: Int) {
        when (step) {
            1 -> showOnboardingWelcome()
            2 -> showOnboardingLanguage()
            3 -> showOnboardingDuration()
            4 -> showOnboardingDefaultBrowser()
            else -> showOnboardingWelcome()
        }
    }

    private fun showOnboardingWelcome() {
        OnboardingHelper.setOnboardingStep(this, 1)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_onboarding_message, null)
        view.findViewById<TextView>(R.id.onboarding_title).text = getString(R.string.welcome_title)
        view.findViewById<TextView>(R.id.onboarding_message).text = getString(R.string.welcome_message)
        view.findViewById<TextView>(R.id.onboarding_dots).text = buildDots(1, 4)

        AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_next) { _, _ ->
                OnboardingHelper.setOnboardingStep(this, 2)
                showOnboardingLanguage()
            }
            .show()
    }

    private fun showOnboardingLanguage() {
        OnboardingHelper.setOnboardingStep(this, 2)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_onboarding_language, null)
        view.findViewById<TextView>(R.id.onboarding_title).text = getString(R.string.onboarding_language_title)
        view.findViewById<TextView>(R.id.onboarding_message).text = getString(R.string.onboarding_language_message)
        view.findViewById<TextView>(R.id.onboarding_dots).text = buildDots(2, 4)

        val group = view.findViewById<RadioGroup>(R.id.language_group)
        val options = LanguageHelper.getLanguageOptions(this)
        val currentTag = LanguageHelper.getSavedLanguageTag(this)
        var checkedId = View.NO_ID
        for ((index, option) in options.withIndex()) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = option.label
            rb.layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            group.addView(rb)
            if (option.tag == currentTag || (currentTag.isBlank() && index == 0)) {
                checkedId = rb.id
            }
        }
        if (checkedId != View.NO_ID) group.check(checkedId)

        AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_next) { _, _ ->
                val selectedId = group.checkedRadioButtonId
                val selectedIndex = group.indexOfChild(group.findViewById(selectedId))
                val selectedTag = options.getOrNull(selectedIndex)?.tag ?: ""
                OnboardingHelper.setOnboardingStep(this, 3)
                LanguageHelper.setAppLanguage(this, selectedTag)
                recreate()
            }
            .setNegativeButton(R.string.onboarding_back) { _, _ ->
                OnboardingHelper.setOnboardingStep(this, 1)
                showOnboardingWelcome()
            }
            .show()
    }

    private fun showOnboardingDuration() {
        OnboardingHelper.setOnboardingStep(this, 3)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_onboarding_duration, null)
        view.findViewById<TextView>(R.id.onboarding_title).text = getString(R.string.lock_duration_set)
        view.findViewById<TextView>(R.id.onboarding_dots).text = buildDots(3, 4)

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
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_next) { _, _ ->
                val h = hoursPicker.value
                val m = minutesPicker.value
                val s = secondsPicker.value
                val ms = (h * 3600L + m * 60L + s) * 1000L
                AllowlistHelper.setLockDurationMs(this, if (ms > 0) ms else 60_000L)
                AllowlistHelper.setLockDurationSet(this)
                maybePushSettingsAfterOnboarding()
                OnboardingHelper.setOnboardingStep(this, 4)
                showOnboardingDefaultBrowser()
            }
            .setNegativeButton(R.string.onboarding_back) { _, _ ->
                OnboardingHelper.setOnboardingStep(this, 2)
                showOnboardingLanguage()
            }
            .show()
    }

    private fun showOnboardingDefaultBrowser() {
        OnboardingHelper.setOnboardingStep(this, 4)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_onboarding_message, null)
        view.findViewById<TextView>(R.id.onboarding_title).text = getString(R.string.onboarding_default_browser_title)
        view.findViewById<TextView>(R.id.onboarding_message).text = getString(R.string.onboarding_default_browser_message)
        view.findViewById<TextView>(R.id.onboarding_dots).text = buildDots(4, 4)

        AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.onboarding_default_browser_action) { _, _ ->
                completeOnboarding()
                requestDefaultBrowserRole()
            }
            .setNegativeButton(R.string.onboarding_finish) { _, _ ->
                completeOnboarding()
            }
            .show()
    }

    private fun completeOnboarding() {
        OnboardingHelper.setWelcomeShown(this)
        OnboardingHelper.clearOnboardingStep(this)
        maybeShowLoginPrompt()
    }

    private fun maybePushSettingsAfterOnboarding() {
        if (!syncManager.isSignedIn()) return
        if (!syncManager.isSettingsLoaded()) {
            pendingSettingsPush = true
            return
        }
        if (syncManager.hasRemoteLockDuration()) return
        pushSettingsToServer()
    }

    private fun buildDots(step: Int, total: Int): String {
        val sb = StringBuilder()
        for (i in 1..total) {
            if (i > 1) sb.append(" ")
            sb.append(if (i == step) "●" else "○")
        }
        return sb.toString()
    }

    private fun requestDefaultBrowserRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                    defaultBrowserRoleLauncher.launch(intent)
                    return
                }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Throwable) { }
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

    /** Settings: mevcut lock duration + Değiştir; artırma anında, düşürme mevcut süre sonra aktif. */
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
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun getPrimaryTextColor(): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        // TYPE_INT_COLOR = 28 (resolved color from theme)
        return if (typedValue.type == 28) {
            typedValue.data
        } else {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK
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

    private fun setupGoogleSignIn() {
        val webClientId = getWebClientId()
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (!webClientId.isNullOrBlank()) {
            builder.requestIdToken(webClientId)
        }
        val gso = builder.build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun startSignIn() {
        if (getWebClientId().isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.error_google_sign_in_not_configured), Toast.LENGTH_LONG).show()
            return
        }
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun getWebClientId(): String? {
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        return if (resId != 0) getString(resId) else null
    }

    private fun maybeShowLoginPrompt() {
        if (!authChecked) {
            pendingLoginPrompt = true
            return
        }
        if (syncManager.isSignedIn()) return
        if (loginPromptPrefs.getBoolean("login_prompt_shown", false)) return
        showLoginPromptNow()
    }

    private fun showLoginPromptNow() {
        if (syncManager.isSignedIn()) return
        if (loginPromptPrefs.getBoolean("login_prompt_shown", false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_in_title)
            .setMessage(R.string.sign_in_message)
            .setPositiveButton(R.string.sign_in) { _, _ ->
                loginPromptPrefs.edit().putBoolean("login_prompt_shown", true).apply()
                startSignIn()
            }
            .setNegativeButton(R.string.continue_without_account) { _, _ ->
                loginPromptPrefs.edit().putBoolean("login_prompt_shown", true).apply()
            }
            .show()
    }

    private fun pushSettingsToServer() {
        if (!syncManager.isSignedIn()) return
        val lockMs = AllowlistHelper.getLockDurationMs(this)
        val pending = AllowlistHelper.getPendingLockDuration(this)
        if (pending != null) {
            syncManager.pushSettings(lockMs, pending.first, pending.second)
        } else {
            syncManager.pushSettings(lockMs, null, null)
        }
    }

    private fun bootstrapRemoteIfEmpty() {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val hasLock = doc?.contains("lockDurationMs") == true
                if (!hasLock && AllowlistHelper.isLockDurationSet(this)) {
                    pushSettingsToServer()
                }
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    getString(R.string.error_firestore_read_failed, it.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        db.collection("users").document(user.uid).collection("allowlist").get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) return@addOnSuccessListener
                val allow = AllowlistHelper.getAllowlistWithTimestamps(this)
                val wait = AllowlistHelper.getWaitlistWithEndTimes(this)
                if (allow.isEmpty() && wait.isEmpty()) return@addOnSuccessListener
                val lockMs = AllowlistHelper.getLockDurationMs(this)
                val now = System.currentTimeMillis()
                val batch = db.batch()
                val col = db.collection("users").document(user.uid).collection("allowlist")
                for ((host, allowedAt) in allow) {
                    val requestedAtMs = (if (allowedAt > 0) allowedAt - lockMs else now - lockMs)
                        .coerceAtMost(now - 1000L)
                        .coerceAtLeast(0L)
                    val data = mapOf(
                        "host" to host,
                        "requestedAt" to Timestamp(Date(requestedAtMs))
                    )
                    batch.set(col.document(host), data)
                }
                for ((host, endMs) in wait) {
                    val requestedAtMs = (endMs - lockMs)
                        .coerceAtMost(now - 1000L)
                        .coerceAtLeast(0L)
                    val data = mapOf(
                        "host" to host,
                        "requestedAt" to Timestamp(Date(requestedAtMs))
                    )
                    batch.set(col.document(host), data)
                }
                batch.commit()
                    .addOnFailureListener {
                        Toast.makeText(
                            this,
                            getString(R.string.error_allowlist_bootstrap_failed, it.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .addOnSuccessListener {
                        db.collection("users").document(user.uid)
                            .set(
                                mapOf("bootstrapAllowlistAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                    }
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    getString(R.string.error_allowlist_read_failed, it.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun initUI() {
        urlBar = findViewById(R.id.url_bar)
        urlBar.isCursorVisible = true
        btnHome = findViewById(R.id.btn_home)
        btnLock = findViewById(R.id.btn_lock)
        btnAddTab = findViewById(R.id.btn_add_tab)
        btnAllowlist = findViewById(R.id.btn_allowlist)
        btnTabs = findViewById(R.id.btn_tabs)
        btnMore = findViewById(R.id.btn_more)
        bottomNav = findViewById(R.id.bottom_nav)
        viewPager = findViewById(R.id.view_pager)
        tabCountBadge = findViewById(R.id.tab_count_badge)
        loadProgress = findViewById(R.id.load_progress)
        toolbarUrlContainer = findViewById(R.id.toolbar_url_container)
        toolbarTabsFrame = findViewById(R.id.toolbar_tabs_frame)
        urlBar.isCursorVisible = true

        btnLock.setOnClickListener {
            try {
                startActivity(
                    Intent(this, app.secondway.lock.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (_: Exception) {
            }
        }

        val currentNavId = R.id.nav_browser
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
                        startActivity(Intent(this, SettingsActivity::class.java))
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {
                    }
                }
            }
            // Keep highlight consistent with the current screen.
            bottomNav.menu.findItem(currentNavId)?.isChecked = true
            false
        }

        if (tabs.isEmpty()) {
            tabs.add(TabItem(UUID.randomUUID().toString(), GOOGLE_SAFE_SEARCH_HOME, getString(R.string.new_tab_title)))
        }

        tabsAdapter = TabsAdapter(this, tabs)
        viewPager.adapter = tabsAdapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                tabs.getOrNull(position)?.let { updateUrlBarDisplay(it.url) }
                updateAllowlistButtonState()
            }
        })
        updateUrlBarDisplay(tabs.firstOrNull()?.url ?: "")
        updateTabCountBadge()

        suggestionPopup = ListPopupWindow(this).apply {
            setAnchorView(toolbarUrlContainer)
            setModal(false)
            setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED)
            setOnItemClickListener { parent, _, position, _ ->
                (parent?.adapter as? ArrayAdapter<*>)?.getItem(position)?.let { sug ->
                    urlBar.setText(sug.toString())
                    dismiss()
                    loadUrlFromBar()
                    hideKeyboard()
                }
            }
        }
        toolbarUrlContainer.post { updateSuggestionPopupLayout() }

        urlBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                suggestRunnable?.let { suggestHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim() ?: ""
                if (query.length < 2 || looksLikeUrl(query)) {
                    suggestionPopup?.dismiss()
                    return
                }
                suggestRunnable = Runnable {
                    fetchSuggestions(query)
                }
                suggestHandler.postDelayed(suggestRunnable!!, SUGGEST_DELAY_MS)
            }
        })
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                suggestionPopup?.dismiss()
                loadUrlFromBar()
                hideKeyboard()
                true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                urlBar.setText(getCurrentTabUrl())
                urlBarSelectAllActive = true
                urlBar.post { urlBar.selectAll() }
                setToolbarButtonsVisible(false)
            } else {
                urlBarSelectAllActive = false
                suggestionPopup?.dismiss()
                updateUrlBarDisplay(getCurrentTabUrl())
                setToolbarButtonsVisible(true)
            }
        }
        urlBar.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && urlBar.isFocused && urlBarSelectAllActive) {
                val textLen = urlBar.text?.length ?: 0
                if (textLen > 0 && urlBar.selectionStart == 0 && urlBar.selectionEnd == textLen) {
                    urlBar.setSelection(textLen)
                    urlBarSelectAllActive = false
                }
            }
            false
        }
        urlBar.setOnClickListener {
            if (!urlBar.isFocused) {
                urlBar.requestFocus()
                urlBar.post { urlBar.selectAll() }
            }
        }

        findViewById<View>(android.R.id.content).setOnTouchListener { _, event ->
            if (urlBar.isFocused && event.action == MotionEvent.ACTION_DOWN && !isTouchInsideView(event, urlBar)) {
                urlBar.clearFocus()
                hideKeyboard()
            }
            suggestionPopup?.dismiss()
            false
        }

        btnHome.setOnClickListener {
            currentWebView?.loadUrl(GOOGLE_SAFE_SEARCH_HOME)
        }
        btnAddTab.setOnClickListener {
            val newTab = TabItem(UUID.randomUUID().toString(), GOOGLE_SAFE_SEARCH_HOME, getString(R.string.new_tab_title))
            tabs.add(newTab)
            tabsAdapter.notifyItemInserted(tabs.size - 1)
            viewPager.setCurrentItem(tabs.size - 1, true)
            updateTabCountBadge()
        }
        btnAllowlist.setOnClickListener { onAllowlistButtonClick() }
        updateAllowlistButtonState()
        btnTabs.setOnClickListener {
            captureCurrentTabPreview()
            TabSwitcherDialogFragment().show(supportFragmentManager, TAB_SWITCHER_TAG)
        }

        btnMore.setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menu.add(0, 0, 0, getString(R.string.settings_menu))
                menu.add(0, 1, 1, getString(R.string.refresh))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        0 -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        1 -> currentWebView?.reload()
                    }
                    true
                }
                show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentWebView?.canGoBack() == true) {
                    currentWebView?.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    fun onContentTouched() {
        if (urlBar.isFocused) {
            urlBar.clearFocus()
            suggestionPopup?.dismiss()
            hideKeyboard()
        } else if (suggestionPopup?.isShowing == true) {
            suggestionPopup?.dismiss()
        }
    }

    private fun maybeAddHistoryEntry(url: String, title: String) {
        val lower = url.lowercase()
        if (lower.isBlank()) return
        if (lower.startsWith("data:")) return
        if (lower.startsWith("about:")) return
        if (lower.startsWith("file:")) return
        HistoryStore.addEntry(this, url, title)
    }

    override fun onDestroy() {
        authListener?.let { auth.removeAuthStateListener(it) }
        WebViewProxyManager.clearOverrideAndStop()
        super.onDestroy()
    }

    override fun onTabUrlChanged(tabId: String, url: String) {
        tabs.find { it.id == tabId }?.let { it.url = url }
        if (viewPager.currentItem < tabs.size && tabs[viewPager.currentItem].id == tabId) {
            updateUrlBarDisplay(url)
            updateAllowlistButtonState()
        }
        maybeAddHistoryEntry(url, tabs.find { it.id == tabId }?.title ?: "")
    }

    override fun onTabTitleChanged(tabId: String, title: String) {
        tabs.find { it.id == tabId }?.let { it.title = title.ifBlank { getString(R.string.new_tab_title) } }
    }

    override fun onLoadProgress(tabId: String, progress: Int) {
        if (viewPager.currentItem >= tabs.size || tabs[viewPager.currentItem].id != tabId) return
        loadProgress.progress = progress.coerceIn(0, 100)
        if (progress < 100) {
            loadProgress.visibility = View.VISIBLE
        } else {
            loadProgress.progress = 100
            loadProgress.postDelayed({ loadProgress.visibility = View.GONE }, 250)
        }
    }

    override fun getTabs(): List<TabItem> = tabs.toList()

    override fun getTabPreview(tabId: String): android.graphics.Bitmap? = tabPreviewCache.get(tabId)

    override fun onTabPreview(tabId: String, bitmap: android.graphics.Bitmap) {
        tabPreviewCache.put(tabId, bitmap)
    }

    override fun onTabSelected(position: Int) {
        if (position in tabs.indices) {
            viewPager.setCurrentItem(position, true)
        }
    }

    override fun onTabClosed(position: Int) {
        if (position !in tabs.indices) return
        val currentItem = viewPager.currentItem
        val removed = tabs.removeAt(position)
        tabPreviewCache.remove(removed.id)
        tabsAdapter.notifyItemRemoved(position)
        (supportFragmentManager.findFragmentByTag(TAB_SWITCHER_TAG) as? TabSwitcherDialogFragment)?.updateTabs()
        if (tabs.isEmpty()) {
            currentWebView = null
            updateUrlBarDisplay("")
        } else {
            val newIndex = when {
                currentItem > position -> currentItem - 1
                currentItem == position -> (position - 1).coerceAtLeast(0)
                else -> currentItem
            }
            viewPager.setCurrentItem(newIndex, false)
            updateUrlBarDisplay(tabs[newIndex].url)
        }
        updateTabCountBadge()
    }

    override fun onNewTabRequested() {
        val newTab = TabItem(UUID.randomUUID().toString(), GOOGLE_SAFE_SEARCH_HOME, getString(R.string.new_tab_title))
        tabs.add(newTab)
        tabsAdapter.notifyItemInserted(tabs.size - 1)
        viewPager.setCurrentItem(tabs.size - 1, true)
        updateTabCountBadge()
    }

    private fun updateTabCountBadge() {
        val count = tabs.size
        tabCountBadge.text = if (count > 99) "99+" else count.toString()
    }

    private fun getCurrentTabUrl(): String = tabs.getOrNull(viewPager.currentItem)?.url ?: ""

    /** Odak yokken gösterim: https://, http:// ve www. kaldırılır (Chrome mobil gibi). */
    private fun shortUrlForDisplay(url: String): String {
        if (url.isBlank()) return url
        var u = url.trim()
        if (u.startsWith("https://", ignoreCase = true)) u = u.removePrefix("https://").removePrefix("HTTPS://")
        else if (u.startsWith("http://", ignoreCase = true)) u = u.removePrefix("http://").removePrefix("HTTP://")
        if (u.startsWith("www.", ignoreCase = true)) u = u.removePrefix("www.").removePrefix("WWW.")
        return u.ifBlank { url }
    }

    /** Sekme URL'sine göre çubuk metnini günceller: odak yokken kısa, odak varken tam URL. */
    private fun updateUrlBarDisplay(fullUrl: String) {
        urlBar.setText(if (urlBar.isFocused) fullUrl else shortUrlForDisplay(fullUrl))
    }

    /** Toolbar'daki URL dışı butonları göster/gizle (focus'ta tam satır URL alanı). */
    private fun setToolbarButtonsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        btnHome.visibility = visibility
        btnAddTab.visibility = visibility
        btnAllowlist.visibility = visibility
        toolbarTabsFrame.visibility = visibility
        btnMore.visibility = visibility
        btnLock.visibility = visibility
    }

    fun setCurrentWebView(webView: WebView?) {
        currentWebView = webView
        pendingOpenUrl?.let { url ->
            webView?.loadUrl(url)
            updateUrlBarDisplay(url)
            updateAllowlistButtonState()
            pendingOpenUrl = null
        }
    }

    fun clearCurrentWebView(webView: WebView?) {
        if (currentWebView == webView) currentWebView = null
    }

    private fun openUrlInCurrentTabDirect(url: String) {
        val webView = currentWebView
        if (webView == null) {
            pendingOpenUrl = url
            return
        }
        webView.loadUrl(url)
        updateUrlBarDisplay(url)
        updateAllowlistButtonState()
    }

    private fun openUrlInNewTabDirect(url: String) {
        val newTab = TabItem(UUID.randomUUID().toString(), url, getString(R.string.new_tab_title))
        tabs.add(newTab)
        tabsAdapter.notifyItemInserted(tabs.size - 1)
        viewPager.setCurrentItem(tabs.size - 1, true)
        updateTabCountBadge()
        updateUrlBarDisplay(url)
        updateAllowlistButtonState()
    }

    private fun openUrlSmart(url: String, openInNewTab: Boolean = false) {
        val target = if (looksLikeUrl(url) && !url.startsWith("http")) "https://$url" else url
        if (shouldResolveHttpRedirect(target)) {
            Log.i(resolveTag, "resolve_start url=$target")
            resolveAndOpenUrl(target, openInNewTab)
        } else {
            Log.i(resolveTag, "resolve_skip url=$target")
            if (openInNewTab) {
                openUrlInNewTabDirect(target)
            } else {
                openUrlInCurrentTabDirect(target)
            }
        }
    }

    private fun shouldResolveHttpRedirect(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true)
    }

    private fun resolveAndOpenUrl(original: String, openInNewTab: Boolean) {
        Thread {
            val resolved = resolveRedirectUrl(original) ?: original
            runOnUiThread {
                if (openInNewTab) {
                    openUrlInNewTabDirect(resolved)
                } else {
                    openUrlInCurrentTabDirect(resolved)
                }
            }
        }.start()
    }

    private fun resolveRedirectUrl(original: String): String? {
        return try {
            val ua = currentWebView?.settings?.userAgentString ?: System.getProperty("http.agent") ?: ""
            val client = resolveClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            var current = original
            repeat(6) { step ->
                val getReq = Request.Builder()
                    .url(current)
                    .get()
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", Locale.getDefault().toLanguageTag())
                    .build()
                client.newCall(getReq).execute().use { resp ->
                    saveCookiesFromResponseChain(resp)
                    Log.i(resolveTag, "resolve_get step=$step url=$current code=${resp.code}")
                    val location = resp.header("Location")
                    if (!location.isNullOrBlank()) {
                        val resolved = resolveRelativeUrl(current, location)
                        if (!resolved.isNullOrBlank()) {
                            if (shouldStopAtResolved(original, resolved, step)) {
                                Log.i(resolveTag, "resolve_stop step=$step url=$current final=$resolved")
                                return resolved
                            }
                            Log.i(resolveTag, "resolve_location step=$step url=$current final=$resolved")
                            current = resolved
                            return@use
                        }
                    }
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        val snippet = body.replace("\\s+".toRegex(), " ").take(160)
                        Log.i(resolveTag, "resolve_body step=$step url=$current len=${body.length} snippet=$snippet")
                        extractRedirectFromHtml(current, body)?.let { extracted ->
                            if (shouldStopAtResolved(original, extracted, step)) {
                                Log.i(resolveTag, "resolve_stop_html step=$step url=$current final=$extracted")
                                return extracted
                            }
                            Log.i(resolveTag, "resolve_html step=$step url=$current final=$extracted")
                            current = extracted
                            return@use
                        }
                    } else {
                        Log.i(resolveTag, "resolve_body step=$step url=$current len=0")
                    }
                    // No redirect hints; stop.
                    return current
                }
            }
            current
        } catch (_: Exception) {
            Log.w(resolveTag, "resolve_failed url=$original")
            null
        }
    }

    private fun shouldStopAtResolved(original: String, resolved: String, step: Int): Boolean {
        val origHost = AllowlistHelper.hostFromUrl(original)
        val resHost = AllowlistHelper.hostFromUrl(resolved)
        if (resolved.contains("code=", ignoreCase = true)) return true
        if (!origHost.isNullOrBlank() && !resHost.isNullOrBlank() && !origHost.equals(resHost, ignoreCase = true)) {
            if (resolved.contains("?")) return true
        }
        return false
    }

    private fun extractRedirectFromHtml(baseUrl: String, html: String): String? {
        val decoded = html.replace("&amp;", "&")
        val meta = Regex(
            "(?i)<meta[^>]*http-equiv=[\"']?refresh[\"']?[^>]*content=[\"'][^\"']*url=([^\"'>]+)",
            RegexOption.IGNORE_CASE
        ).find(decoded)?.groupValues?.getOrNull(1)
        val js = Regex(
            "(?i)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)",
            RegexOption.IGNORE_CASE
        ).find(decoded)?.groupValues?.getOrNull(1)
        val anchor = Regex(
            "(?i)<a[^>]*href=[\"']([^\"']+)",
            RegexOption.IGNORE_CASE
        ).find(decoded)?.groupValues?.getOrNull(1)
        val raw = meta ?: js ?: anchor ?: return null
        return resolveRelativeUrl(baseUrl, raw)
    }

    private fun resolveRelativeUrl(baseUrl: String, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return try {
            val base = java.net.URI(baseUrl)
            base.resolve(trimmed).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCookiesFromResponseChain(response: okhttp3.Response) {
        var r: okhttp3.Response? = response
        while (r != null) {
            saveCookiesFromResponseSingle(r.request.url.toString(), r)
            r = r.priorResponse
        }
        try { CookieManager.getInstance().flush() } catch (_: Exception) { }
    }

    private fun saveCookiesFromResponseSingle(url: String, response: okhttp3.Response) {
        try {
            val cookieManager = CookieManager.getInstance()
            val headers = response.headers
            for (i in 0 until headers.size) {
                if (headers.name(i).equals("Set-Cookie", ignoreCase = true)) {
                    val value = headers.value(i)
                    if (value.isNotBlank()) cookieManager.setCookie(url, value)
                }
            }
        } catch (_: Exception) { }
    }

    private fun captureCurrentTabPreview() {
        val webView = currentWebView ?: return
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return
        val targetWidth = 360
        val targetHeight = 240
        val scale = minOf(
            targetWidth.toFloat() / width.toFloat(),
            targetHeight.toFloat() / height.toFloat(),
            1f
        )
        val bmpW = (width * scale).toInt().coerceAtLeast(1)
        val bmpH = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(scale, scale)
        webView.draw(canvas)
        val tabId = tabs.getOrNull(viewPager.currentItem)?.id ?: return
        tabPreviewCache.put(tabId, bitmap)
    }

    private fun loadUrlFromBar() {
        val text = urlBar.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        var url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            looksLikeUrl(text) -> "https://$text"
            else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(text, "UTF-8")}&safe=active"
        }
        url = GoogleSafeSearchHelper.ensureUrl(url)
        AmpUrlHelper.unwrap(url)?.let { url = it }
        AllowlistHelper.hostFromUrl(url)?.let { CloudflareFamilyDns.prefetchHost(it) }
        suggestionPopup?.dismiss()
        if (BlockedSearchEngines.isBlocked(url)) {
            openUrlInCurrentTabDirect(BlockedSearchEngines.getBlockPageDataUrl(this))
        } else {
            openUrlSmart(url)
        }
        urlBar.clearFocus()
        hideKeyboard()
    }

    private fun fetchSuggestions(query: String) {
        Thread {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val conn = URL("$SUGGEST_URL$encoded").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept-Charset", "utf-8")
                val charset = connectionCharset(conn)
                val json = conn.inputStream.bufferedReader(charset).readText()
                conn.disconnect()
                val arr = JSONArray(json)
                if (arr.length() < 2) return@Thread
                val suggestions = arr.getJSONArray(1)
                val list = mutableListOf<String>()
                for (i in 0 until suggestions.length()) {
                    val item = suggestions.get(i)
                    list.add(if (item is String) item else suggestions.getJSONArray(i).getString(0))
                }
                runOnUiThread {
                    if (urlBar.text?.toString()?.trim() != query) return@runOnUiThread
                    val limited = list.take(3)
                    if (limited.isEmpty()) return@runOnUiThread
                    updateSuggestionPopupLayout()
                    suggestionPopup?.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, limited))
                    suggestionPopup?.show()
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun connectionCharset(conn: HttpURLConnection): Charset {
        val contentType = conn.contentType ?: return Charsets.UTF_8
        val match = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(contentType)
        val name = match?.groupValues?.getOrNull(1)?.trim()?.trim('"') ?: return Charsets.UTF_8
        return try { Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }
    }

    private fun updateSuggestionPopupLayout() {
        val popup = suggestionPopup ?: return
        val anchor = toolbarUrlContainer
        popup.setAnchorView(anchor)
        val width = urlBar.width
        if (width > 0) popup.width = width
        popup.horizontalOffset = urlBar.left
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = currentFocus?.windowToken ?: urlBar.windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun isTouchInsideView(event: MotionEvent, view: View): Boolean {
        val rect = android.graphics.Rect()
        view.getGlobalVisibleRect(rect)
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        return rect.contains(x, y)
    }

    private fun showHistoryDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_history, null)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_history)
        val empty = view.findViewById<TextView>(R.id.history_empty)
        val items = HistoryStore.getEntries(this).toMutableList()
        lateinit var adapter: HistoryAdapter
        adapter = HistoryAdapter(items, { entry ->
            currentWebView?.loadUrl(entry.url)
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
            currentWebView?.clearHistory()
        }
        if (clearCookies) {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            } catch (_: Exception) { }
            try {
                WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) { }
            try {
                WebViewDatabase.getInstance(this).clearFormData()
                WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
            } catch (_: Exception) { }
        }
        if (clearCache) {
            try {
                currentWebView?.clearCache(true)
            } catch (_: Exception) { }
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

    fun handleDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val safeUrl = url ?: return
        val host = AllowlistHelper.hostFromUrl(safeUrl)
        if (host == null || !AllowlistHelper.isHostAllowlisted(this, host)) {
            Toast.makeText(this, getString(R.string.download_blocked_not_allowlisted), Toast.LENGTH_SHORT).show()
            return
        }
        val request = DownloadManager.Request(Uri.parse(safeUrl))
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        try {
            val cookie = CookieManager.getInstance().getCookie(safeUrl)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
        } catch (_: Exception) { }
        val fileName = URLUtil.guessFileName(safeUrl, contentDisposition, mimeType)
        request.setTitle(fileName)
        request.setDescription(safeUrl)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
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

    private fun looksLikeUrl(text: String): Boolean =
        text.contains(".") && !text.contains(" ")

    private fun showAboutDialog() {
        val versionName: String
        val updatedStr: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pkg = WebView.getCurrentWebViewPackage()
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

    private fun updateAllowlistButtonState() {
        val currentUrl = currentWebView?.url ?: ""
        val host = AllowlistHelper.hostFromUrl(currentUrl)
        val isAllowlisted = host != null && AllowlistHelper.isHostAllowlisted(this, host)
        val inWaitlist = host != null && AllowlistHelper.getWaitlistRemainingMs(this, host) != null
        val showRemove = isAllowlisted || inWaitlist
        btnAllowlist.setImageResource(
            if (showRemove) R.drawable.ic_allowlist_remove else R.drawable.ic_allowlist_add
        )
        btnAllowlist.contentDescription = when {
            isAllowlisted -> getString(R.string.allowlist_remove_site)
            inWaitlist -> getString(R.string.allowlist_remove_from_waitlist)
            else -> getString(R.string.allowlist_add_site)
        }
    }

    private fun onAllowlistButtonClick() {
        val currentUrl = currentWebView?.url ?: ""
        val rawHost = AllowlistHelper.hostFromUrl(currentUrl)
        val host = AllowlistHelper.canonicalHost(rawHost) ?: run {
            Toast.makeText(this, getString(R.string.allowlist_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }
        if (AllowlistHelper.isHostAllowlisted(this, host)) {
            AllowlistHelper.removeHost(this, host)
            Toast.makeText(this, getString(R.string.allowlist_removed), Toast.LENGTH_SHORT).show()
            if (syncManager.isSignedIn()) syncManager.pushRemoveHost(host) else {
                Toast.makeText(this, getString(R.string.error_not_signed_in_local_only), Toast.LENGTH_SHORT).show()
            }
            updateAllowlistButtonState()
            currentWebView?.reload()
        } else if (AllowlistHelper.getWaitlistRemainingMs(this, host) != null) {
            AllowlistHelper.removeFromWaitlist(this, host)
            Toast.makeText(this, getString(R.string.allowlist_removed_from_waitlist), Toast.LENGTH_SHORT).show()
            if (syncManager.isSignedIn()) syncManager.pushRemoveHost(host) else {
                Toast.makeText(this, getString(R.string.error_not_signed_in_local_only), Toast.LENGTH_SHORT).show()
            }
            updateAllowlistButtonState()
        } else {
            if (AllowlistHelper.addHost(this, host)) {
                Toast.makeText(this, getString(R.string.allowlist_added_to_waitlist), Toast.LENGTH_LONG).show()
                if (syncManager.isSignedIn()) syncManager.pushAddHost(host) else {
                    Toast.makeText(this, getString(R.string.error_not_signed_in_local_only), Toast.LENGTH_SHORT).show()
                }
                updateAllowlistButtonState()
            } else {
                Toast.makeText(this, getString(R.string.allowlist_already), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var allowlistCountdownHandler: Handler? = null
    private var allowlistCountdownRunnable: Runnable? = null

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

        allowlistCountdownHandler = Handler(Looper.getMainLooper())
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
}
