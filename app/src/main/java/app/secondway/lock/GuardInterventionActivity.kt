package app.secondway.lock

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GuardInterventionActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guard_intervention)
        val message = intent.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.guard_intervention_default_message)
        findViewById<TextView>(R.id.guard_message).text = message
        handler.postDelayed(finishRunnable, DISPLAY_MILLIS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        private const val DISPLAY_MILLIS = 2300L
    }
}
