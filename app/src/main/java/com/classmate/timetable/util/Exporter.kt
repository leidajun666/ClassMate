package com.classmate.timetable.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.ui.TimetableView

/** 在无界面上渲染课表位图（用于导出图片，不依赖首页）。 */
object Exporter {

    fun renderTimetable(context: Context, week: Int): Bitmap {
        val prefs = Prefs(context)
        val db = Db.get(context)
        val density = context.resources.displayMetrics.density
        val width = (context.resources.displayMetrics.widthPixels - 32 * density).toInt().coerceAtLeast(720)

        val view = TimetableView(context)
        view.setData(
            courses = db.courses(),
            periods = db.periods(),
            week = week,
            dates = (1..7).map { prefs.dateOf(week, it) },
            todayDay = -1,
            showWeekend = prefs.showWeekend,
            dimOthers = prefs.dimOtherWeeks
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view.renderBitmap(resolveColor(context, android.R.attr.colorBackground, Color.WHITE))
    }

    private fun resolveColor(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return tv.data
        if (tv.resourceId != 0) {
            val csl = runCatching { ContextCompat.getColorStateList(context, tv.resourceId) }.getOrNull()
            if (csl != null) return csl.defaultColor
        }
        return fallback
    }
}
