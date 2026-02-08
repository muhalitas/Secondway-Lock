package app.secondway.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object NewAppUnlockScheduler {

    fun schedule(context: Context, packageName: String, endTimeMillis: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, packageName)
        val triggerAt = endTimeMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context, packageName: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context, packageName))
    }

    private fun pendingIntent(context: Context, packageName: String): PendingIntent {
        val intent = Intent(context, NewAppUnlockReceiver::class.java)
            .setAction(NewAppUnlockReceiver.ACTION_UNLOCK)
            .putExtra(NewAppUnlockReceiver.EXTRA_PACKAGE_NAME, packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, packageName.hashCode(), intent, flags)
    }
}

