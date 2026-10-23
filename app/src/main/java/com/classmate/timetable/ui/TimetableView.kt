package com.classmate.timetable.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.classmate.timetable.data.Course
import com.classmate.timetable.data.Period
import com.classmate.timetable.util.CourseColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 自绘周课表网格：左侧节次时间列 + 周一~周日（可隐藏周末），
 * 课程块自动分栏显示重叠课程，非本周课程淡化，今日列高亮。
 */
class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCourseClick: ((Course) -> Unit)? = null
    var onEmptyLongClick: ((day: Int, period: Int) -> Unit)? = null

    private var courses: List<Course> = emptyList()
    private var periods: List<Period> = emptyList()
    private var week: Int = 1
    private var showWeekend: Boolean = true
    private var dimOthers: Boolean = true
    private var dates: List<LocalDate> = emptyList()
    private var todayDay: Int = -1

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private fun d(v: Float) = v * density
    private fun sp(v: Float) = v * scaledDensity

    private val headerH = d(46f)
    private val timeW = d(38f)
    private val rowH = d(60f)

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return tv.data
        if (tv.resourceId != 0) {
            val csl = runCatching { ContextCompat.getColorStateList(context, tv.resourceId) }.getOrNull()
            if (csl != null) return csl.defaultColor
        }
        return fallback
    }

    private val cText = themeColor(android.R.attr.textColorPrimary, 0xFF1A1B21.toInt())
    private val cSub = themeColor(android.R.attr.textColorSecondary, 0xFF6A6C75.toInt())
    private val cPrimary = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF3F5AA6.toInt())
    private val cSurface = themeColor(android.R.attr.colorBackground, Color.WHITE)
    private val cLine = withAlpha(cSub, 40)
    private val cLineStrong = withAlpha(cSub, 70)
    private val cTodayBg = withAlpha(cPrimary, 20)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(0.8f)
        color = cLine
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        isSubpixelText = true
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        isSubpixelText = true
    }
    private val dayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val slotPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val slotTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(8.5f)
        textAlign = Paint.Align.CENTER
    }
    private val roundRect = RectF()

    private data class Hit(val rect: RectF, val course: Course)

    private val hits = ArrayList<Hit>()

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let {
                onCourseClick?.invoke(it)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (hitTest(e.x, e.y) != null) return
            val cell = cellAt(e.x, e.y) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onEmptyLongClick?.invoke(cell.first, cell.second)
        }
    })

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(
        courses: List<Course>,
        periods: List<Period>,
        week: Int,
        dates: List<LocalDate>,
        todayDay: Int,
        showWeekend: Boolean,
        dimOthers: Boolean
    ) {
        this.courses = courses
        this.periods = periods.ifEmpty { Period.defaults() }
        this.week = week
        this.dates = dates
        this.todayDay = todayDay
        this.showWeekend = showWeekend
        this.dimOthers = dimOthers
        requestLayout()
        invalidate()
    }

    private val dayCount: Int
        get() = if (showWeekend) 7 else 5

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = periods.size.coerceAtLeast(1)
        val h = (headerH + rows * rowH).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return
        val w = width.toFloat()
        val rows = periods.size
        if (rows == 0) return
        val cols = dayCount
        val colW = (w - timeW) / cols
        val totalH = headerH + rows * rowH + paddingBottom
        val todayColumn = if (todayDay in 1..7) todayDay else -1

        // 今日列背景
        if (todayColumn in 1..cols) {
            fillPaint.color = cTodayBg
            val left = timeW + (todayColumn - 1) * colW
            canvas.drawRect(left, 0f, left + colW, totalH, fillPaint)
        }

        // 表头文字
        for (day in 1..cols) {
            val cx = timeW + (day - 0.5f) * colW
            val isToday = day == todayColumn
            dayPaint.color = if (isToday) cPrimary else cText
            datePaint.color = if (isToday) cPrimary else cSub
            val dayName = Course.DAY_NAMES[day]
            canvas.drawText(dayName, cx, headerH * 0.48f, dayPaint)
            val dateText = dates.getOrNull(day - 1)?.let { it.format(MONTH_DAY) } ?: ""
            if (dateText.isNotEmpty()) {
                canvas.drawText(dateText, cx, headerH * 0.82f, datePaint)
            }
        }

        // 节次列
        for ((i, p) in periods.withIndex()) {
            val cy = headerH + (i + 0.5f) * rowH
            slotPaint.color = if (p.index % 2 == 1) cSub else cSub
            canvas.drawText(p.index.toString(), timeW / 2f, cy + sp(1f), slotPaint)
            slotTimePaint.color = withAlpha(cSub, 170)
            canvas.drawText(p.start, timeW / 2f, cy + sp(12f), slotTimePaint)
        }

        // 网格线
        gridPaint.color = cLine
        for (r in 0..rows) {
            val y = headerH + r * rowH
            canvas.drawLine(timeW, y, w, y, gridPaint)
        }
        for (c in 0..cols) {
            val x = timeW + c * colW
            canvas.drawLine(x, 0f, x, totalH, gridPaint)
        }
        gridPaint.color = cLineStrong
        canvas.drawLine(timeW, headerH, w, headerH, gridPaint)
        canvas.drawLine(0f, headerH, timeW, headerH, gridPaint)
        gridPaint.color = cLine
        canvas.drawLine(timeW, 0f, timeW, totalH, gridPaint)

        // 课程块
        hits.clear()
        for (day in 1..cols) {
            val dayCourses = courses.filter { it.dayOfWeek == day }
            if (dayCourses.isEmpty()) continue
            val colLeft = timeW + (day - 1) * colW
            drawDayCourses(canvas, dayCourses, colLeft, colW)
        }
    }

    private fun drawDayCourses(canvas: Canvas, list: List<Course>, colLeft: Float, colW: Float) {
        val sorted = list.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
        // 按“重叠簇”分组，簇内分栏
        var i = 0
        while (i < sorted.size) {
            val cluster = mutableListOf(sorted[i])
            var maxEnd = sorted[i].endPeriod
            var j = i + 1
            while (j < sorted.size && sorted[j].startPeriod <= maxEnd) {
                cluster.add(sorted[j])
                maxEnd = maxOf(maxEnd, sorted[j].endPeriod)
                j++
            }
            val lanes = ArrayList<MutableList<Course>>()
            for (c in cluster) {
                var placed = false
                for (lane in lanes) {
                    if (lane.last().endPeriod < c.startPeriod) {
                        lane.add(c)
                        placed = true
                        break
                    }
                }
                if (!placed) lanes.add(mutableListOf(c))
            }
            val laneW = colW / lanes.size
            lanes.forEachIndexed { laneIndex, lane ->
                lane.forEach { course ->
                    val left = colLeft + laneIndex * laneW + d(1.5f)
                    val right = colLeft + (laneIndex + 1) * laneW - d(1.5f)
                    val top = headerH + (course.startPeriod - 1) * rowH + d(2f)
                    val bottom = headerH + course.endPeriod * rowH - d(2f)
                    drawCourseBlock(canvas, course, left, top, right, bottom)
                }
            }
            i = j
        }
    }

    private fun drawCourseBlock(canvas: Canvas, course: Course, left: Float, top: Float, right: Float, bottom: Float) {
        val active = course.activeInWeek(week)
        val alpha = if (active) 255 else if (dimOthers) 64 else 150
        val fill = CourseColors.color(course.colorIndex)
        val onFill = CourseColors.onColor(fill)

        roundRect.set(left, top, right, bottom)
        val radius = d(9f)
        blockPaint.color = fill
        blockPaint.alpha = alpha
        blockPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(roundRect, radius, radius, blockPaint)

        val pad = d(5f)
        val textLeft = left + pad
        val textRight = right - pad
        val maxWidth = (textRight - textLeft).coerceAtLeast(d(10f))
        val height = bottom - top

        namePaint.color = onFill
        namePaint.alpha = alpha
        metaPaint.color = onFill
        metaPaint.alpha = (alpha * 0.85f).toInt()

        val nameLineH = sp(11f) * 1.18f
        val maxNameLines = ((height - d(6f)) / nameLineH).toInt().coerceIn(1, 4)
        val lines = wrapText(course.name, namePaint, maxWidth, maxNameLines)
        var y = top + pad + sp(9.5f)
        lines.forEach { line ->
            canvas.drawText(line, textLeft, y, namePaint)
            y += nameLineH
        }

        val remain = bottom - d(3f) - y
        if (remain >= sp(9f) && course.location.isNotBlank()) {
            val loc = TextUtils.ellipsize(course.location, metaPaint, maxWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(loc, textLeft, y + sp(2f), metaPaint)
            y += sp(10.5f)
        }
        if (bottom - d(3f) - y >= sp(8f) && course.teacher.isNotBlank()) {
            val teacher = TextUtils.ellipsize(course.teacher, metaPaint, maxWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(teacher, textLeft, y + sp(2f), metaPaint)
        }

        hits.add(Hit(RectF(left, top, right, bottom), course))
    }

    private fun wrapText(text: String, paint: TextPaint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var index = 0
        while (index < text.length && out.size < maxLines) {
            val count = paint.breakText(text, index, text.length, true, maxWidth, null)
            if (count <= 0) break
            var take = count
            if (index + take < text.length) {
                val slice = text.substring(index, index + take)
                val cut = slice.indexOfLast { it == ' ' || it == '-' || it == '（' || it == '(' || it == '·' }
                if (cut > take / 2) take = cut + 1
            }
            if (out.size == maxLines - 1 && index + take < text.length) {
                val rest = text.substring(index)
                out.add(TextUtils.ellipsize(rest, paint, maxWidth, TextUtils.TruncateAt.END).toString())
                return out
            }
            out.add(text.substring(index, index + take).trim())
            index += take
        }
        return out
    }

    private fun hitTest(x: Float, y: Float): Course? {
        for (i in hits.indices.reversed()) {
            if (hits[i].rect.contains(x, y)) return hits[i].course
        }
        return null
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (y < headerH || x < timeW) return null
        val cols = dayCount
        val colW = (width - timeW) / cols
        if (colW <= 0f) return null
        val day = ((x - timeW) / colW).toInt() + 1
        val period = ((y - headerH) / rowH).toInt() + 1
        if (day !in 1..cols || period !in 1..periods.size) return null
        return day to period
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return true
    }

    /** 把当前课表渲染成图片（用于导出 / 分享）。 */
    fun renderBitmap(background: Int = cSurface): Bitmap {
        val rows = periods.size.coerceAtLeast(1)
        val w = if (width > 0) width
        else (resources.displayMetrics.widthPixels - d(20f)).toInt().coerceAtLeast(720)
        val h = (headerH + rows * rowH).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        draw(canvas)
        return bmp
    }

    fun subtitleFor(week: Int): String = "第 $week 周"

    companion object {
        private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")
    }
}
