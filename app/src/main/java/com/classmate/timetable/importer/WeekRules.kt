package com.classmate.timetable.importer

/**
 * 周次文本 ⇄ 周次列表。
 *
 * 教务系统导出的周次写法很杂：`1-16周`、`1-16周(单)`、`1-8,10-16周`、`第3周`。
 * App 的课程只能存「起止周 + 单双周」，所以这里把任意周次集合拆成若干连续区间，
 * 每段生成一条 ParsedCourse：`1-8,10-16` 会拆成两条，单双周则合并成一条带 weekType 的课程。
 */
object WeekRules {

    data class Spec(val weeks: List<Int>, val oddOnly: Boolean, val evenOnly: Boolean)

    fun parse(text: String?): Spec {
        if (text.isNullOrBlank()) return Spec(emptyList(), false, false)
        var s = text
        val odd = Regex("单周|[（(]\\s*单\\s*[）)]").containsMatchIn(s)
        val even = Regex("双周|[（(]\\s*双\\s*[）)]").containsMatchIn(s)
        s = s.replace(Regex("[（(]\\s*[单双]\\s*[）)]"), " ")
            .replace("单周", " ").replace("双周", " ")
            .replace("周", " ")
            .replace(Regex("第"), " ")

        val out = sortedSetOf<Int>()
        s.split(Regex("[,，、;；\\s]+")).filter { it.isNotBlank() }.forEach { part ->
            val m = Regex("^(\\d+)\\s*[-~至到]\\s*(\\d+)$").find(part.trim())
            if (m != null) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                for (w in minOf(a, b)..maxOf(a, b)) out.add(w)
            } else {
                Regex("^\\d+$").find(part.trim())?.let { out.add(it.value.toInt()) }
            }
        }
        return Spec(out.toList(), odd, even)
    }

    fun compress(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val a = weeks.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = a[0]
        var prev = a[0]
        for (i in 1..a.size) {
            val cur = if (i < a.size) a[i] else Int.MIN_VALUE
            if (cur == prev + 1) {
                prev = cur
                continue
            }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = cur
            prev = cur
        }
        return parts.joinToString(",") + "周"
    }

    /**
     * 把「课程 + 周次集合」展开成若干条可直接入库的课程。
     *
     * @param weekText 原始周次文本，用于判断单双周
     * @param weeks    显式周次（为空则用整学期）
     */
    fun spread(
        name: String,
        teacher: String,
        location: String,
        day: Int,
        startPeriod: Int,
        endPeriod: Int,
        weekText: String?,
        weeks: List<Int>,
        totalWeeks: Int,
        note: String = ""
    ): List<ParsedCourse> {
        val safeTotal = totalWeeks.coerceIn(1, 40)
        val spec = parse(weekText)
        var list = (if (weeks.isNotEmpty()) weeks else spec.weeks)
            .filter { it in 1..safeTotal }
            .distinct()
            .sorted()

        val odd = spec.oddOnly
        val even = spec.evenOnly
        if (odd && even) {
            // 既是单周又是双周，按全周处理
        } else if (odd) {
            list = list.filter { it % 2 == 1 }
        } else if (even) {
            list = list.filter { it % 2 == 0 }
        }

        val base = ParsedCourse(
            name = name.trim(),
            teacher = teacher.trim(),
            location = location.trim(),
            day = day.coerceIn(1, 7),
            startPeriod = startPeriod.coerceAtLeast(1),
            endPeriod = endPeriod.coerceAtLeast(startPeriod.coerceAtLeast(1)),
            note = note
        )

        if (list.isEmpty()) {
            return listOf(base.copy(startWeek = 1, endWeek = safeTotal, weekType = 0))
        }

        // 单/双周：起止覆盖整段，用 weekType 表达，避免拆成一堆单周课程
        if (odd != even) {
            val type = if (odd) 1 else 2
            return listOf(base.copy(
                startWeek = list.first(),
                endWeek = list.last(),
                weekType = type
            ))
        }

        // 连续区间合并，跳周（1-8,10-16）拆成多条
        val result = mutableListOf<ParsedCourse>()
        var runStart = list.first()
        var prev = list.first()
        for (i in 1..list.size) {
            val cur = if (i < list.size) list[i] else Int.MIN_VALUE
            if (cur == prev + 1) {
                prev = cur
                continue
            }
            result.add(base.copy(startWeek = runStart, endWeek = prev, weekType = 0))
            runStart = cur
            prev = cur
        }
        return result
    }
}
