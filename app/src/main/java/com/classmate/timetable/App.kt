package com.classmate.timetable

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.notify.Notifications
import com.classmate.timetable.notify.ReminderScheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.themeMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        Notifications.ensureChannel(this)
        runCatching { ReminderScheduler.reschedule(this) }
    }
}
