package com.classmate.timetable.data

/** 一门课程（可跨多节、多周）。星期 1=周一 … 7=周日，节次与周次均从 1 开始。 */
data class Course(
    val id: Long = 0L,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val dayOfWeek: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: Int = 0, // 0 每周 / 1 单周 / 2 双周
    val colorIndex: Int = 0,
    val note: String = ""
) {
    fun activeInWeek(week: Int): Boolean {
        if (week < startWeek || week > endWeek) return false
        return when (weekType) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    fun overlapsPeriod(other: Course): Boolean =
        dayOfWeek == other.dayOfWeek && startPeriod <= other.endPeriod && other.startPeriod <= endPeriod

    fun weeksText(): String {
        val base = if (startWeek == endWeek) "第${startWeek}周" else "$startWeek-$endWeek 周"
        return when (weekType) {
            1 -> "$base（单）"
            2 -> "$base（双）"
            else -> base
        }
    }

    fun periodText(): String =
        if (startPeriod == endPeriod) "第${startPeriod}节" else "第$startPeriod-${endPeriod}节"

    companion object {
        val DAY_NAMES = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val DAY_SHORT = arrayOf("", "一", "二", "三", "四", "五", "六", "日")
        val WEEK_TYPE_NAMES = arrayOf("每周", "单周", "双周")
    }
}

/** 一节课的时间（节次序号从 1 开始）。 */
data class Period(
    val index: Int,
    val start: String, // "08:00"
    val end: String    // "08:45"
) {
    fun startMinutes(): Int = parseMinutes(start)
    fun endMinutes(): Int = parseMinutes(end)

    companion object {
        fun parseMinutes(t: String): Int {
            val parts = t.split(":")
            if (parts.size < 2) return 0
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            return h * 60 + m
        }

        fun formatMinutes(minutes: Int): String {
            val m = ((minutes % 1440) + 1440) % 1440
            return String.format("%02d:%02d", m / 60, m % 60)
        }

        fun defaults(): List<Period> = listOf(
            Period(1, "08:00", "08:45"),
            Period(2, "08:55", "09:40"),
            Period(3, "10:00", "10:45"),
            Period(4, "10:55", "11:40"),
            Period(5, "14:00", "14:45"),
            Period(6, "14:55", "15:40"),
            Period(7, "16:00", "16:45"),
            Period(8, "16:55", "17:40"),
            Period(9, "19:00", "19:45"),
            Period(10, "19:55", "20:40"),
            Period(11, "20:50", "21:35"),
            Period(12, "21:45", "22:30")
        )
    }
}
