package com.classmate.timetable.util

import android.graphics.Color

/** 课程色板：饱和度高、在明暗主题下都清晰；文字颜色按亮度自动取黑/白。 */
object CourseColors {

    val palette = intArrayOf(
        0xFF4C7DF0.toInt(), // 蓝
        0xFFE8873A.toInt(), // 橙
        0xFF3FA26A.toInt(), // 绿
        0xFFE05B5B.toInt(), // 红
        0xFF9B59D0.toInt(), // 紫
        0xFF2BA3CF.toInt(), // 青
        0xFFD9A32B.toInt(), // 金
        0xFF5C6BC0.toInt(), // 靛
        0xFF4CAF93.toInt(), // 蓝绿
        0xFFC2557E.toInt(), // 玫红
        0xFF7C8C3F.toInt(), // 橄榄
        0xFF8D6E63.toInt()  // 棕
    )

    fun size(): Int = palette.size

    fun color(index: Int): Int = palette[((index % palette.size) + palette.size) % palette.size]

    fun onColor(color: Int): Int {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        // 相对亮度（sRGB 近似）
        val lum = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        return if (lum > 0.55) 0xFF1B1B1F.toInt() else Color.WHITE
    }

    private fun channel(v: Double): Double =
        if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

    /** 根据课程名散列出一个稳定的颜色序号 */
    fun suggestIndex(name: String): Int {
        if (name.isEmpty()) return 0
        var h = 7
        name.forEach { h = h * 31 + it.code }
        return (h and 0x7FFFFFFF) % palette.size
    }
}
