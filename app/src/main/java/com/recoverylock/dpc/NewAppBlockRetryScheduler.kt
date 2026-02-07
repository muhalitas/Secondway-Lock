package com.recoverylock.dpc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object NewAppBlockRetryScheduler {

    fun schedule(context: Context, packageName: String, attempt: Int) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, packageName, attempt)
        val delayMillis = when (attempt.coerceIn(1, 8)) {
            1 -> 2_000L
            2 -> 5_000L
            3 -> 15_000L
            4 -> 60_000L
            5 -> 2 * 60_000L
            6 -> 5 * 60_000L
            7 -> 10 * 60_000L
            else -> 30 * 60_000L
        }
        val triggerAt = System.currentTimeMillis() + delayMillis
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun pendingIntent(context: Context, packageName: String, attempt: Int): PendingIntent {
        val intent = Intent(context, NewAppBlockRetryReceiver::class.java)
            .setAction(NewAppBlockRetryReceiver.ACTION_RETRY_BLOCK)
            .putExtra(NewAppBlockRetryReceiver.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(NewAppBlockRetryReceiver.EXTRA_ATTEMPT, attempt)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, ("retry:" + packageName).hashCode(), intent, flags)
    }
}

