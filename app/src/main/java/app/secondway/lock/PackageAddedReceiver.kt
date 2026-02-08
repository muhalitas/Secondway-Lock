package app.secondway.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Yeni paket eklendiğinde tek fonksiyonu çağırır: suspend + listeye kapalı yazdırma. */
class PackageAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        // Avoid losing work if the process is killed right after returning from onReceive.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                // PackageManager can briefly lag behind the broadcast; retry a few times.
                var ok = false
                for (attempt in 1..6) {
                    ok = PolicyHelper.onNewPackageInstalled(appContext, pkg)
                    if (ok) break
                    try {
                        Thread.sleep(250L * attempt)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                if (!ok) {
                    // Ensure we eventually lock it even if the initial window misses.
                    NewAppLockStore.setBlocked(appContext, pkg)
                    NewAppLockStore.addNewPackage(appContext, pkg)
                    NewAppBlockRetryScheduler.schedule(appContext, pkg, 1)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
