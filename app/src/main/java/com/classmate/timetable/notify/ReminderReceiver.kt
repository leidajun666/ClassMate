package com.classmate.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getLongExtra("course_id", -1L)
        val db = Db.get(context)
        val prefs = Prefs(context)
        val course = if (courseId > 0) db.courseById(courseId) else null
        val period = course?.let { db.periods().firstOrNull { p -> p.index == it.startPeriod } }

        val title = if (course != null) {
            "${prefs.reminderMinutes} 分钟后上课 · ${period?.start ?: ""}"
        } else {
            "上课提醒"
        }
        val content = if (course != null) {
            buildString {
                append(course.name)
                if (course.location.isNotBlank()) append(" · ").append(course.location)
                if (course.teacher.isNotBlank()) append(" · ").append(course.teacher)
                if (period != null) append("  ").append(period.start).append("-").append(period.end)
            }
        } else {
            "你有一节课即将开始"
        }
        Notifications.showReminder(context, courseId.toInt().let { if (it <= 0) 1001 else it }, title, content)
    }
}
