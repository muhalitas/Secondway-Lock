package app.secondway.lock

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class ReadmeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_readme)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.readme_title)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        val currentNavId = R.id.nav_info
        bottomNav.menu.findItem(currentNavId)?.isChecked = true
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentNavId) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_blocker -> {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    overridePendingTransition(0, 0)
                }
                R.id.nav_browser -> {
                    startActivity(
                        Intent(this, com.secondwaybrowser.app.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    overridePendingTransition(0, 0)
                }
                R.id.nav_profile -> {
                    startActivity(
                        Intent(this, com.secondwaybrowser.app.SettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    overridePendingTransition(0, 0)
                }
            }
            bottomNav.menu.findItem(currentNavId)?.isChecked = true
            false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
