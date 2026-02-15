package app.secondway.lock

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object GuardServiceHelper {

    fun isAccessibilityGuardEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = ComponentName(context, AccessibilityGuardService::class.java).flattenToString()
        for (info in enabled) {
            val id = info.resolveInfo.serviceInfo?.let {
                ComponentName(it.packageName, it.name).flattenToString()
            } ?: continue
            if (id == target) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
