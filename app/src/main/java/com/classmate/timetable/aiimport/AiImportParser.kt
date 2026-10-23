package com.classmate.timetable.aiimport

import com.classmate.timetable.importer.ParsedCourse
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析 AI 返回的课程 JSON。
 *
 * 不同 AI 的输出格式五花八门：可能包在 ```json 代码块里，可能多一句“好的，以下是识别结果”，
 * 字段名可能是 name / 课程名 / courseName，星期可能是 3 / "周三" / "Wednesday"，
 * 节次可能是 3 和 4 两个字段，也可能是一句 "3-4节"。这里全部做兼容，
 * 最后统一裁剪到当前设置的节次数量与总周数内。
 */
object AiImportParser {

    data class Result(
        val courses: List<ParsedCourse> = emptyList(),
        val message: String = "",
        val fromJson: Boolean = false
    )

    /** 交给 AI 的提示词：复制给任意能看图的 AI，让它按固定结构吐 JSON。 */
    val PROMPT: String = """
你是课程表识别助手。下面这张图片是一张学校课程表（可能是教务系统截图、手机截图或拍的纸质课表）。

请识别出其中所有课程，并且**只输出一个 JSON 数组**，不要写任何解释文字，不要输出 JSON 以外的内容。

每门课一个对象，字段如下：
- "name"：字符串，课程名称，如 "高等数学（上）"
- "teacher"：字符串，任课教师，看不出来填空字符串 ""
- "location"：字符串，上课地点或教室，如 "教一101"，看不出来填空字符串 ""
- "day"：整数 1-7，星期几，1=周一，7=周日
- "startPeriod"：整数，开始节次，从 1 开始
- "endPeriod"：整数，结束节次
- "startWeek"：整数，起始周
- "endWeek"：整数，结束周
- "weekType"：整数，0=每周，1=单周，2=双周

规则：
1. 同一门课一周上多次，请拆成多条。
2. 图中写 "1-16周"，则 startWeek=1、endWeek=16；写 "1-16周(单)" 则 weekType=1；"(双)" 则 weekType=2。
3. 图中写 "3-4节"，则 startPeriod=3、endPeriod=4；只写 "第5节" 则 startPeriod=endPeriod=5。
4. 周次完全看不出时，startWeek=1、endWeek=20。
5. 节次必须尽量判断；如果只标了上课时间（如 8:00-9:40），按顺序推测：上午第1-2、3-4节，下午第5-6、7-8节，晚上第9-10节。
6. 如果图片里没有课程表（不是课表图），输出 []。

输出示例（严格照此格式，不要加注释）：
[{"name":"高等数学（上）","teacher":"张三","location":"教一101","day":1,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16,"weekType":0},
{"name":"大学英语","teacher":"李四","location":"外语楼203","day":3,"startPeriod":3,"endPeriod":4,"startWeek":1,"endWeek":16,"weekType":0}]

只输出 JSON。
""".trimIndent()

    // ------------------------------------------------------------------ 对外入口

    fun parse(raw: String, periodCount: Int, totalWeeks: Int): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result(message = "还没有粘贴 AI 返回的内容")

        val json = extractJson(text)
        if (json != null) {
            val array = runCatching { toArray(json) }.getOrNull()
            if (array != null) {
                val list = parseArray(array, periodCount, totalWeeks)
                if (list.isNotEmpty()) return Result(list, fromJson = true)
                return Result(message = "JSON 读到了，但里面没有有效课程（每门课至少要有课程名 name）")
            }
            return Result(message = "这段内容像是 JSON，但格式有误，可能复制时少了括号。请重新完整复制一次。")
        }

        val loose = parseLines(text, periodCount, totalWeeks)
        if (loose.isNotEmpty()) {
            return Result(loose, message = "没找到 JSON，已按文本行尽力解析，请逐条核对", fromJson = false)
        }
        return Result(message = "没能解析出课程。请确认粘贴的是 AI 返回的完整 JSON 数组。")
    }

    // ------------------------------------------------------------------ JSON 定位

    /** 从一大段文字里抠出第一段完整的 JSON（优先用 ```json 代码块）。 */
    private fun extractJson(text: String): String? {
        Regex("```(?:json|JSON|Json)?\\s*([\\s\\S]*?)```").find(text)?.let { m ->
            val body = m.groupValues[1].trim()
            if (body.startsWith("[") || body.startsWith("{")) return body
        }
        val startArr = text.indexOf('[')
        val startObj = text.indexOf('{')
        val start = listOf(startArr, startObj).filter { it >= 0 }.minOrNull() ?: return null
        val open = text[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun toArray(json: String): JSONArray? {
        val t = json.trim()
        if (t.startsWith("[")) return JSONArray(t)
        val obj = JSONObject(t)
        val keys = obj.keys().asSequence().toList()
        keys.forEach { k ->
            val v = obj.opt(k)
            if (v is JSONArray) return v
        }
        keys.forEach { k ->
            val v = obj.opt(k)
            if (v is JSONObject) return JSONArray().put(v)
        }
        // 对象本身就是一门课
        val flat = flatten(obj)
        if (flat.containsKey("name") || flat.containsKey("课程") || flat.containsKey("课程名")) {
            return JSONArray().put(obj)
        }
        return null
    }

    private fun parseArray(array: JSONArray, periodCount: Int, totalWeeks: Int): List<ParsedCourse> {
        val unique = LinkedHashMap<String, ParsedCourse>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val course = fromObject(obj, periodCount, totalWeeks) ?: continue
            unique[course.signature()] = course
        }
        return unique.values.sortedWith(compareBy({ it.day }, { it.startPeriod }))
    }

    // ------------------------------------------------------------------ 单条课程

    private fun fromObject(obj: JSONObject, periodCount: Int, totalWeeks: Int): ParsedCourse? {
        val m = flatten(obj)

        val name = pickString(m, "name", "coursename", "course", "title", "subject", "课程", "课程名", "课程名称")
            ?.trim().orEmpty()
        if (name.isEmpty()) return null

        val teacher = pickString(m, "teacher", "teachername", "instructor", "professor", "prof", "任课教师", "教师", "老师")
            ?.trim().orEmpty()
        val location = pickString(m, "location", "room", "place", "classroom", "site", "上课地点", "地点", "教室", "上课教室")
            ?.trim().orEmpty()

        val day = parseDay(pickAny(m, "day", "dayofweek", "weekday", "week", "星期", "星期几", "上课星期"))

        val periodValue = pickAny(m, "period", "periods", "section", "sections", "slot", "节次", "节", "节数")
        var start = parseInt(pickAny(m, "startperiod", "periodstart", "start", "from", "开始节次", "起始节次", "开始节", "起节")) ?: 0
        var end = parseInt(pickAny(m, "endperiod", "periodend", "end", "to", "结束节次", "终止节次", "结束节", "止节")) ?: 0
        if (start <= 0 && periodValue != null) {
            parseRange(periodValue)?.let { start = it.first; end = if (end <= 0) it.second else end }
        }
        if (start <= 0) return null
        if (end <= 0) end = start

        val weeksValue = pickAny(m, "weeks", "weeksrange", "weekrange", "周次", "上课周次", "周")
        var startWeek = parseInt(pickAny(m, "startweek", "weekstart", "起始周", "开始周", "起周")) ?: 0
        var endWeek = parseInt(pickAny(m, "endweek", "weekend", "结束周", "终止周", "止周")) ?: 0
        if (startWeek <= 0 && weeksValue != null) {
            parseRange(weeksValue)?.let { startWeek = it.first; if (endWeek <= 0) endWeek = it.second }
        }
        if (startWeek <= 0) startWeek = 1
        if (endWeek <= 0) endWeek = maxOf(startWeek, totalWeeks)

        var weekType = parseWeekType(pickAny(m, "weektype", "weekparity", "parity", "type", "单双周"))
        if (weekType == 0) {
            val probe = buildString {
                (weeksValue as? String)?.let { append(it) }
                pickString(m, "weeks", "weektype", "单双周", "周次")?.let { append(' ').append(it) }
            }
            weekType = when {
                probe.contains("单") || probe.contains("odd", true) -> 1
                probe.contains("双") || probe.contains("even", true) -> 2
                else -> 0
            }
        }

        val safePeriodCount = periodCount.coerceAtLeast(1)
        val safeWeeks = totalWeeks.coerceAtLeast(1)

        val s = start.coerceIn(1, safePeriodCount)
        val e = end.coerceIn(s, safePeriodCount)
        val sw = startWeek.coerceIn(1, safeWeeks)
        val ew = endWeek.coerceIn(sw, safeWeeks)

        return ParsedCourse(
            name = name,
            teacher = teacher,
            location = location,
            day = day.coerceIn(1, 7),
            startPeriod = s,
            endPeriod = e,
            startWeek = sw,
            endWeek = ew,
            weekType = weekType.coerceIn(0, 2)
        )
    }

    // ------------------------------------------------------------------ 字段读取

    private fun flatten(obj: JSONObject): Map<String, Any?> {
        val map = HashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            map[normalizeKey(k)] = if (v == JSONObject.NULL) null else v
        }
        return map
    }

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace("_", "").replace("-", "").replace(" ", "").replace("　", "")

    private fun pickAny(map: Map<String, Any?>, vararg keys: String): Any? {
        keys.forEach { key ->
            val v = map[normalizeKey(key)]
            if (v != null && (v !is String || v.isNotBlank())) return v
        }
        return null
    }

    private fun pickString(map: Map<String, Any?>, vararg keys: String): String? =
        when (val v = pickAny(map, *keys)) {
            null -> null
            is String -> v
            is Number -> v.toString()
            is JSONArray -> (0 until v.length()).mapNotNull { i -> v.optString(i).takeIf { it.isNotBlank() } }.joinToString(" / ")
            else -> null
        }

    private fun parseInt(value: Any?): Int? = when (value) {
        null -> null
        is Number -> value.toInt()
        is String -> Regex("-?\\d+").find(value)?.value?.toIntOrNull()
        else -> null
    }

    private fun parseRange(value: Any?): Pair<Int, Int>? = when (value) {
        null -> null
        is Number -> value.toInt().let { it to it }
        is JSONArray -> {
            val first = parseInt(value.opt(0))
            val second = if (value.length() > 1) parseInt(value.opt(1)) else null
            if (first == null) null else first to (second ?: first)
        }
        is String -> {
            val nums = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
            when {
                nums.isEmpty() -> null
                nums.size == 1 -> nums[0] to nums[0]
                else -> nums[0] to nums[1]
            }
        }
        else -> null
    }

    private fun parseWeekType(value: Any?): Int = when (value) {
        null -> 0
        is Number -> value.toInt().coerceIn(0, 2)
        is String -> when {
            value.contains("单") || value.contains("odd", true) -> 1
            value.contains("双") || value.contains("even", true) -> 2
            else -> parseInt(value)?.coerceIn(0, 2) ?: 0
        }
        else -> 0
    }

    private fun parseDay(value: Any?): Int {
        when (value) {
            is Number -> return value.toInt().let { if (it == 0) 7 else it.coerceIn(1, 7) }
            is String -> {
                val s = value.trim().lowercase()
                Regex("\\d+").find(s)?.value?.toIntOrNull()?.let { n ->
                    return if (n == 0) 7 else n.coerceIn(1, 7)
                }
                val cn = listOf(
                    '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7
                )
                cn.forEach { (c, d) -> if (s.contains(c)) return d }
                val en = listOf(
                    "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6, "sun" to 7
                )
                en.forEach { (k, d) -> if (s.contains(k)) return d }
            }
        }
        return 1
    }

    // ------------------------------------------------------------------ 纯文本兜底

    /**
     * AI 没按 JSON 输出时的兜底：按行找「星期 / 节次 / 周次」三类特征，剩下的当课程名、教师、地点。
     */
    private fun parseLines(text: String, periodCount: Int, totalWeeks: Int): List<ParsedCourse> {
        val dayRegex = Regex("(星期|周)[一二三四五六日天]")
        val periodRegex = Regex("第?\\s*\\d{1,2}\\s*[-~至到,，、]\\s*\\d{1,2}\\s*节?")
        val periodSingle = Regex("第\\s*\\d{1,2}\\s*节")
        val weekRegex = Regex("第?\\s*\\d{1,2}\\s*[-~至到]\\s*\\d{1,2}\\s*周|第\\s*\\d{1,2}\\s*周")
        val locationRegex = Regex(
            "([\\u4e00-\\u9fa5]{1,6}(楼|馆|场|室|中心|教室)|[\\u4e00-\\u9fa5]{1,3}[A-Za-z]?\\d{2,4}|[A-Za-z]{1,3}\\s?\\d{2,4})"
        )

        val result = LinkedHashMap<String, ParsedCourse>()
        text.lines()
            .asSequence()
            .map { it.replace("|", " ").replace("*", " ").replace("#", " ").trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.all { c -> c in "-—:： " } }
            .forEach { line ->
                val dayMatch = dayRegex.find(line) ?: return@forEach
                val periodMatch = periodRegex.find(line) ?: periodSingle.find(line) ?: return@forEach
                val weekMatch = weekRegex.find(line)

                // 先摘掉星期/节次/周次，剩下的才可能是地点，避免把「第16周」当地点
                var rest = line
                listOfNotNull(dayMatch, periodMatch, weekMatch).forEach { m ->
                    rest = rest.replace(m.value, " ")
                }
                val locationMatch = locationRegex.find(rest)
                if (locationMatch != null) rest = rest.replace(locationMatch.value, " ")
                rest = rest.replace(Regex("[\\s,，;；:：、\\-—()（）\\[\\]【】]+"), " ").trim()
                val name = rest.split(' ').firstOrNull { it.length >= 2 && !it.all { c -> c.isDigit() } }
                    ?: return@forEach

                val parts = rest.split(' ')
                    .filter { it.isNotBlank() && it != name }
                val teacher = parts.firstOrNull { it.length in 2..4 && it.all { c -> c.code in 0x4E00..0x9FFF } }.orEmpty()

                val day = parseDay(dayMatch.value)
                val period = parseRange(periodMatch.value) ?: return@forEach
                val weeks = weekMatch?.let { parseRange(it.value) }
                val weekType = when {
                    line.contains("单周") || line.contains("(单)") || line.contains("（单）") -> 1
                    line.contains("双周") || line.contains("(双)") || line.contains("（双）") -> 2
                    else -> 0
                }

                val safePeriodCount = periodCount.coerceAtLeast(1)
                val safeWeeks = totalWeeks.coerceAtLeast(1)
                val s = period.first.coerceIn(1, safePeriodCount)
                val e = period.second.coerceIn(s, safePeriodCount)
                val sw = (weeks?.first ?: 1).coerceIn(1, safeWeeks)
                val ew = (weeks?.second ?: totalWeeks).coerceIn(sw, safeWeeks)

                val course = ParsedCourse(
                    name = name,
                    teacher = teacher,
                    location = locationMatch?.value?.trim().orEmpty(),
                    day = day,
                    startPeriod = s,
                    endPeriod = e,
                    startWeek = sw,
                    endWeek = ew,
                    weekType = weekType
                )
                result[course.signature()] = course
            }
        return result.values.sortedWith(compareBy({ it.day }, { it.startPeriod }))
    }
}
