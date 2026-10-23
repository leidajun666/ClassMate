package com.classmate.timetable.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.classmate.timetable.R
import com.classmate.timetable.data.Course
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Period
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.databinding.ActivityMainBinding
import com.classmate.timetable.databinding.SheetCourseDetailBinding
import com.classmate.timetable.notify.ReminderScheduler
import com.classmate.timetable.util.Backup
import com.classmate.timetable.util.CourseColors
import com.classmate.timetable.widget.TodayWidgetProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var db: Db

    private var viewWeek = 1
    private var courses: List<Course> = emptyList()
    private var periods: List<Period> = emptyList()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val bitmap = b.timetable.renderBitmap()
                val bytes = ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                Backup.writeBitmap(this, uri, bytes)
                toast("课表图片已保存")
            }.onFailure { toast("保存失败：${it.message}") }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ReminderScheduler.reschedule(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (prefs.dynamicColor) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        setSupportActionBar(b.toolbar)
        supportActionBar?.title = null
        b.toolbar.title = getString(R.string.title_main)

        viewWeek = (prefs.lastViewWeek.takeIf { it > 0 } ?: prefs.currentWeek())
            .coerceIn(1, prefs.totalWeeks)

        b.btnPrevWeek.setOnClickListener { changeWeek(-1) }
        b.btnNextWeek.setOnClickListener { changeWeek(1) }
        b.btnBackToday.setOnClickListener {
            viewWeek = prefs.currentWeek()
            reload()
        }
        b.fab.setOnClickListener { openEditor(null) }

        b.timetable.onCourseClick = { showCourseSheet(it) }
        b.timetable.onEmptyLongClick = { day, period ->
            openEditor(null, day = day, period = period)
        }

        ensureNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        if (::db.isInitialized) {
            reload()
            TodayWidgetProvider.updateAll(this)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::prefs.isInitialized) prefs.lastViewWeek = viewWeek
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.askedNotification) {
            prefs.askedNotification = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun changeWeek(delta: Int) {
        val target = (viewWeek + delta).coerceIn(1, prefs.totalWeeks)
        if (target == viewWeek) return
        viewWeek = target
        reload()
    }

    private fun reload() {
        courses = db.courses()
        periods = db.periods()
        val dates = (1..7).map { prefs.dateOf(viewWeek, it) }
        val realWeek = prefs.weekOf(LocalDate.now())
        val todayDay = if (realWeek == viewWeek) LocalDate.now().dayOfWeek.value else -1

        b.timetable.setData(
            courses = courses,
            periods = periods,
            week = viewWeek,
            dates = dates,
            todayDay = todayDay,
            showWeekend = prefs.showWeekend,
            dimOthers = prefs.dimOtherWeeks
        )

        val first = dates.first()
        val last = dates.last()
        b.tvWeekLabel.text = buildString {
            append("第 ").append(viewWeek).append(" 周")
            if (viewWeek == prefs.currentWeek()) append(" · 本周")
        }
        b.tvWeekRange.text = buildString {
            append(first.format(MD)).append(" - ").append(last.format(MD))
            append(" · 共 ").append(prefs.totalWeeks).append(" 周")
        }
        b.btnBackToday.visibility = if (viewWeek != prefs.currentWeek()) View.VISIBLE else View.GONE

        updateNextCard()
    }

    private fun updateNextCard() {
        val now = LocalDate.now()
        val nowMinutes = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        var shown = false
        for (offset in 0..7L) {
            val date = now.plusDays(offset)
            val week = prefs.weekOf(date)
            if (week < 1 || week > prefs.totalWeeks) continue
            val day = date.dayOfWeek.value
            val dayCourses = courses
                .filter { it.dayOfWeek == day && it.activeInWeek(week) }
                .sortedBy { it.startPeriod }
            val candidate = dayCourses.firstOrNull { c ->
                val p = periods.firstOrNull { it.index == c.startPeriod }
                offset > 0 || (p != null && p.startMinutes() + 45 > nowMinutes)
            } ?: continue
            val p = periods.firstOrNull { it.index == candidate.startPeriod }
            val startText = p?.start ?: ""
            val endText = p?.end ?: ""
            val prefix = when (offset) {
                0L -> "下一节"
                1L -> "明天"
                else -> Course.DAY_NAMES[day]
            }
            val countdown = if (offset == 0L && p != null) {
                val diff = p.startMinutes() - nowMinutes
                when {
                    diff > 60 -> "还有 ${diff / 60} 小时 ${diff % 60} 分"
                    diff > 0 -> "还有 $diff 分钟"
                    else -> "正在进行"
                }
            } else {
                ""
            }
            b.tvNextLabel.text = listOf(prefix, "$startText-$endText", countdown)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            b.tvNextTitle.text = candidate.name
            b.tvNextSub.text = listOf(candidate.location, candidate.teacher)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "第${candidate.startPeriod}-${candidate.endPeriod}节" }
            shown = true
            break
        }
        if (!shown) {
            b.tvNextLabel.text = "接下来"
            b.tvNextTitle.text = "本周暂无课程安排"
            b.tvNextSub.text = "点击右下角按钮，或长按课表格子添加课程"
        }
    }

    private fun openEditor(courseId: Long?, day: Int? = null, period: Int? = null) {
        val intent = Intent(this, CourseEditActivity::class.java)
        if (courseId != null) intent.putExtra(CourseEditActivity.EXTRA_ID, courseId)
        if (day != null) intent.putExtra(CourseEditActivity.EXTRA_DAY, day)
        if (period != null) intent.putExtra(CourseEditActivity.EXTRA_PERIOD, period)
        startActivity(intent)
    }

    private fun showCourseSheet(course: Course) {
        val sheetBinding = SheetCourseDetailBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        val color = CourseColors.color(course.colorIndex)
        sheetBinding.colorBar.background = GradientDrawable().apply {
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(color)
        }
        sheetBinding.tvName.text = course.name

        val period = periods.firstOrNull { it.index == course.startPeriod }
        val endPeriod = periods.firstOrNull { it.index == course.endPeriod }
        val timeText = buildString {
            append(Course.DAY_NAMES[course.dayOfWeek])
            append(" ").append(course.periodText())
            if (period != null) {
                append(" · ").append(period.start)
                if (endPeriod != null) append("-").append(endPeriod.end)
            }
            append(" · ").append(course.weeksText())
        }
        sheetBinding.tvTime.text = timeText

        sheetBinding.rowLocation.visibility = if (course.location.isBlank()) View.GONE else View.VISIBLE
        sheetBinding.tvLocation.text = course.location
        sheetBinding.rowTeacher.visibility = if (course.teacher.isBlank()) View.GONE else View.VISIBLE
        sheetBinding.tvTeacher.text = course.teacher
        sheetBinding.rowNote.visibility = if (course.note.isBlank()) View.GONE else View.VISIBLE
        sheetBinding.tvNote.text = course.note

        sheetBinding.btnEdit.setOnClickListener {
            dialog.dismiss()
            openEditor(course.id)
        }
        sheetBinding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除课程")
                .setMessage("确定删除《${course.name}》吗？")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    db.delete(course.id)
                    dialog.dismiss()
                    reload()
                    ReminderScheduler.reschedule(this)
                    TodayWidgetProvider.updateAll(this)
                    toast(getString(R.string.deleted))
                }
                .show()
        }
        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_ai -> {
                startActivity(Intent(this, AiImportActivity::class.java))
                return true
            }
            R.id.action_jw -> {
                startActivity(Intent(this, FileImportActivity::class.java))
                return true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_export -> {
                exportLauncher.launch("课表_第${viewWeek}周.png")
                return true
            }
            R.id.action_about -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_full_name)
                    .setMessage("一个把数据全部留在本机的课程表：\n\n· AI 识图导入（复制提示词给 AI，粘贴 JSON 导入）\n· 教务文件导入（ics/csv/xls/xlsx）与网页登录抓取\n· 单双周 / 多节连上 / 颜色标记\n· 课前提醒与桌面小组件\n· JSON 备份与课表图片导出\n\n无需联网、无需账号，数据只保存在本机；只有「网页登录抓取」需要联网。")
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val MD: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")
    }
}
