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

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        // Paparazzi expects a theme name (not an @style/... reference).
        theme = "Theme.SafeBrowser",
        // Required for Material/AppCompat widgets (BottomNavigationView, MaterialButton, etc.).
        appCompatEnabled = true
    )

    @Test
    fun splash() {
        val view: View = paparazzi.inflate(R.layout.activity_splash)
        paparazzi.snapshot(view, "01_splash")
    }

    @Test
    fun onboarding_shell() {
        val view: View = paparazzi.inflate(R.layout.activity_onboarding)
        paparazzi.snapshot(view, "02_onboarding_shell")
    }

    @Test
    fun settings() {
        val view: View = paparazzi.inflate(R.layout.activity_settings)
        paparazzi.snapshot(view, "03_settings")
    }

    @Test
    fun guard_intervention() {
        val view: View = paparazzi.inflate(R.layout.activity_guard_intervention)
        paparazzi.snapshot(view, "04_guard_intervention")
    }

    /** Browser main is WebView-heavy; we still snapshot the chrome (toolbar + bottom nav) shell. */
    @Test
    fun browser_main_shell() {
        val view: View = paparazzi.inflate(R.layout.activity_main)
        paparazzi.snapshot(view, "05_browser_main_shell")
    }
}
