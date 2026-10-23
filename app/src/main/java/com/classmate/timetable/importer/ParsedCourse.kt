package com.classmate.timetable.importer

import com.classmate.timetable.data.Course

/** 导入中间产物：一门课的一个连续周次片段。 */
data class ParsedCourse(
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val day: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: Int = 0,
    val note: String = ""
) {
    fun toCourse(colorIndex: Int): Course = Course(
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = day,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        colorIndex = colorIndex,
        note = note
    )

    fun signature(): String = "$name|$day|$startPeriod|$endPeriod|$startWeek|$endWeek|$weekType|$location"

    fun weekText(): String =
        if (startWeek == endWeek) "第${startWeek}周" else "$startWeek-$endWeek 周"
}
