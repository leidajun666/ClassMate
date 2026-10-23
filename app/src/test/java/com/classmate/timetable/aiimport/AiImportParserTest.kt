package com.classmate.timetable.aiimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用真实 AI 可能返回的各种格式校验解析器。 */
class AiImportParserTest {

    private val periods = 12
    private val weeks = 20

    private fun parse(text: String) = AiImportParser.parse(text, periods, weeks)

    @Test
    fun pureJsonArray() {
        val json = """
            [{"name":"高等数学（上）","teacher":"张三","location":"教一101","day":1,
              "startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16,"weekType":0}]
        """.trimIndent()
        val r = parse(json)
        assertEquals(1, r.courses.size)
        val c = r.courses[0]
        assertEquals("高等数学（上）", c.name)
        assertEquals("张三", c.teacher)
        assertEquals("教一101", c.location)
        assertEquals(1, c.day)
        assertEquals(1, c.startPeriod)
        assertEquals(2, c.endPeriod)
        assertEquals(16, c.endWeek)
        assertTrue(r.fromJson)
    }

    @Test
    fun fencedJsonWithChatter() {
        val text = """
            好的，我已经识别出课表内容如下：
            ```json
            [
              {"name":"大学英语","day":3,"startPeriod":3,"endPeriod":4,"startWeek":1,"endWeek":16,"weekType":0},
              {"name":"体育","day":5,"startPeriod":5,"endPeriod":6,"startWeek":2,"endWeek":18,"weekType":0}
            ]
            ```
            以上共 2 门课程，如有出入请告诉我。
        """.trimIndent()
        val r = parse(text)
        assertEquals(2, r.courses.size)
        assertEquals("大学英语", r.courses[0].name)
        assertEquals(3, r.courses[0].day)
    }

    @Test
    fun wrappedInObject() {
        val text = """{"courses":[{"name":"线性代数","day":2,"startPeriod":1,"endPeriod":2}],"note":"ok"}"""
        val r = parse(text)
        assertEquals(1, r.courses.size)
        assertEquals("线性代数", r.courses[0].name)
        // 缺字段时给默认值：第 1 周 ~ 总周数
        assertEquals(1, r.courses[0].startWeek)
        assertEquals(weeks, r.courses[0].endWeek)
    }

    @Test
    fun chineseKeysAndTextValues() {
        val text = """
            [{"课程名称":"概率论","任课教师":"李四","上课地点":"理学楼305","星期":"周三",
              "节次":"3-4节","周次":"1-16周（单）"}]
        """.trimIndent()
        val r = parse(text)
        assertEquals(1, r.courses.size)
        val c = r.courses[0]
        assertEquals("概率论", c.name)
        assertEquals("李四", c.teacher)
        assertEquals("理学楼305", c.location)
        assertEquals(3, c.day)
        assertEquals(3, c.startPeriod)
        assertEquals(4, c.endPeriod)
        assertEquals(1, c.startWeek)
        assertEquals(16, c.endWeek)
        assertEquals(1, c.weekType)
    }

    @Test
    fun englishDayAndArrayPeriods() {
        val text = """
            [{"name":"Operating System","teacher":"Smith","day":"Wednesday",
              "periods":[7,8],"weeks":"3-15","weekType":0}]
        """.trimIndent()
        val r = parse(text)
        assertEquals(1, r.courses.size)
        assertEquals(3, r.courses[0].day)
        assertEquals(7, r.courses[0].startPeriod)
        assertEquals(8, r.courses[0].endPeriod)
        assertEquals(3, r.courses[0].startWeek)
        assertEquals(15, r.courses[0].endWeek)
    }

    @Test
    fun clampsOutOfRange() {
        val text = """[{"name":"晚课","day":9,"startPeriod":13,"endPeriod":15,"startWeek":25,"endWeek":30}]"""
        val r = parse(text)
        assertEquals(1, r.courses.size)
        val c = r.courses[0]
        assertEquals(7, c.day)
        assertEquals(12, c.startPeriod)
        assertEquals(12, c.endPeriod)
        assertEquals(weeks, c.startWeek)
        assertEquals(weeks, c.endWeek)
    }

    @Test
    fun dedupesIdenticalCourses() {
        val text = """
            [{"name":"高数","day":1,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16},
             {"name":"高数","day":1,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16},
             {"name":"高数","day":2,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16}]
        """.trimIndent()
        assertEquals(2, parse(text).courses.size)
    }

    @Test
    fun markdownTableFallback() {
        val text = """
            | 课程 | 时间 |
            | --- | --- |
            | 高等数学 | 周一 1-2节 1-16周 教一101 |
            | 大学物理 | 周三 5-6节 1-16周 理学楼305 |
        """.trimIndent()
        val r = parse(text)
        assertEquals(2, r.courses.size)
        assertEquals("高等数学", r.courses[0].name)
        assertEquals(1, r.courses[0].day)
        assertEquals("教一101", r.courses[0].location)
        assertEquals(3, r.courses[1].day)
        assertTrue(!r.fromJson)
    }

    @Test
    fun rejectsNonTimetable() {
        val r = parse("这张图片里似乎没有课程表，只有一张风景照。")
        assertTrue(r.courses.isEmpty())
        assertTrue(r.message.isNotEmpty())
    }

    @Test
    fun handlesBrokenJson() {
        val r = parse("[{\"name\":\"高数\",\"day\":1,}]")
        assertTrue(r.courses.isEmpty())
        assertTrue(r.message.contains("格式") || r.message.contains("JSON"))
    }
}
