package app.secondway.lock

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ReadmeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_readme)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.readme_title)
        findViewById<Button>(R.id.button_remove_device_owner).setOnClickListener { removeDeviceOwner() }
    }

    override fun onResume() {
        super.onResume()
        val isOwner = isDeviceOwner()
        findViewById<TextView>(R.id.device_owner_status).text =
            if (isOwner) getString(R.string.status_device_owner) else getString(R.string.status_not_device_owner)
        findViewById<Button>(R.id.button_remove_device_owner).visibility =
            if (isOwner) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }

    private fun removeDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, R.string.toast_not_device_owner, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            dpm.clearDeviceOwnerApp(packageName)
            Toast.makeText(this, R.string.toast_device_owner_removed, Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_remove_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
