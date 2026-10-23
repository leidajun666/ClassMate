package com.classmate.timetable.importer

import com.classmate.timetable.data.Period

/**
 * 表格（HTML / XLSX / WebView 抓取）→ 课程。
 *
 * 教务课表本质是一张二维表：表头是星期，最左列是节次，格子里塞「课程名 / 教师 / 地点 / 周次」。
 * 这里按这个结构反推：先找表头行确定每列对应星期，再按节次标签确定行，最后解析格子文本。
 */
object GridParsing {

    // ---------------------------------------------------------------- 时间 ↔ 节次

    fun sectionOfTime(time: String?, periods: List<Period>, isEnd: Boolean = false): Int {
        if (time.isNullOrBlank() || periods.isEmpty()) return 0
        val t = minutes(time) ?: return 0
        periods.forEachIndexed { index, p ->
            val start = minutes(p.start) ?: return@forEachIndexed
            val end = minutes(p.end) ?: return@forEachIndexed
            if (if (isEnd) t <= end else (t >= start && t < end)) return index + 1
        }
        return 0
    }

    fun minutes(hhmm: String): Int? {
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(hhmm) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    fun normalizeTime(s: String?): String {
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(s ?: "") ?: return ""
        return m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
    }

    // ---------------------------------------------------------------- 文本 → 字段

    data class CellMeta(
        val name: String = "",
        val teacher: String = "",
        val location: String = "",
        val weekText: String = ""
    )

    private val teacherKey = Regex("^(教师|老师|任课教师|授课教师|主讲)\\s*[:：]\\s*")
    private val locationKey = Regex("^(地点|教室|上课地点|上课教室|场地)\\s*[:：]\\s*")
    private val weekKey = Regex("^(周次|周数|起止周|上课周次)\\s*[:：]\\s*")
    private val nameKey = Regex("^(课程|课程名称|教学班|科目)\\s*[:：]\\s*")

    /** 解析一个格子里的课程文本：课程名 / 教师 / 地点 / 周次。 */
    fun parseCellText(text: String): CellMeta {
        var name = ""
        var teacher = ""
        var location = ""
        var weekText = ""

        val lines = text.split(Regex("[\\n\\r]+|<br\\s*/?>", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (raw in lines) {
            when {
                teacherKey.containsMatchIn(raw) -> {
                    teacher = raw.replace(teacherKey, "").trim()
                    continue
                }
                locationKey.containsMatchIn(raw) -> {
                    location = raw.replace(locationKey, "").trim()
                    continue
                }
                weekKey.containsMatchIn(raw) -> {
                    weekText = raw.replace(weekKey, "").trim()
                    continue
                }
                nameKey.containsMatchIn(raw) -> {
                    name = raw.replace(nameKey, "").trim()
                    continue
                }
            }

            val isWeekLine = Regex("(\\d+\\s*[-~至]\\s*\\d+\\s*周|\\d+\\s*周)").containsMatchIn(raw) && raw.length < 30
            if (weekText.isEmpty() && isWeekLine) {
                var w = raw
                while (Regex("^[（(][\\s\\S]*[）)]$").matches(w)) w = w.substring(1, w.length - 1).trim()
                weekText = w
                continue
            }
            if (name.isEmpty()) {
                name = raw
                continue
            }
            if (location.isEmpty() && isLocationLike(raw)) {
                location = raw
                continue
            }
            if (teacher.isEmpty() && isTeacherLike(raw)) {
                teacher = raw.replace(Regex("[（(].*?[）)]"), "").trim()
                continue
            }
            if (location.isEmpty()) location = raw
        }
        return CellMeta(name, teacher, location, weekText)
    }

    private fun isTeacherLike(s: String): Boolean =
        Regex("老师|教授|讲师|助教").containsMatchIn(s) ||
            Regex("^[\\u4e00-\\u9fa5]{2,4}([,，、][\\u4e00-\\u9fa5]{2,4})*$").matches(s)

    private fun isLocationLike(s: String): Boolean =
        s.length <= 30 && Regex("(楼|教|馆|场|室|机房|实验|中心|校区|A\\d|B\\d|[A-Za-z]-\\d|\\d{3,})").containsMatchIn(s)

    // ---------------------------------------------------------------- 星期 / 节次

    private val DAY_CN = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)

    fun detectDay(cell: String?): Int {
        if (cell.isNullOrBlank()) return 0
        val t = cell.trim()
        Regex("(星期|周)\\s*([一二三四五六日天])").find(t)?.let {
            return DAY_CN[it.groupValues[2][0]] ?: 0
        }
        Regex("^(mon|tue|wed|thu|fri|sat|sun)", RegexOption.IGNORE_CASE).find(t)?.let {
            return when (it.groupValues[1].lowercase().take(3)) {
                "mon" -> 1; "tue" -> 2; "wed" -> 3; "thu" -> 4
                "fri" -> 5; "sat" -> 6; else -> 7
            }
        }
        if (t.length == 1) DAY_CN[t[0]]?.let { return it }
        // 2026-09-07 这种日期
        Regex("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}").find(t)?.let {
            runCatching {
                val parts = t.take(10).split('-', '/', '.')
                val d = java.time.LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                return d.dayOfWeek.value
            }
        }
        return 0
    }

    /** "1-2" / "第1-2节" / "3" → (1,2) / (3,3) */
    fun parseSectionLabel(label: String?): Pair<Int, Int>? {
        if (label.isNullOrBlank()) return null
        val s = label.trim()
        Regex("(\\d+)\\s*[-~至]\\s*(\\d+)").find(s)?.let { m ->
            val hasHint = s.contains("节") || s.contains("第") ||
                Regex("^\\d+\\s*[-~]\\s*\\d+$").matches(s)
            if (hasHint) return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        Regex("(\\d+)").find(s)?.let { return it.value.toInt().let { n -> n to n } }
        return null
    }

    private val HEADERISH = Regex("^(课程|节次|时间|星期|星期一|备注|上午|下午|晚上|午休|课间)$")

    fun isHeaderish(s: String?): Boolean = s != null && HEADERISH.matches(s.trim())

    /** 一个格子塞多门课（常见用 --- 或空行分隔） */
    private fun splitCourses(text: String): List<String> {
        val parts = text.split(Regex("\\n\\s*(?:-{3,}|={3,}|\\|)\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.size > 1) parts else listOf(text)
    }

    // ---------------------------------------------------------------- 网格处理

    /**
     * 把二维网格转成课程。
     * @param grid 每行每列一个字符串（rowspan/colspan 已展开）
     */
    fun gridToCourses(
        grid: List<List<String>>,
        periods: List<Period>,
        totalWeeks: Int
    ): List<ParsedCourse> {
        if (grid.isEmpty()) return emptyList()
        val headerRow = grid.take(8).indexOfFirst { row -> row.count { detectDay(it) > 0 } >= 3 }
        if (headerRow < 0) return emptyList()

        val header = grid[headerRow]
        val colDay = HashMap<Int, Int>()
        header.forEachIndexed { c, cell ->
            val d = detectDay(cell)
            if (d > 0) colDay[c] = d
        }
        if (colDay.isEmpty()) return emptyList()

        var sectionCol = header.indexOfFirst { Regex("节|时间|时段").containsMatchIn(it) }
        if (sectionCol < 0) sectionCol = 0

        val courses = mutableListOf<ParsedCourse>()
        var autoSection = 0

        for (r in headerRow + 1 until grid.size) {
            val row = grid[r]
            val label = row.getOrNull(sectionCol)?.trim().orEmpty()
            val parsed = parseSectionLabel(label)
            var section: Int
            var sectionEnd: Int
            if (parsed != null && (label.contains("节") || label.contains("第") || Regex("^\\d+\\s*[-~]\\s*\\d+$").matches(label))) {
                section = parsed.first
                sectionEnd = parsed.second
                autoSection = sectionEnd
            } else if (label.isEmpty()) {
                autoSection += 1
                section = autoSection
                sectionEnd = autoSection
            } else {
                autoSection += 1
                section = autoSection
                sectionEnd = autoSection
            }

            for ((c, day) in colDay) {
                val text = row.getOrNull(c)?.trim().orEmpty()
                if (text.isEmpty() || isHeaderish(text)) continue
                for (block in splitCourses(text)) {
                    val meta = parseCellText(block)
                    if (meta.name.isEmpty() || isHeaderish(meta.name)) continue
                    // 格子里写了「第3-4节」时以格子为准
                    var sp = section
                    var ep = sectionEnd
                    Regex("第?\\s*(\\d{1,2})\\s*[-~至]\\s*(\\d{1,2})\\s*节").find(block)?.let {
                        sp = it.groupValues[1].toInt()
                        ep = it.groupValues[2].toInt()
                    }
                    courses.addAll(
                        WeekRules.spread(
                            name = meta.name,
                            teacher = meta.teacher,
                            location = meta.location,
                            day = day,
                            startPeriod = sp,
                            endPeriod = maxOf(sp, ep),
                            weekText = meta.weekText,
                            weeks = emptyList(),
                            totalWeeks = totalWeeks
                        )
                    )
                }
            }
        }
        return courses
    }

    // ---------------------------------------------------------------- HTML 表格

    fun htmlToGrid(html: String): List<List<String>> {
        val rows = mutableListOf<List<Cell>>()
        val trRe = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
        val cellRe = Regex("<(td|th)\\b([^>]*)>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)

        for (tr in trRe.findAll(html)) {
            val cells = mutableListOf<Cell>()
            for (m in cellRe.findAll(tr.groupValues[1])) {
                val attrs = m.groupValues[2]
                val style = Regex("style\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1) ?: ""
                if (Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE).containsMatchIn(style)) continue
                val rowspan = Regex("rowspan\\s*=\\s*\"?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val colspan = Regex("colspan\\s*=\\s*\"?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                cells.add(Cell(cellText(m.groupValues[3]), rowspan, colspan))
            }
            if (cells.isNotEmpty()) rows.add(cells)
        }
        return expandSpans(rows)
    }

    data class Cell(val text: String, val rowspan: Int = 1, val colspan: Int = 1)

    fun expandSpans(rows: List<List<Cell>>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        val pending = HashMap<Int, Pair<String, Int>>()

        for (row in rows) {
            val line = ArrayList<String>()
            var col = 0
            fun fillPending() {
                while (true) {
                    val p = pending[col] ?: break
                    if (p.second <= 0) {
                        pending.remove(col)
                        break
                    }
                    while (line.size <= col) line.add("")
                    line[col] = p.first
                    pending[col] = p.first to (p.second - 1)
                    col++
                }
            }

            for (cell in row) {
                fillPending()
                for (k in 0 until cell.colspan) {
                    while (line.size <= col) line.add("")
                    line[col] = cell.text
                    if (cell.rowspan > 1) pending[col] = cell.text to (cell.rowspan - 1)
                    col++
                }
            }
            fillPending()
            out.add(line)
        }
        return out
    }

    fun cellText(inner: String): String = inner
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|li)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace(Regex("[ \\t\\u00a0]+"), " ")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
}
