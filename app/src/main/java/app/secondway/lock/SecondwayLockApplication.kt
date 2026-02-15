package app.secondway.lock

import android.app.Application

class SecondwayLockApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LanguageHelper.applySavedLocale(this)
        try {
            val selfPkg = packageName
            // Safety: never allow our own package to be tracked/blocked as a "newly installed app".
            NewAppLockStore.removeTrackedPackage(this, selfPkg)
            PolicyHelper.setAppBlocked(this, selfPkg, false)
        } catch (_: Throwable) {
        }
    }
}
