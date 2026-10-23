package com.classmate.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.classmate.timetable.widget.TodayWidgetProvider

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                runCatching { ReminderScheduler.reschedule(context) }
                runCatching { TodayWidgetProvider.updateAll(context) }
            }
        }
    }
}
