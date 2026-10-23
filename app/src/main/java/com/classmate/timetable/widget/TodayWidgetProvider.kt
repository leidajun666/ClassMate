package com.classmate.timetable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.classmate.timetable.R
import com.classmate.timetable.data.Course
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.ui.MainActivity
import com.classmate.timetable.util.CourseColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 桌面小组件：今日课程一览。 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            runCatching { appWidgetManager.updateAppWidget(id, build(context)) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) updateAll(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.classmate.timetable.WIDGET_REFRESH"
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE")

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            if (ids == null || ids.isEmpty()) return
            ids.forEach { id ->
                runCatching { manager.updateAppWidget(id, build(context)) }
            }
        }

        fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            val prefs = Prefs(context)
            val db = Db.get(context)
            val today = LocalDate.now()
            val week = prefs.weekOf(today)
            val weekday = today.dayOfWeek.value
            val periods = db.periods().associateBy { it.index }

            val todayCourses = if (week in 1..prefs.totalWeeks) {
                db.courses()
                    .filter { it.dayOfWeek == weekday && it.activeInWeek(week) }
                    .sortedBy { it.startPeriod }
            } else {
                emptyList<Course>()
            }

            views.setTextViewText(R.id.wDate, today.format(DATE_FMT) + " · 第" + week + "周")
            views.removeAllViews(R.id.wList)

            todayCourses.take(6).forEach { course ->
                val item = RemoteViews(context.packageName, R.layout.widget_today_item)
                val period = periods[course.startPeriod]
                item.setTextViewText(R.id.iTime, period?.start ?: "")
                item.setTextViewText(R.id.iName, course.name)
                item.setTextViewText(
                    R.id.iPlace,
                    listOfNotNull(
                        course.location.takeIf { it.isNotBlank() },
                        course.teacher.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                )
                item.setInt(R.id.iBar, "setBackgroundColor", CourseColors.color(course.colorIndex))
                views.addView(R.id.wList, item)
            }

            views.setViewVisibility(R.id.wEmpty, if (todayCourses.isEmpty()) View.VISIBLE else View.GONE)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.wTitle, pi)
            views.setOnClickPendingIntent(R.id.wEmpty, pi)
            views.setOnClickPendingIntent(R.id.wList, pi)
            return views
        }
    }
}
