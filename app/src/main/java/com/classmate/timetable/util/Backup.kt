package com.classmate.timetable.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.classmate.timetable.data.Course
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Period
import com.classmate.timetable.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** 课表备份（JSON）：课程 + 节次时间 + 学期设置。 */
object Backup {

    fun exportJson(context: Context): String {
        val prefs = Prefs(context)
        val db = Db.get(context)
        val root = JSONObject()
        root.put("app", "ClassMate")
        root.put("version", 1)
        root.put("semesterStart", prefs.semesterStart.toString())
        root.put("totalWeeks", prefs.totalWeeks)

        val courses = JSONArray()
        db.courses().forEach { c ->
            courses.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("location", c.location)
                    put("day", c.dayOfWeek)
                    put("startPeriod", c.startPeriod)
                    put("endPeriod", c.endPeriod)
                    put("startWeek", c.startWeek)
                    put("endWeek", c.endWeek)
                    put("weekType", c.weekType)
                    put("color", c.colorIndex)
                    put("note", c.note)
                }
            )
        }
        root.put("courses", courses)

        val periods = JSONArray()
        db.periods().forEach { p ->
            periods.put(
                JSONObject().apply {
                    put("index", p.index)
                    put("start", p.start)
                    put("end", p.end)
                }
            )
        }
        root.put("periods", periods)
        return root.toString(2)
    }

    /** 返回导入的课程数；replace 为 true 时先清空原有课程。 */
    fun importJson(context: Context, text: String, replace: Boolean): Int {
        val root = JSONObject(text)
        val prefs = Prefs(context)
        val courses = mutableListOf<Course>()
        val array = root.optJSONArray("courses") ?: JSONArray()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            courses.add(
                Course(
                    name = name,
                    teacher = o.optString("teacher"),
                    location = o.optString("location"),
                    dayOfWeek = o.optInt("day", 1).coerceIn(1, 7),
                    startPeriod = o.optInt("startPeriod", 1).coerceIn(1, 20),
                    endPeriod = o.optInt("endPeriod", 2).coerceIn(1, 20),
                    startWeek = o.optInt("startWeek", 1).coerceIn(1, 40),
                    endWeek = o.optInt("endWeek", 20).coerceIn(1, 40),
                    weekType = o.optInt("weekType", 0),
                    colorIndex = o.optInt("color", 0),
                    note = o.optString("note")
                )
            )
        }
        val db = Db.get(context)
        if (replace) {
            db.replaceAll(courses)
        } else {
            db.insertAll(courses)
        }

        val periodsArray = root.optJSONArray("periods")
        if (periodsArray != null) {
            for (i in 0 until periodsArray.length()) {
                val o = periodsArray.optJSONObject(i) ?: continue
                val index = o.optInt("index", 0)
                val start = o.optString("start")
                val end = o.optString("end")
                if (index > 0 && start.isNotBlank() && end.isNotBlank()) {
                    db.savePeriod(Period(index, start, end))
                }
            }
        }

        val start = root.optString("semesterStart")
        if (start.isNotBlank()) {
            runCatching { prefs.semesterStart = java.time.LocalDate.parse(start) }
        }
        val weeks = root.optInt("totalWeeks", 0)
        if (weeks > 0) prefs.totalWeeks = weeks
        return courses.size
    }

    fun writeTo(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    fun readFrom(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()

    fun writeBitmap(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }

    fun shareIntent(uri: Uri, mime: String, title: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
}
