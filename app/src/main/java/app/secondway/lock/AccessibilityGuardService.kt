package app.secondway.lock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

class AccessibilityGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastInterventionAt = 0L
    private var overlayView: View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!PolicyHelper.isFactoryResetRestriction(this)) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return
        if (pkg == packageName) return

        val eventBlob = eventBlobLower(event)
        val windowBlob = if (pkg in SETTINGS_PACKAGES || pkg in TAMPER_PACKAGES) {
            rootWindowBlobLower()
        } else {
            ""
        }
        val combinedBlob = buildString {
            if (eventBlob.isNotBlank()) append(eventBlob)
            if (windowBlob.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(windowBlob)
            }
        }.trim()
        val classLower = event.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        val overlayEnabled = GuardServiceHelper.canDrawOverlays(this)
        val appNameLower = getString(R.string.app_name).lowercase(Locale.getDefault())
        val guardLabelLower = getString(R.string.settings_enable_accessibility_guard).lowercase(Locale.getDefault())
        val packageLower = packageName.lowercase(Locale.getDefault())

        // Clear any stale setup gate once permission is granted.
        if (overlayEnabled) {
            GuardTamperGate.clearOverlayGrantFlow(this)
        }

        if (isFactoryResetScreen(event, pkg)) {
            intervene(getString(R.string.guard_intervention_factory_reset_message))
            return
        }

        // During initial overlay-permission setup, briefly allow Settings screens related to
        // granting the permission. Once permission is granted, protect again.
        if (!overlayEnabled && GuardTamperGate.isOverlayGrantFlowActive(this) && pkg in SETTINGS_PACKAGES) {
            val mentionsSelf =
                combinedBlob.contains(packageLower) ||
                    combinedBlob.contains(appNameLower) ||
                    combinedBlob.contains(guardLabelLower)

            // Don't allow actual uninstall/disable flows even during setup.
            val hasStrongTamper = TAMPER_KEYWORDS.any { combinedBlob.contains(it) }

            val looksLikeOverlayOrPermission =
                GuardTamperGate.isOverlayGrantFlowInEarlyPhase(this) ||
                    isOverlayPermissionScreen(pkg, combinedBlob, classLower) ||
                    OVERLAY_GATE_KEYWORDS.any { combinedBlob.contains(it) }

            if (mentionsSelf && looksLikeOverlayOrPermission && !hasStrongTamper) return
        }

        if (isSelfProtectionScreen(event, pkg, eventBlob, windowBlob)) {
            intervene(getString(R.string.guard_intervention_tamper_message))
            return
        }

        if (PolicyHelper.isAppBlocked(this, pkg)) {
            val label = appLabelOrPackage(pkg)
            val msg = getString(R.string.guard_intervention_blocked_message, label)
            intervene(msg)
        }
    }

    override fun onInterrupt() = Unit

    private fun intervene(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastInterventionAt < INTERVENTION_DEBOUNCE_MILLIS) return
        lastInterventionAt = now

        if (GuardServiceHelper.canDrawOverlays(this)) {
            showOverlayIntervention(message)
        } else {
            try {
                val intent = Intent(this, GuardInterventionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(GuardInterventionActivity.EXTRA_MESSAGE, message)
                startActivity(intent)
            } catch (_: Throwable) {
            }
        }

        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {
        }
        handler.postDelayed({
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (_: Throwable) {
            }
        }, 120L)
    }

    private fun showOverlayIntervention(message: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        hideOverlayIntervention()

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
        }
        val title = TextView(this).apply {
            text = getString(R.string.guard_intervention_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
        }
        val body = TextView(this).apply {
            text = message
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 15f
            setLineSpacing(2f, 1f)
            setPadding(0, 10.dp(), 0, 0)
        }
        card.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            wm.addView(root, params)
            overlayView = root
            handler.postDelayed({ hideOverlayIntervention() }, OVERLAY_DISPLAY_MILLIS)
        } catch (_: Throwable) {
            hideOverlayIntervention()
        }
    }

    private fun hideOverlayIntervention() {
        val view = overlayView ?: return
        overlayView = null
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlayIntervention()
        super.onDestroy()
    }

    private fun appLabelOrPackage(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString().ifBlank { pkg }
        } catch (_: Exception) {
            pkg
        }
    }

    private fun isSelfProtectionScreen(
        event: AccessibilityEvent,
        packageNameOfEvent: String,
        eventBlob: String,
        windowBlob: String
    ): Boolean {
        if (packageNameOfEvent !in TAMPER_PACKAGES) return false

        val blob = buildString {
            if (eventBlob.isNotBlank()) append(eventBlob)
            if (windowBlob.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(windowBlob)
            }
        }.trim()
        if (blob.isBlank()) return false
        val classLower = event.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()

        val appNameLower = getString(R.string.app_name).lowercase(Locale.getDefault())
        val guardLabelLower = getString(R.string.settings_enable_accessibility_guard).lowercase(Locale.getDefault())
        val packageLower = packageName.lowercase(Locale.getDefault())

        val mentionsSelf = blob.contains(packageLower) || blob.contains(appNameLower) || blob.contains(guardLabelLower)
        val mentionsTamperAction = TAMPER_KEYWORDS.any { blob.contains(it) }
        val classHintsTamper = CLASS_HINTS.any { classLower.contains(it) }

        // Extra: accessibility settings screens where the user can toggle the guard OFF.
        val looksLikeAccessibilityScreen =
            ACCESSIBILITY_CLASS_HINTS.any { classLower.contains(it) } || ACCESSIBILITY_KEYWORDS.any { blob.contains(it) }

        // Extra: overlay permission ("appear on top") screens where the user can turn it OFF.
        val looksLikeOverlayScreen =
            OVERLAY_CLASS_HINTS.any { classLower.contains(it) } || OVERLAY_KEYWORDS.any { blob.contains(it) }

        val mentionsSelfInWindow = mentionsSelf
        return mentionsSelfInWindow && (mentionsTamperAction || classHintsTamper || looksLikeAccessibilityScreen || looksLikeOverlayScreen)
    }

    private fun isOverlayPermissionScreen(
        packageNameOfEvent: String,
        blob: String,
        classLower: String
    ): Boolean {
        if (packageNameOfEvent !in TAMPER_PACKAGES) return false
        if (blob.isBlank()) return false

        val appNameLower = getString(R.string.app_name).lowercase(Locale.getDefault())
        val guardLabelLower = getString(R.string.settings_enable_accessibility_guard).lowercase(Locale.getDefault())
        val packageLower = packageName.lowercase(Locale.getDefault())

        val mentionsSelf = blob.contains(packageLower) || blob.contains(appNameLower) || blob.contains(guardLabelLower)
        if (!mentionsSelf) return false

        val looksLikeOverlayScreen =
            OVERLAY_CLASS_HINTS.any { classLower.contains(it) } || OVERLAY_KEYWORDS.any { blob.contains(it) }
        return looksLikeOverlayScreen
    }

    private fun isFactoryResetScreen(event: AccessibilityEvent, packageNameOfEvent: String): Boolean {
        if (packageNameOfEvent !in SETTINGS_PACKAGES) return false
        val blob = eventBlobLower(event)
        if (blob.isBlank()) return false
        val classLower = event.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()

        val strong = FACTORY_RESET_STRONG_KEYWORDS.any { blob.contains(it) }
        val classHint = FACTORY_RESET_CLASS_HINTS.any { classLower.contains(it) }
        return strong || classHint
    }

    private fun eventBlobLower(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        val classLower = event.className?.toString()?.lowercase(Locale.getDefault()).orEmpty()
        if (classLower.isNotBlank()) parts.add(classLower)
        val cd = event.contentDescription?.toString()
        if (!cd.isNullOrBlank()) parts.add(cd.lowercase(Locale.getDefault()))
        for (txt in event.text) {
            if (txt != null) parts.add(txt.toString().lowercase(Locale.getDefault()))
        }
        return parts.joinToString(" ").trim()
    }

    private fun rootWindowBlobLower(): String {
        val root = rootInActiveWindow ?: return ""
        val parts = ArrayList<String>(128)
        val maxNodes = 600
        val maxChars = 8000
        val q: ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> = ArrayDeque()
        q.add(root)
        var nodes = 0
        var chars = 0
        while (q.isNotEmpty() && nodes < maxNodes && chars < maxChars) {
            val n = q.removeFirst()
            nodes++
            val t = n.text?.toString()
            if (!t.isNullOrBlank()) {
                val s = t.lowercase(Locale.getDefault())
                parts.add(s)
                chars += s.length + 1
            }
            val cd = n.contentDescription?.toString()
            if (!cd.isNullOrBlank()) {
                val s = cd.lowercase(Locale.getDefault())
                parts.add(s)
                chars += s.length + 1
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                q.add(c)
            }
        }
        return parts.joinToString(" ")
    }

    companion object {
        private const val INTERVENTION_DEBOUNCE_MILLIS = 1300L
        private const val OVERLAY_DISPLAY_MILLIS = 2300L

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings"
        )

        private val TAMPER_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.securitycenter"
        )

        private val ACCESSIBILITY_CLASS_HINTS = listOf(
            "accessibility"
        )

        private val ACCESSIBILITY_KEYWORDS = listOf(
            "accessibility",
            "installed services",
            "downloaded apps",
            "yuklenen hizmetler",
            "yüklenen hizmetler",
            "indirilen uygulamalar",
            "kullanim kolayligi",
            "kullanım kolaylığı",
            "erisilebilirlik",
            "erişilebilirlik"
        )

        private val OVERLAY_CLASS_HINTS = listOf(
            "overlay",
            "draw",
            "appops",
            "specialaccess"
        )

        private val OVERLAY_KEYWORDS = listOf(
            // English
            "display over other apps",
            "appear on top",
            "draw over other apps",

            // Turkish
            "ustte goster",
            "üstte göster",
            "ustte gorun",
            "üstte görün",
            "ustte gorunebilen",
            "üstte görünebilen",
            "ustte gorunebilir",
            "üstte görünebilir",
            "ustte goruntule",
            "üstte görüntüle",
            "diger uygulamalarin uzerinde",
            "diğer uygulamaların üzerinde",
            "diger uygulamalar uzerinde",
            "diğer uygulamalar üzerinde",
            "diger uygulamalarin ustunde",
            "diğer uygulamaların üstünde",
            "diger uygulamalar ustunde",
            "diğer uygulamalar üstünde"
        )

        private val OVERLAY_GATE_KEYWORDS = listOf(
            // Generic allow/deny vocabulary that often appears on permission pages (OEM-dependent).
            "allow",
            "allowed",
            "not allowed",
            "permission",
            "izin",
            "izin ver",
            "izin verme",
            "izinli",
            "izinsiz",
            "izin verildi",
            "izin verilmedi"
        )

        private val TAMPER_KEYWORDS = listOf(
            "uninstall",
            "remove",
            "delete",
            "disable",
            "deactivate",
            "kaldir",
            "kaldır",
            "sil",
            "devre disi",
            "devre dışı",
            "force stop"
        )

        private val CLASS_HINTS = listOf(
            "uninstall",
            "appinfo",
            "installedappdetails",
            "manageapplications",
            "applicationsettings",
            "pkg"
        )

        private val FACTORY_RESET_STRONG_KEYWORDS = listOf(
            // English
            "factory reset",
            "factory data reset",
            "erase all data",
            "erase all",
            "reset options",
            "reset this phone",
            "master reset",
            "master clear",

            // Turkish
            "fabrika ayar",
            "fabrika ayarlarına",
            "fabrika ayarlarına sıfırla",
            "fabrika ayarlarına sifirla",
            "sıfırla",
            "sifirla",
            "telefonu sıfırla",
            "telefonu sifirla",
            "tüm verileri sil",
            "tum verileri sil",
            "verileri sil"
        )

        private val FACTORY_RESET_CLASS_HINTS = listOf(
            "masterclear",
            "factoryreset",
            "resetoptions",
            "erase",
            "wipe"
        )
    }
}
