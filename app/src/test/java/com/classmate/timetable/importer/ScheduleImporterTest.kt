package com.classmate.timetable.importer

import com.classmate.timetable.data.Period
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 教务导出文件的导入校验：ICS / CSV(GBK) / HTML 伪 xls / xlsx / JSON / WebView 抓取。 */
class ScheduleImporterTest {

    private val periods = listOf(
        Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40"),
        Period(3, "10:00", "10:45"), Period(4, "10:55", "11:40"),
        Period(5, "14:00", "14:45"), Period(6, "14:55", "15:40"),
        Period(7, "16:00", "16:45"), Period(8, "16:55", "17:40"),
        Period(9, "19:00", "19:45"), Period(10, "19:55", "20:40"),
        Period(11, "20:50", "21:35"), Period(12, "21:45", "22:30")
    )
    private val totalWeeks = 20
    private val termStart = LocalDate.of(2026, 9, 7)

    private fun import(name: String, text: String) =
        ScheduleFileImporter.import(name, text.toByteArray(), periods, totalWeeks, termStart)

    @Test
    fun icsImport() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:高等数学（上）
            LOCATION:教一101
            DESCRIPTION:教师：张三\n1-16周
            DTSTART;TZID=Asia/Shanghai:20260907T080000
            DTEND;TZID=Asia/Shanghai:20260907T094000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val r = import("课表.ics", ics)
        assertEquals(1, r.courses.size)
        val c = r.courses[0]
        assertEquals("高等数学（上）", c.name)
        assertEquals("张三", c.teacher)
        assertEquals("教一101", c.location)
        assertEquals(1, c.day)
        assertEquals(1, c.startPeriod)
        assertEquals(2, c.endPeriod)
        assertEquals(1, c.startWeek)
        assertEquals(16, c.endWeek)
        assertEquals("ics", r.format)
    }

    @Test
    fun gbkCsvImport() {
        val csv = "课程名称,任课教师,上课地点,星期,开始节次,结束节次,周次\n" +
            "高等数学,张三,教一101,周一,1,2,1-16周\n" +
            "大学英语,李四,外语楼203,周三,3,4,1-16周(单)\n"
        val bytes = csv.toByteArray(Charset.forName("GBK"))   // 教务导出常见 GBK
        val r = ScheduleFileImporter.import("课表.csv", bytes, periods, totalWeeks, termStart)

        assertEquals(2, r.courses.size)
        val math = r.courses.first { it.name == "高等数学" }
        assertEquals(1, math.day)
        assertEquals(1, math.startPeriod)
        assertEquals(16, math.endWeek)

        val english = r.courses.first { it.name == "大学英语" }
        assertEquals(3, english.day)
        assertEquals(1, english.weekType)   // 单周
    }

    @Test
    fun htmlFakeXlsImport() {
        val html = """
            <html><body><table border="1">
            <tr><th>节次</th><th>周一</th><th>周二</th><th>周三</th></tr>
            <tr><td>1-2节</td>
                <td>高等数学<br>张三<br>1-16周<br>教一101</td>
                <td></td>
                <td>大学英语<br>李四<br>1-8周<br>外语楼203</td></tr>
            <tr><td>3-4节</td>
                <td>大学物理<br>王五<br>1-16周<br>理学楼305</td>
                <td></td><td></td></tr>
            </table></body></html>
        """.trimIndent()

        val r = import("课表.xls", html)
        assertEquals(3, r.courses.size)

        val math = r.courses.first { it.name == "高等数学" }
        assertEquals(1, math.day)
        assertEquals(1, math.startPeriod)
        assertEquals(2, math.endPeriod)
        assertEquals("张三", math.teacher)
        assertEquals("教一101", math.location)

        val english = r.courses.first { it.name == "大学英语" }
        assertEquals(3, english.day)
        assertEquals(8, english.endWeek)
    }

    @Test
    fun xlsxImport() {
        val r = ScheduleFileImporter.import("课表.xlsx", buildXlsx(), periods, totalWeeks, termStart)
        assertEquals(1, r.courses.size)
        val c = r.courses[0]
        assertEquals("高等数学", c.name)
        assertEquals("张三", c.teacher)
        assertEquals(1, c.day)
        assertEquals(1, c.startPeriod)
        assertEquals(16, c.endWeek)
    }

    @Test
    fun jsonImport() {
        val json = """
            [{"name":"线性代数","teacher":"赵六","location":"理学楼101","day":2,
              "startSection":3,"endSection":4,"weeks":[1,2,3,4,5]}]
        """.trimIndent()
        val r = import("courses.json", json)
        assertEquals(1, r.courses.size)
        val c = r.courses[0]
        assertEquals("线性代数", c.name)
        assertEquals(2, c.day)
        assertEquals(3, c.startPeriod)
        assertEquals(1, c.startWeek)
        assertEquals(5, c.endWeek)
    }

    @Test
    fun weekRulesSplitsGapAndOddWeeks() {
        val gap = WeekRules.spread("课", "", "", 1, 1, 2, "1-8,10-16周", emptyList(), 20)
        assertEquals(2, gap.size)
        assertEquals(1, gap[0].startWeek)
        assertEquals(8, gap[0].endWeek)
        assertEquals(10, gap[1].startWeek)
        assertEquals(16, gap[1].endWeek)

        val odd = WeekRules.spread("课", "", "", 1, 1, 2, "1-16周(单)", emptyList(), 20)
        assertEquals(1, odd.size)
        assertEquals(1, odd[0].weekType)
        assertEquals(1, odd[0].startWeek)
        assertEquals(15, odd[0].endWeek)

        val even = WeekRules.spread("课", "", "", 1, 1, 2, "2-16周（双）", emptyList(), 20)
        assertEquals(2, even[0].weekType)
    }

    @Test
    fun webviewCaptureImport() {
        val payload = JSONObject().apply {
            put("ok", true)
            put("text", "第一周 课表")
            put("tables", JSONArray().put(JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONArray().put("节次").put(1).put(1))
                    put(JSONArray().put("周一").put(1).put(1))
                    put(JSONArray().put("周二").put(1).put(1))
                    put(JSONArray().put("周三").put(1).put(1))
                })
                put(JSONArray().apply {
                    put(JSONArray().put("1-2节").put(1).put(1))
                    put(JSONArray().put("数据结构\n孙七\n1-16周\n信息楼402").put(1).put(1))
                    put(JSONArray().put("").put(1).put(1))
                    put(JSONArray().put("").put(1).put(1))
                })
                put(JSONArray().apply {
                    put(JSONArray().put("3-4节").put(1).put(1))
                    put(JSONArray().put("").put(1).put(1))
                    put(JSONArray().put("操作系统\n周八\n1-16周\n信息楼403").put(1).put(1))
                    put(JSONArray().put("").put(1).put(1))
                })
            }))
        }

        val r = ScheduleFileImporter.fromCapture(payload.toString(), periods, totalWeeks)
        assertEquals(2, r.courses.size)
        assertEquals("数据结构", r.courses[0].name)
        assertEquals(1, r.courses[0].day)
        assertEquals(2, r.courses[1].day)
        assertEquals(3, r.courses[1].startPeriod)
    }

    @Test
    fun htmlRowspanCourseIsMerged() {
        val html = """
            <table>
            <tr><th>节次</th><th>周一</th><th>周二</th><th>周三</th></tr>
            <tr><td>1</td><td rowspan="2">高等数学<br>张三<br>1-16周<br>教一101</td><td></td><td></td></tr>
            <tr><td>2</td><td></td><td></td></tr>
            <tr><td>3-4节</td><td></td><td></td><td></td></tr>
            </table>
        """.trimIndent()
        val r = import("rowspan.xls", html)
        assertEquals(1, r.courses.size)
        assertEquals(1, r.courses[0].startPeriod)
        assertEquals(2, r.courses[0].endPeriod)
    }

    @Test
    fun captureWithoutTableGivesHint() {
        val payload = JSONObject().put("ok", true).put("text", "登录页").toString()
        val r = ScheduleFileImporter.fromCapture(payload, periods, totalWeeks)
        assertTrue(r.courses.isEmpty())
        assertTrue(r.message.contains("课表"))
    }

    @Test
    fun ole2XlsIsRejectedWithHint() {
        val bytes = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
        val r = ScheduleFileImporter.import("old.xls", bytes, periods, totalWeeks, termStart)
        assertTrue(r.courses.isEmpty())
        assertTrue(r.message.contains("另存为"))
    }

    @Test
    fun detectionByContent() {
        assertEquals("ics", ScheduleFileImporter.detectFormat("x.txt", "BEGIN:VCALENDAR".toByteArray()))
        assertEquals("html", ScheduleFileImporter.detectFormat("x.txt", "<html><tr>".toByteArray()))
        assertEquals("json", ScheduleFileImporter.detectFormat("x.txt", "[{\"name\":\"a\"}]".toByteArray()))
        assertEquals("csv", ScheduleFileImporter.detectFormat("x.txt", "课程,教师".toByteArray()))
    }

    // ------------------------------------------------------------------ 造一个最小 xlsx

    private fun buildXlsx(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")
            put(
                "xl/sharedStrings.xml",
                """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                   <si><t>节次</t></si><si><t>周一</t></si><si><t>周二</t></si><si><t>周三</t></si>
                   <si><t>1-2节</t></si><si><t>高等数学
张三
1-16周
教一101</t></si></sst>"""
            )
            put(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                   <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
                   <row r="2"><c r="A2" t="s"><v>4</v></c><c r="B2" t="s"><v>5</v></c></row>
                   </sheetData></worksheet>"""
            )
        }
        return out.toByteArray()
    }
}
