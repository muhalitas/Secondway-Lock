package com.secondwaybrowser.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.secondway.lock.R
import app.secondway.lock.NewAppRow
import app.secondway.lock.NewAppsAdapter
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Rule
import org.junit.Test

/**
 * Fast, emulator-free UI snapshots.
 * Goal: give us stable screenshots for polish/typography without waiting for an emulator.
 */
class PaparazziScreenshotsTest {

    private val device = DeviceConfig.PIXEL_6

    private class PlaceholderPagerAdapter(
        private val pages: List<String>
    ) : RecyclerView.Adapter<PlaceholderPagerAdapter.VH>() {
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val tv = TextView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.sw_text))
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.sw_surface))
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = pages[position]
        }

        override fun getItemCount(): Int = pages.size
    }

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
        val view: View = paparazzi.inflate<View>(R.layout.activity_splash)
        paparazzi.snapshot(view, "01_splash")
    }

    @Test
    fun onboarding_shell() {
        useTheme("Theme.SafeBrowser")
        val view: View = paparazzi.inflate<View>(R.layout.activity_onboarding).apply {
            // The onboarding activity populates content at runtime. Add a representative step so
            // snapshots are not misleading/blank.
            val content = findViewById<ViewGroup>(R.id.onboarding_content)
            if (content.childCount == 0) {
                val step = View.inflate(context, R.layout.onboarding_step_welcome, null)
                content.addView(
                    step,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
        paparazzi.snapshot(view, "02_onboarding_shell")
    }

    @Test
    fun settings() {
        useTheme("Theme.SafeBrowser")
        val view: View = paparazzi.inflate<View>(R.layout.activity_settings).apply {
            findViewById<TextView>(R.id.account_status)?.text = "Signed out"
            findViewById<TextView>(R.id.language_value)?.text = "English"
            findViewById<TextView>(R.id.lock_duration_value)?.text = "15 minutes"
            // Match runtime: settings lives under Profile tab.
            findViewById<BottomNavigationView>(R.id.bottom_nav)?.let { nav ->
                nav.menu.findItem(R.id.nav_profile)?.isChecked = true
                nav.selectedItemId = R.id.nav_profile
            }
        }
        paparazzi.snapshot(view, "03_settings")
    }

    @Test
    fun guard_intervention() {
        useTheme("Theme.SecondwayLock")
        val view: View = paparazzi.inflate<View>(R.layout.activity_guard_intervention)
        paparazzi.snapshot(view, "04_guard_intervention")
    }

    /** Browser main is WebView-heavy; we still snapshot the chrome (toolbar + bottom nav) shell. */
    @Test
    fun browser_main_shell() {
        useTheme("Theme.SafeBrowser")
        val view: View = paparazzi.inflate<View>(R.layout.activity_main).apply {
            // ViewPager2 would otherwise render blank because its adapter is set in the Activity.
            findViewById<ViewPager2>(R.id.view_pager)?.let { pager ->
                if (pager.adapter == null) {
                    pager.adapter = PlaceholderPagerAdapter(
                        listOf(
                            "Secondway Browser\n\nSearch or type a URL",
                            "New tab\n\nPinned shortcuts will appear here",
                            "History\n\nYour browsing history appears here"
                        )
                    )
                    pager.setCurrentItem(0, false)
                }
            }

            findViewById<EditText>(R.id.url_bar)?.setText("https://secondway.app")
            findViewById<TextView>(R.id.tab_count_badge)?.text = "3"

            findViewById<ProgressBar>(R.id.load_progress)?.let { p ->
                p.visibility = View.VISIBLE
                p.progress = 62
            }

            findViewById<BottomNavigationView>(R.id.bottom_nav)?.let { nav ->
                nav.menu.findItem(R.id.nav_browser)?.isChecked = true
                nav.selectedItemId = R.id.nav_browser
            }
        }
        paparazzi.snapshot(view, "05_browser_main_shell")
    }

    @Test
    fun lock_main() {
        useTheme("Theme.SecondwayLock")
        val view: View = paparazzi.inflate<View>(R.layout.lock_activity_main).apply {
            findViewById<Switch>(R.id.master_switch)?.isChecked = true

            findViewById<TextView>(R.id.protection_countdown)?.let { tv ->
                tv.visibility = View.VISIBLE
                tv.text = "Protection countdown: 00:12:35"
            }

            findViewById<TextView>(R.id.lock_duration_display)?.text = "Lock duration: 60 minutes"
            findViewById<View>(R.id.lock_duration_defer_row)?.let { row ->
                row.visibility = View.VISIBLE
                findViewById<TextView>(R.id.lock_duration_defer_text)?.text =
                    "Change scheduled: 15 minutes (in 00:04:10)"
            }

            // RecyclerView is populated at runtime; create a realistic list so the snapshot is useful.
            findViewById<RecyclerView>(R.id.all_apps_list)?.let { rv ->
                if (rv.layoutManager == null) {
                    rv.layoutManager = LinearLayoutManager(context)
                }
                if (rv.adapter == null) {
                    val now = 1_700_000_000_000L
                    val rows = mutableListOf(
                        NewAppRow(
                            packageName = "com.example.chat",
                            label = "Chat",
                            icon = ColorDrawable(Color.parseColor("#22C55E")),
                            desiredAllowed = true,
                            isActuallyBlocked = false,
                            pendingUnlockEndMillis = 0L
                        ),
                        NewAppRow(
                            packageName = "com.example.social",
                            label = "Social",
                            icon = ColorDrawable(Color.parseColor("#60A5FA")),
                            desiredAllowed = false,
                            isActuallyBlocked = true,
                            pendingUnlockEndMillis = 0L
                        ),
                        NewAppRow(
                            packageName = "com.example.video",
                            label = "Video",
                            icon = ColorDrawable(Color.parseColor("#F97316")),
                            desiredAllowed = true,
                            isActuallyBlocked = false,
                            pendingUnlockEndMillis = now + 5 * 60 * 1000L
                        )
                    )
                    val adapter = NewAppsAdapter(rows) { _, _ -> }
                    adapter.nowMillis = now
                    rv.adapter = adapter
                }
            }

            findViewById<BottomNavigationView>(R.id.bottom_nav)?.let { nav ->
                nav.menu.findItem(R.id.nav_blocker)?.isChecked = true
                nav.selectedItemId = R.id.nav_blocker
            }
        }
        paparazzi.snapshot(view, "06_lock_main")
    }

    @Test
    fun lock_settings() {
        useTheme("Theme.SecondwayLock")
        val view: View = paparazzi.inflate<View>(R.layout.lock_activity_settings).apply {
            // Make status cards visible so the screenshot matches common real-world state.
            findViewById<View>(R.id.full_protection_card)?.visibility = View.VISIBLE
            findViewById<View>(R.id.battery_opt_card)?.visibility = View.VISIBLE

            findViewById<TextView>(R.id.default_browser_desc)?.text = "Secondway Browser is set as default"
            findViewById<TextView>(R.id.language_value)?.text = "English"

            findViewById<BottomNavigationView>(R.id.bottom_nav)?.let { nav ->
                nav.menu.findItem(R.id.nav_info)?.isChecked = true
                nav.selectedItemId = R.id.nav_info
            }
        }
        paparazzi.snapshot(view, "07_lock_settings")
    }
}
