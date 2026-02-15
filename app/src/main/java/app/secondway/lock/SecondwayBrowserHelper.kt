package app.secondway.lock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager

object SecondwayBrowserHelper {

    private const val DOWNLOAD_URL =
        "https://github.com/muhalitas/Secondway-release/releases/download/v1.0.8/secondway-browser-v1.0.8.apk"

    fun isInstalled(context: Context): Boolean {
        val pm = context.packageManager
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

    fun openDownload(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
