package com.classmate.timetable.importer

import com.classmate.timetable.data.Period
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 教务导出文件导入总入口。
 *
 * 支持：`.ics`、`.csv/.tsv/.txt`、`.xls`（其实是 HTML 表格）、`.xlsx`、`.json`。
 * 全部纯 Kotlin 实现，不依赖任何第三方库，也不需要联网。
 */
object ScheduleFileImporter {

    data class Result(
        val courses: List<ParsedCourse> = emptyList(),
        val format: String = "",
        val label: String = "",
        val message: String = ""
    )

    fun import(
        fileName: String,
        bytes: ByteArray,
        periods: List<Period>,
        totalWeeks: Int,
        termStart: LocalDate? = null
    ): Result {
        if (bytes.isEmpty()) return Result(message = "文件是空的")

        val format = detectFormat(fileName, bytes)
        val courses = when (format) {
            "xlsx" -> {
                val grid = XlsxReader.readSheet(bytes)
                if (grid.isEmpty()) return Result(format = format, message = "这个 xlsx 里没读到表格内容")
                GridParsing.gridToCourses(grid, periods, totalWeeks)
            }
            "ole2" -> return Result(
                format = format,
                message = "这是老式二进制 .xls，App 读不了。请用 Excel/WPS 另存为 .xlsx 或 .csv 再导入。"
            )
            "ics" -> IcsParser.parse(TextCodec.decode(bytes), periods, totalWeeks, termStart)
            "html" -> GridParsing.gridToCourses(GridParsing.htmlToGrid(TextCodec.decode(bytes)), periods, totalWeeks)
            "json" -> jsonCourses(TextCodec.decode(bytes), periods, totalWeeks)
            else -> DelimitedParser.parse(TextCodec.decode(bytes), periods, totalWeeks)
        }

        val list = finalize(courses)
        val label = when (format) {
            "xlsx" -> "Excel"
            "ics" -> "日历 ICS"
            "html" -> "网页表格"
            "json" -> "JSON"
            else -> "CSV/文本"
        }
        return if (list.isEmpty()) {
            Result(
                format = format,
                label = label,
                message = "没从这份文件里解析出课程。若是课表截图，请用「AI 识图导入」；若是网页课表，可用「网页登录抓取」。"
            )
        } else {
            Result(courses = list, format = format, label = label)
        }
    }

    /** WebView 抓取到的页面表格 → 课程。 */
    fun fromCapture(payload: String, periods: List<Period>, totalWeeks: Int): Result {
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: return Result(message = "抓取内容不是有效数据")

        // 1) 优先解析页面表格
        root.optJSONArray("tables")?.let { tables ->
            for (t in 0 until tables.length()) {
                val rows = tables.optJSONArray(t) ?: continue
                val cells = mutableListOf<List<GridParsing.Cell>>()
                for (r in 0 until rows.length()) {
                    val row = rows.optJSONArray(r) ?: continue
                    val line = mutableListOf<GridParsing.Cell>()
                    for (c in 0 until row.length()) {
                        val cell = row.optJSONArray(c) ?: continue
                        line.add(
                            GridParsing.Cell(
                                text = cell.optString(0),
                                rowspan = cell.optInt(1, 1).coerceAtLeast(1),
                                colspan = cell.optInt(2, 1).coerceAtLeast(1)
                            )
                        )
                    }
                    if (line.isNotEmpty()) cells.add(line)
                }
                val grid = GridParsing.expandSpans(cells)
                val courses = finalize(GridParsing.gridToCourses(grid, periods, totalWeeks))
                if (courses.isNotEmpty()) return Result(courses, "web", "网页表格")
            }
        }

        // 2) 页面里带了结构化课程（有些系统直接是接口 JSON）
        root.optJSONArray("courses")?.let { arr ->
            val courses = finalize(parseJsonArray(arr, periods, totalWeeks))
            if (courses.isNotEmpty()) return Result(courses, "web", "网页数据")
            return Result(message = "抓到的课程列表是空的")
        }

        val textLen = root.optString("text").length
        return Result(
            message = if (textLen > 0) {
                "当前页面没找到课表表格。请先登录并进入「我的课表 / 课表查询」页面，再点抓取。"
            } else {
                "当前页面没抓到内容，等页面加载完再试。"
            }
        )
    }

    // ------------------------------------------------------------------ 格式探测

    fun detectFormat(fileName: String, bytes: ByteArray): String {
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return "xlsx"
        if (bytes.size >= 3 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte()) return "ole2"

        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1).uppercase()
        if (head.contains("BEGIN:VCALENDAR") || head.contains("BEGIN:VEVENT")) return "ics"
        if (head.contains("<TABLE") || head.contains("<HTML") || head.contains("<TR")) return "html"
        val trimmed = TextCodec.decode(bytes).trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "json"

        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "ics" || ext == "ical" || ext == "ifb") return "ics"
        return "csv"
    }

    // ------------------------------------------------------------------ JSON

    private fun jsonCourses(text: String, periods: List<Period>, totalWeeks: Int): List<ParsedCourse> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            val keys = listOf("courses", "data", "list", "result", "rows", "课表")
            keys.firstNotNullOfOrNull { k -> obj.optJSONArray(k) }
                ?: return emptyList()
        }
        return parseJsonArray(array, periods, totalWeeks)
    }

    private fun parseJsonArray(array: JSONArray, periods: List<Period>, totalWeeks: Int): List<ParsedCourse> {
        val out = mutableListOf<ParsedCourse>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = pick(obj, "name", "courseName", "course", "课程", "课程名", "课程名称", "title")
                ?: continue
            val teacher = pick(obj, "teacher", "teacherName", "教师", "任课教师").orEmpty()
            val location = pick(obj, "location", "classroom", "room", "地点", "教室", "上课地点").orEmpty()

            val dayRaw = pick(obj, "dayOfWeek", "day", "weekday", "星期", "周几")
            val day = when (val d = dayRaw) {
                null -> GridParsing.detectDay(pick(obj, "date", "日期"))
                else -> d.intOf() ?: GridParsing.detectDay(d)
            }

            val startRaw = pick(obj, "startSection", "startPeriod", "start", "section", "开始节次", "节次")
            val endRaw = pick(obj, "endSection", "endPeriod", "end", "结束节次")
            var sp = startRaw?.intOf() ?: 0
            var ep = endRaw?.intOf() ?: 0
            if (sp == 0) {
                GridParsing.parseSectionLabel(pick(obj, "period", "sections", "节次范围"))?.let {
                    sp = it.first
                    ep = it.second
                }
            }
            if (sp == 0) sp = GridParsing.sectionOfTime(pick(obj, "startTime", "开始时间"), periods)
            if (sp == 0) continue
            if (ep == 0) ep = sp

            val weekText = pick(obj, "weekText", "weeksText", "周次", "起止周")
                ?: obj.optJSONArray("weeks")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optInt(it).takeIf { w -> w > 0 } }
                        .let { if (it.isEmpty()) null else WeekRules.compress(it) }
                }

            out.addAll(
                WeekRules.spread(
                    name = name,
                    teacher = teacher,
                    location = location,
                    day = (day ?: 1).coerceIn(1, 7),
                    startPeriod = sp,
                    endPeriod = maxOf(sp, ep),
                    weekText = weekText,
                    weeks = emptyList(),
                    totalWeeks = totalWeeks
                )
            )
        }
        return out
    }

    private fun pick(obj: JSONObject, vararg keys: String): String? {
        keys.forEach { k ->
            val v = obj.opt(k)
            if (v != null && v != JSONObject.NULL) {
                val s = v.toString().trim()
                if (s.isNotEmpty() && s != "null") return s
            }
        }
        return null
    }

    private fun String.intOf(): Int? {
        val m = Regex("-?\\d+").find(trim()) ?: return null
        return runCatching { Integer.parseInt(m.value) }.getOrNull()
    }

    // ------------------------------------------------------------------ 收尾

    fun finalize(courses: List<ParsedCourse>): List<ParsedCourse> {
        val out = mutableListOf<ParsedCourse>()
        val seen = HashSet<String>()

        // 同一门课被 rowspan 拆成「第1节」「第2节」两格的情况，合并成「1-2节」
        courses.filter { it.name.isNotBlank() }
            .groupBy {
                listOf(it.name, it.day, it.startWeek, it.endWeek, it.weekType, it.location, it.teacher)
                    .joinToString("|")
            }
            .forEach { (_, group) ->
                val sorted = group.sortedBy { it.startPeriod }
                var current = sorted.first()
                for (i in 1 until sorted.size) {
                    val next = sorted[i]
                    current = if (next.startPeriod <= current.endPeriod + 1) {
                        current.copy(endPeriod = maxOf(current.endPeriod, next.endPeriod))
                    } else {
                        out.add(current)
                        next
                    }
                }
                out.add(current)
            }

        return out.filter { seen.add(it.signature()) }
            .sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name }))
    }
}
