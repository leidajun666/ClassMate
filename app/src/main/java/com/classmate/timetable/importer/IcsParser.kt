package com.classmate.timetable.importer

import com.classmate.timetable.data.Period
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** iCalendar（教务系统最常见的「导出到日历」）→ 课程。 */
object IcsParser {

    private data class Event(
        var summary: String = "",
        var location: String = "",
        var description: String = "",
        var start: LocalDateTime? = null,
        var end: LocalDateTime? = null,
        var rrule: String? = null
    )

    fun parse(
        text: String,
        periods: List<Period>,
        totalWeeks: Int,
        termStart: LocalDate?
    ): List<ParsedCourse> {
        val events = readEvents(text)
        val out = mutableListOf<ParsedCourse>()
        for (ev in events) {
            val start = ev.start ?: continue
            val end = ev.end ?: start

            val meta = GridParsing.parseCellText(ev.description)
            val name = ev.summary.trim().ifEmpty { meta.name }
            if (name.isEmpty()) continue

            val day = start.dayOfWeek.value
            val startTime = "%02d:%02d".format(start.hour, start.minute)
            val endTime = "%02d:%02d".format(end.hour, end.minute)
            var sp = GridParsing.sectionOfTime(startTime, periods)
            var ep = GridParsing.sectionOfTime(endTime, periods, isEnd = true)
            if (sp <= 0) {
                // 时间对不上节次表：按一天内的顺序粗排（8 点前算 1，之后每 2 小时一节）
                sp = ((start.hour - 8).coerceAtLeast(0) / 2) + 1
                ep = sp
            }
            if (ep < sp) ep = sp

            val weeks: List<Int>
            var weekText = meta.weekText
            if (weekText.isNotBlank()) {
                weeks = WeekRules.parse(weekText).weeks
            } else if (!ev.rrule.isNullOrBlank()) {
                weeks = weeksFromRRule(ev.rrule!!, start.toLocalDate(), termStart)
                weekText = WeekRules.compress(weeks)
            } else if (termStart != null) {
                val w = weekIndexOf(start.toLocalDate(), termStart)
                weeks = if (w > 0) listOf(w) else emptyList()
                weekText = if (w > 0) "${w}周" else ""
            } else {
                weeks = emptyList()
            }

            out.addAll(
                WeekRules.spread(
                    name = name,
                    teacher = meta.teacher,
                    location = ev.location.trim().ifEmpty { meta.location },
                    day = day,
                    startPeriod = sp,
                    endPeriod = ep,
                    weekText = weekText,
                    weeks = weeks,
                    totalWeeks = totalWeeks
                )
            )
        }
        return out
    }

    private fun readEvents(text: String): List<Event> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n")
            .replace(Regex("\\n[ \\t]"), "")   // 折行
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val events = mutableListOf<Event>()
        var cur: Event? = null
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", true) -> cur = Event()
                line.equals("END:VEVENT", true) -> {
                    cur?.let { events.add(it) }
                    cur = null
                }
                cur == null -> Unit
                else -> {
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val key = line.substring(0, idx).substringBefore(';').uppercase()
                    val value = line.substring(idx + 1)
                    when (key) {
                        "SUMMARY" -> cur.summary = unescape(value)
                        "LOCATION" -> cur.location = unescape(value)
                        "DESCRIPTION" -> cur.description = unescape(value)
                        "DTSTART" -> cur.start = parseDate(value)
                        "DTEND" -> cur.end = parseDate(value)
                        "RRULE" -> cur.rrule = value
                    }
                }
            }
        }
        return events
    }

    private fun unescape(v: String): String = v
        .replace("\\n", "\n", ignoreCase = true)
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    fun parseDate(value: String): LocalDateTime? {
        val v = value.trim()
        Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})?Z?$").find(v)?.let { m ->
            return LocalDateTime.of(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                m.groupValues[6].toIntOrNull() ?: 0
            )
        }
        Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(v)?.let { m ->
            return LocalDate.of(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()
            ).atStartOfDay()
        }
        Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})([ T](\\d{1,2}):(\\d{2}))?").find(v)?.let { m ->
            return LocalDate.of(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()
            ).atTime(
                m.groupValues[5].toIntOrNull() ?: 0,
                m.groupValues[6].toIntOrNull() ?: 0
            )
        }
        return null
    }

    private fun weeksFromRRule(rrule: String, startDate: LocalDate, termStart: LocalDate?): List<Int> {
        val map = HashMap<String, String>()
        rrule.split(';').forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) map[kv.substring(0, i).uppercase()] = kv.substring(i + 1)
        }
        val interval = (map["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var count = map["COUNT"]?.toIntOrNull() ?: 0
        val until = map["UNTIL"]?.let { parseDate(it)?.toLocalDate() }
        if (count == 0 && until == null) count = 20

        if (termStart != null) {
            val base = weekIndexOf(startDate, termStart).coerceAtLeast(1)
            if (until != null) {
                val out = mutableListOf<Int>()
                var w = base
                var guard = 0
                while (guard++ < 60) {
                    val d = termStart.plusDays(((w - 1) * 7 + (startDate.dayOfWeek.value - 1)).toLong())
                    if (d.isAfter(until)) break
                    out.add(w)
                    w += interval
                }
                return out
            }
            return (0 until count).map { base + it * interval }
        }
        return (0 until count).map { 1 + it * interval }
    }

    private fun weekIndexOf(date: LocalDate, termStart: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(termStart, date)
        if (days < 0) return 0
        return (days / 7).toInt() + 1
    }
}
