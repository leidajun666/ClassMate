package com.classmate.timetable.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 本地 SQLite 存储：课程表 + 节次时间表。 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE courses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                teacher TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                day INTEGER NOT NULL,
                start_period INTEGER NOT NULL,
                end_period INTEGER NOT NULL,
                start_week INTEGER NOT NULL,
                end_week INTEGER NOT NULL,
                week_type INTEGER NOT NULL DEFAULT 0,
                color INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE periods (
                idx INTEGER PRIMARY KEY,
                start_time TEXT NOT NULL,
                end_time TEXT NOT NULL
            )
            """.trimIndent()
        )
        Period.defaults().forEach { p ->
            val cv = ContentValues().apply {
                put("idx", p.index)
                put("start_time", p.start)
                put("end_time", p.end)
            }
            db.insert("periods", null, cv)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前只有 v1，后续升级在此按版本迁移
    }

    fun courses(): MutableList<Course> {
        val list = mutableListOf<Course>()
        readableDatabase.query("courses", null, null, null, null, null, "day ASC, start_period ASC")
            .use { c ->
                while (c.moveToNext()) list.add(c.toCourse())
            }
        return list
    }

    fun courseById(id: Long): Course? {
        readableDatabase.query("courses", null, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { c ->
                if (c.moveToFirst()) return c.toCourse()
            }
        return null
    }

    fun save(course: Course): Long {
        val cv = ContentValues().apply {
            put("name", course.name)
            put("teacher", course.teacher)
            put("location", course.location)
            put("day", course.dayOfWeek)
            put("start_period", course.startPeriod)
            put("end_period", course.endPeriod)
            put("start_week", course.startWeek)
            put("end_week", course.endWeek)
            put("week_type", course.weekType)
            put("color", course.colorIndex)
            put("note", course.note)
        }
        return if (course.id > 0) {
            writableDatabase.update("courses", cv, "id = ?", arrayOf(course.id.toString()))
            course.id
        } else {
            writableDatabase.insert("courses", null, cv)
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete("courses", "id = ?", arrayOf(id.toString()))
    }

    fun insertAll(courses: List<Course>): Int {
        if (courses.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        var count = 0
        try {
            courses.forEach { c ->
                val cv = ContentValues().apply {
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("location", c.location)
                    put("day", c.dayOfWeek)
                    put("start_period", c.startPeriod)
                    put("end_period", c.endPeriod)
                    put("start_week", c.startWeek)
                    put("end_week", c.endWeek)
                    put("week_type", c.weekType)
                    put("color", c.colorIndex)
                    put("note", c.note)
                }
                if (db.insert("courses", null, cv) > 0) count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun replaceAll(courses: List<Course>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("courses", null, null)
            courses.forEach { c ->
                val cv = ContentValues().apply {
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("location", c.location)
                    put("day", c.dayOfWeek)
                    put("start_period", c.startPeriod)
                    put("end_period", c.endPeriod)
                    put("start_week", c.startWeek)
                    put("end_week", c.endWeek)
                    put("week_type", c.weekType)
                    put("color", c.colorIndex)
                    put("note", c.note)
                }
                db.insert("courses", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearCourses() {
        writableDatabase.delete("courses", null, null)
    }

    fun periods(): MutableList<Period> {
        val list = mutableListOf<Period>()
        readableDatabase.query("periods", null, null, null, null, null, "idx ASC").use { c ->
            while (c.moveToNext()) {
                list.add(
                    Period(
                        index = c.getInt(c.getColumnIndexOrThrow("idx")),
                        start = c.getString(c.getColumnIndexOrThrow("start_time")),
                        end = c.getString(c.getColumnIndexOrThrow("end_time"))
                    )
                )
            }
        }
        if (list.isEmpty()) {
            Period.defaults().forEach { savePeriod(it) }
            return Period.defaults().toMutableList()
        }
        return list
    }

    fun savePeriod(p: Period) {
        val cv = ContentValues().apply {
            put("idx", p.index)
            put("start_time", p.start)
            put("end_time", p.end)
        }
        writableDatabase.insertWithOnConflict("periods", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 调整每天的最大节次（支持 4~16 节），解决“课表节次超过 12 节就没地方放”的问题。
     * 减少节次时把超出范围的课程压回最后一节，避免课程直接消失。
     */
    fun setPeriodCount(count: Int) {
        val target = count.coerceIn(4, 16)
        val current = periods()
        when {
            target > current.size -> {
                var lastEnd = current.lastOrNull()?.end ?: "22:30"
                for (i in current.size + 1..target) {
                    val startMinutes = Period.parseMinutes(lastEnd) + 10
                    val period = Period(
                        index = i,
                        start = Period.formatMinutes(startMinutes),
                        end = Period.formatMinutes(startMinutes + 45)
                    )
                    savePeriod(period)
                    lastEnd = period.end
                }
            }
            target < current.size -> {
                val db = writableDatabase
                db.beginTransaction()
                try {
                    db.delete("periods", "idx > ?", arrayOf(target.toString()))
                    db.execSQL("UPDATE courses SET end_period = $target WHERE end_period > $target")
                    db.execSQL("UPDATE courses SET start_period = $target WHERE start_period > $target")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    private fun Cursor.toCourse(): Course = Course(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")) ?: "",
        teacher = getString(getColumnIndexOrThrow("teacher")) ?: "",
        location = getString(getColumnIndexOrThrow("location")) ?: "",
        dayOfWeek = getInt(getColumnIndexOrThrow("day")),
        startPeriod = getInt(getColumnIndexOrThrow("start_period")),
        endPeriod = getInt(getColumnIndexOrThrow("end_period")),
        startWeek = getInt(getColumnIndexOrThrow("start_week")),
        endWeek = getInt(getColumnIndexOrThrow("end_week")),
        weekType = getInt(getColumnIndexOrThrow("week_type")),
        colorIndex = getInt(getColumnIndexOrThrow("color")),
        note = getString(getColumnIndexOrThrow("note")) ?: ""
    )

    companion object {
        private const val DB_NAME = "classmate.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context).also { instance = it }
            }
    }
}
