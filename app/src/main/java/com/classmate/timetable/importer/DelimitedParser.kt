package com.classmate.timetable.importer

import com.classmate.timetable.data.Period

/** CSV / TSV / 分号分隔的课程表（教务「导出 Excel」有时其实是文本）。 */
object DelimitedParser {

    private val ALIAS: Map<String, List<String>> = mapOf(
        "name" to listOf("课程名称", "课程", "课程名", "教学班名称", "科目", "name", "course"),
        "teacher" to listOf("任课教师", "授课教师", "教师", "老师", "主讲教师", "teacher"),
        "location" to listOf("上课地点", "教室", "地点", "场地", "location", "room"),
        "day" to listOf("星期", "周几", "上课星期", "day", "weekday"),
        "secStart" to listOf("开始节次", "起始节", "开始节", "起始节次", "节次", "开始时间", "start"),
        "secEnd" to listOf("结束节次", "终止节", "结束节", "结束时间", "end"),
        "weeks" to listOf("周次", "周数", "上课周次", "起止周", "weeks"),
        "period" to listOf("节次范围", "上课节次", "period")
    )

    fun parse(text: String, periods: List<Period>, totalWeeks: Int): List<ParsedCourse> {
        val rows = smartSplit(text)
        if (rows.size < 2) return emptyList()

        val header = rows[0].map { it.trim() }
        val map = HashMap<String, Int>()
        ALIAS.forEach { (field, aliases) ->
            val idx = header.indexOfFirst { h ->
                val lower = h.lowercase()
                aliases.any { a -> lower == a.lowercase() || lower.contains(a.lowercase()) }
            }
            if (idx >= 0) map[field] = idx
        }
        if (!map.containsKey("name")) return emptyList()

        val out = mutableListOf<ParsedCourse>()
        for (r in 1 until rows.size) {
            val row = rows[r]
            fun cell(key: String): String = map[key]?.let { row.getOrNull(it)?.trim().orEmpty() } ?: ""

            val name = cell("name")
            if (name.isEmpty()) continue

            var day = GridParsing.detectDay(cell("day"))
            if (day == 0) day = 1

            var sp = cell("secStart").toIntOrNull() ?: 0
            var ep = cell("secEnd").toIntOrNull() ?: 0
            if (sp == 0) {
                GridParsing.parseSectionLabel(cell("period"))?.let { sp = it.first; ep = it.second }
            }
            if (sp == 0) sp = GridParsing.sectionOfTime(GridParsing.normalizeTime(cell("secStart")), periods)
            if (sp == 0) continue
            if (ep == 0) ep = sp

            out.addAll(
                WeekRules.spread(
                    name = name,
                    teacher = cell("teacher"),
                    location = cell("location"),
                    day = day,
                    startPeriod = sp,
                    endPeriod = maxOf(sp, ep),
                    weekText = cell("weeks"),
                    weeks = emptyList(),
                    totalWeeks = totalWeeks
                )
            )
        }
        return out
    }

    /** 自动嗅探分隔符 + 处理引号包裹 */
    fun smartSplit(text: String): List<List<String>> {
        val body = text.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
        val sample = body.split("\n").take(5).joinToString("\n")
        val sep = listOf(',', '\t', ';', '|').maxByOrNull { s -> sample.count { it == s } } ?: ','

        val out = mutableListOf<List<String>>()
        for (line in body.split("\n")) {
            if (line.isBlank()) continue
            val cells = mutableListOf<String>()
            val cur = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            cur.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    ch == sep && !inQuotes -> {
                        cells.add(cur.toString())
                        cur.setLength(0)
                    }
                    else -> cur.append(ch)
                }
                i++
            }
            cells.add(cur.toString())
            out.add(cells)
        }
        return out
    }
}
