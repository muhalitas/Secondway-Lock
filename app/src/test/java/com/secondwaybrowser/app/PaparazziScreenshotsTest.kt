package com.secondwaybrowser.app

import android.view.LayoutInflater
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
        theme = "@style/Theme.SafeBrowser"
    )

    @Test
    fun splash() {
        paparazzi.snapshot(name = "01_splash") {
            LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_splash, null, false)
        }
    }

    @Test
    fun onboarding_shell() {
        paparazzi.snapshot(name = "02_onboarding_shell") {
            LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_onboarding, null, false)
        }
    }

    @Test
    fun settings() {
        paparazzi.snapshot(name = "03_settings") {
            LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_settings, null, false)
        }
    }

    @Test
    fun guard_intervention() {
        paparazzi.snapshot(name = "04_guard_intervention") {
            LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_guard_intervention, null, false)
        }
    }

    /** Browser main is WebView-heavy; we still snapshot the chrome (toolbar + bottom nav) shell. */
    @Test
    fun browser_main_shell() {
        paparazzi.snapshot(name = "05_browser_main_shell") {
            LayoutInflater.from(paparazzi.context).inflate(R.layout.activity_main, null, false)
        }
    }
}
