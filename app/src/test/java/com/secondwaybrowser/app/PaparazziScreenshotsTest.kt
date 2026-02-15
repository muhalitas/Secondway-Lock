package com.secondwaybrowser.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
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
        theme = "@style/Theme.SafeBrowser"
    )

    @Test
    fun splash() {
        paparazzi.snapshot(name = "01_splash") {
            it.inflate(R.layout.activity_splash)
        }
    }

    @Test
    fun onboarding_shell() {
        paparazzi.snapshot(name = "02_onboarding_shell") {
            it.inflate(R.layout.activity_onboarding)
        }
    }

    @Test
    fun settings() {
        paparazzi.snapshot(name = "03_settings") {
            it.inflate(R.layout.activity_settings)
        }
    }

    @Test
    fun guard_intervention() {
        // This layout is in lock module package but same res namespace.
        paparazzi.snapshot(name = "04_guard_intervention") {
            it.inflate(app.secondway.lock.R.layout.activity_guard_intervention)
        }
    }

    /** Browser main is WebView-heavy; we still snapshot the chrome (toolbar + bottom nav) shell. */
    @Test
    fun browser_main_shell() {
        paparazzi.snapshot(name = "05_browser_main_shell") {
            it.inflate(R.layout.activity_main)
        }
    }
}
