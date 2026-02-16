package com.secondwaybrowser.app

import android.view.View
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.secondway.lock.R
import org.junit.Rule
import org.junit.Test

/**
 * Fast, emulator-free UI snapshots.
 * Goal: give us stable screenshots for polish/typography without waiting for an emulator.
 */
class PaparazziScreenshotsTest {

    private val device = DeviceConfig.PIXEL_6

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = device,
        // Paparazzi expects a theme name (not an @style/... reference).
        theme = "Theme.SafeBrowser",
        // Required for Material/AppCompat widgets (BottomNavigationView, MaterialButton, etc.).
        appCompatEnabled = true
    )

    private fun useTheme(theme: String) {
        paparazzi.unsafeUpdateConfig(deviceConfig = device, theme = theme)
    }

    @Test
    fun splash() {
        useTheme("Theme.SafeBrowser.Splash")
        val view: View = paparazzi.inflate(R.layout.activity_splash)
        paparazzi.snapshot(view, "01_splash")
    }

    @Test
    fun onboarding_shell() {
        useTheme("Theme.SafeBrowser")
        val view: View = paparazzi.inflate(R.layout.activity_onboarding)
        paparazzi.snapshot(view, "02_onboarding_shell")
    }

    @Test
    fun settings() {
        useTheme("Theme.SafeBrowser")
        val view: View = paparazzi.inflate(R.layout.activity_settings)
        paparazzi.snapshot(view, "03_settings")
    }

    @Test
    fun guard_intervention() {
        useTheme("Theme.SecondwayLock")
        val view: View = paparazzi.inflate(R.layout.activity_guard_intervention)
        paparazzi.snapshot(view, "04_guard_intervention")
    }

    /** Browser main is WebView-heavy; we still snapshot the chrome (toolbar + bottom nav) shell. */
    @Test
    fun browser_main_shell() {
        useTheme("Theme.SafeBrowser")
        val view: View = paparazzi.inflate(R.layout.activity_main)
        paparazzi.snapshot(view, "05_browser_main_shell")
    }

    @Test
    fun lock_main() {
        useTheme("Theme.SecondwayLock")
        val view: View = paparazzi.inflate(R.layout.lock_activity_main)
        paparazzi.snapshot(view, "06_lock_main")
    }

    @Test
    fun lock_settings() {
        useTheme("Theme.SecondwayLock")
        val view: View = paparazzi.inflate(R.layout.lock_activity_settings)
        paparazzi.snapshot(view, "07_lock_settings")
    }
}
