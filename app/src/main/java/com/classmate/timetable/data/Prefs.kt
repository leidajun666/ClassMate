package com.classmate.timetable.data

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 全局设置：学期起点、周数、显示与提醒偏好。 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("classmate_settings", Context.MODE_PRIVATE)

    var semesterStart: LocalDate
        get() {
            val raw = sp.getString(KEY_START, null)
            if (raw != null) {
                runCatching { return LocalDate.parse(raw) }
            }
            val fallback = LocalDate.now().with(DayOfWeek.MONDAY)
            sp.edit().putString(KEY_START, fallback.toString()).apply()
            return fallback
        }
        set(value) {
            sp.edit().putString(KEY_START, value.toString()).apply()
        }

    var totalWeeks: Int
        get() = sp.getInt(KEY_TOTAL_WEEKS, 20).coerceIn(1, 40)
        set(value) {
            sp.edit().putInt(KEY_TOTAL_WEEKS, value.coerceIn(1, 40)).apply()
        }

    /** 0 表示按开学日期自动计算 */
    var manualWeek: Int
        get() = sp.getInt(KEY_MANUAL_WEEK, 0)
        set(value) {
            sp.edit().putInt(KEY_MANUAL_WEEK, value).apply()
        }

    var showWeekend: Boolean
        get() = sp.getBoolean(KEY_SHOW_WEEKEND, true)
        set(value) {
            sp.edit().putBoolean(KEY_SHOW_WEEKEND, value).apply()
        }

    var dimOtherWeeks: Boolean
        get() = sp.getBoolean(KEY_DIM_OTHER, true)
        set(value) {
            sp.edit().putBoolean(KEY_DIM_OTHER, value).apply()
        }

    var dynamicColor: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) {
            sp.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        }

    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME, 0)
        set(value) {
            sp.edit().putInt(KEY_THEME, value).apply()
        }

    var reminderEnabled: Boolean
        get() = sp.getBoolean(KEY_REMINDER, true)
        set(value) {
            sp.edit().putBoolean(KEY_REMINDER, value).apply()
        }

    var reminderMinutes: Int
        get() = sp.getInt(KEY_REMINDER_MIN, 15)
        set(value) {
            sp.edit().putInt(KEY_REMINDER_MIN, value).apply()
        }

    var askedNotification: Boolean
        get() = sp.getBoolean(KEY_ASKED_NOTIF, false)
        set(value) {
            sp.edit().putBoolean(KEY_ASKED_NOTIF, value).apply()
        }

    /** 记住上次浏览的周次（0 表示未设置） */
    var lastViewWeek: Int
        get() = sp.getInt(KEY_VIEW_WEEK, 0)
        set(value) {
            sp.edit().putInt(KEY_VIEW_WEEK, value).apply()
        }

    /** 某一日期属于第几周（可能越界，调用方自行 clamp） */
    fun weekOf(date: LocalDate): Int =
        (ChronoUnit.DAYS.between(semesterStart, date) / 7).toInt() + 1

    fun dateOf(week: Int, dayOfWeek: Int): LocalDate =
        semesterStart.plusDays((week - 1) * 7L + (dayOfWeek - 1))

    fun currentWeek(): Int {
        if (manualWeek > 0) return manualWeek.coerceIn(1, totalWeeks)
        return weekOf(LocalDate.now()).coerceIn(1, totalWeeks)
    }

    fun isCurrentWeekReal(): Boolean = manualWeek == 0

    companion object {
        private const val KEY_START = "semester_start"
        private const val KEY_TOTAL_WEEKS = "total_weeks"
        private const val KEY_MANUAL_WEEK = "manual_week"
        private const val KEY_SHOW_WEEKEND = "show_weekend"
        private const val KEY_DIM_OTHER = "dim_other_weeks"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_REMINDER = "reminder_enabled"
        private const val KEY_REMINDER_MIN = "reminder_minutes"
        private const val KEY_VIEW_WEEK = "view_week"
        private const val KEY_ASKED_NOTIF = "asked_notif"
    }
}
