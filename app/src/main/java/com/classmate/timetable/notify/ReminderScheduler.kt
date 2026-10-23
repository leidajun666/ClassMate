package com.classmate.timetable.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs
import java.time.LocalDate
import java.time.ZoneId

/** 为未来一周的课程安排精确闹钟；每次重排前先清空已排的闹钟。 */
object ReminderScheduler {

    const val ACTION_REMIND = "com.classmate.timetable.action.REMIND"
    private const val SP = "reminder_codes"
    private const val KEY_CODES = "codes"

    fun reschedule(context: Context) {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        val db = Db.get(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return

        cancelAll(ctx)

        if (!prefs.reminderEnabled) return
        val courses = db.courses()
        if (courses.isEmpty()) return

        val periods = db.periods().associateBy { it.index }
        val totalWeeks = prefs.totalWeeks
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val codes = mutableSetOf<String>()

        for (offset in 0..7L) {
            val date = today.plusDays(offset)
            val week = prefs.weekOf(date)
            if (week < 1 || week > totalWeeks) continue
            val day = date.dayOfWeek.value
            courses.filter { it.dayOfWeek == day && it.activeInWeek(week) }.forEach { course ->
                val period = periods[course.startPeriod] ?: return@forEach
                val start = date.atStartOfDay(zone).plusMinutes(period.startMinutes().toLong())
                val triggerAt = start.minusMinutes(prefs.reminderMinutes.toLong()).toInstant().toEpochMilli()
                if (triggerAt <= now + 5_000) return@forEach
                val code = codeFor(course.id, day)
                val pi = buildIntent(ctx, code, course.id)
                schedule(am, triggerAt, pi)
                codes.add(code.toString())
            }
        }

        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_CODES, codes).apply()
    }

    private fun schedule(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    private fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val stored = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getStringSet(KEY_CODES, emptySet()) ?: emptySet()
        stored.forEach { raw ->
            val code = raw.toIntOrNull() ?: return@forEach
            val pi = buildIntent(context, code, -1L)
            am.cancel(pi)
            pi.cancel()
        }
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().remove(KEY_CODES).apply()
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun codeFor(courseId: Long, day: Int): Int =
        ((courseId % 100_000L).toInt() * 10 + day).let { if (it < 0) -it else it }

    private fun buildIntent(context: Context, code: Int, courseId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            data = android.net.Uri.parse("classmate://remind/$code")
            putExtra("course_id", courseId)
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
