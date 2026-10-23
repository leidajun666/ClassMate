package com.classmate.timetable.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.classmate.timetable.R
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Period
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.databinding.ActivitySettingsBinding
import com.classmate.timetable.notify.ReminderScheduler
import com.classmate.timetable.util.Backup
import com.classmate.timetable.util.Exporter
import com.classmate.timetable.widget.TodayWidgetProvider
import com.google.android.material.color.DynamicColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var db: Db

    private val createImage =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val bitmap: Bitmap = Exporter.renderTimetable(this, prefs.currentWeek())
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                Backup.writeBitmap(this, uri, bytes)
                toast("课表图片已保存")
            }.onFailure { toast("导出失败：${it.message}") }
        }

    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                Backup.writeTo(this, uri, Backup.exportJson(this))
                toast("备份已导出")
            }.onFailure { toast("导出失败：${it.message}") }
        }

    private val openBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = Backup.readFrom(this, uri)
            if (text.isNullOrBlank()) {
                toast("无法读取该文件")
                return@registerForActivityResult
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("导入备份")
                .setMessage("要覆盖当前课表，还是与现有课程合并？")
                .setNeutralButton("合并") { _, _ -> doImport(text, false) }
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("覆盖") { _, _ -> doImport(text, true) }
                .show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (prefs.dynamicColor) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        b.rowSemesterStart.setOnClickListener { pickSemesterStart() }
        b.rowTotalWeeks.setOnClickListener { pickTotalWeeks() }
        b.rowPeriodCount.setOnClickListener { pickPeriodCount() }
        b.rowCurrentWeek.setOnClickListener { pickCurrentWeek() }

        b.swShowWeekend.isChecked = prefs.showWeekend
        b.swDimOther.isChecked = prefs.dimOtherWeeks
        b.swDynamicColor.isChecked = prefs.dynamicColor
        b.swReminder.isChecked = prefs.reminderEnabled

        b.swShowWeekend.setOnCheckedChangeListener { _, checked ->
            prefs.showWeekend = checked
            TodayWidgetProvider.updateAll(this)
        }
        b.swDimOther.setOnCheckedChangeListener { _, checked ->
            prefs.dimOtherWeeks = checked
        }
        b.swDynamicColor.setOnCheckedChangeListener { _, checked ->
            prefs.dynamicColor = checked
            recreate()
        }
        b.swReminder.setOnCheckedChangeListener { _, checked ->
            prefs.reminderEnabled = checked
            ReminderScheduler.reschedule(this)
            if (checked) checkExactAlarm()
        }

        b.rowTheme.setOnClickListener { pickTheme() }
        b.rowReminderAhead.setOnClickListener { pickReminderAhead() }
        b.rowExportImage.setOnClickListener { createImage.launch("课表_第${prefs.currentWeek()}周.png") }
        b.rowExportBackup.setOnClickListener {
            createBackup.launch("classmate-backup-${LocalDate.now()}.json")
        }
        b.rowImportBackup.setOnClickListener {
            openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        }
        b.rowClear.setOnClickListener { confirmClear() }

        refreshValues()
        loadPeriods()
    }

    private fun refreshValues() {
        b.tvSemesterStart.text = prefs.semesterStart.toString()
        b.tvTotalWeeks.text = "${prefs.totalWeeks} 周"
        b.tvCurrentWeek.text = if (prefs.manualWeek > 0) {
            "第 ${prefs.manualWeek} 周（手动）"
        } else {
            "第 ${prefs.currentWeek()} 周（自动）"
        }
        b.tvThemeMode.text = when (prefs.themeMode) {
            1 -> getString(R.string.settings_theme_light)
            2 -> getString(R.string.settings_theme_dark)
            else -> getString(R.string.settings_theme_system)
        }
        b.tvReminderAhead.text = "${prefs.reminderMinutes} 分钟"
    }

    private fun loadPeriods() {
        val periods = db.periods()
        b.tvPeriodCount.text = "${periods.size} 节"
        b.rvPeriods.layoutManager = LinearLayoutManager(this)
        b.rvPeriods.adapter = PeriodAdapter(periods) { period -> editPeriod(period) }
    }

    /** 有些专业一天排到 13、14 节，默认 12 节不够用。 */
    private fun pickPeriodCount() {
        val options = (8..16).toList()
        val labels = options.map { "$it 节" }.toTypedArray()
        val checked = options.indexOf(db.periods().size).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_period_count)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                db.setPeriodCount(options[which])
                loadPeriods()
                ReminderScheduler.reschedule(this)
                TodayWidgetProvider.updateAll(this)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Android 12+ 精确闹钟需要用户单独授权，未授权时提醒可能有几分钟偏差。 */
    private fun checkExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (ReminderScheduler.canScheduleExact(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("允许精确提醒")
            .setMessage("系统默认只允许近似时间唤醒。允许“闹钟和提醒”权限后，课程提醒才能准点送达。")
            .setNegativeButton("稍后", null)
            .setPositiveButton("去授权") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }.onFailure { toast("无法打开设置页") }
            }
            .show()
    }

    private fun pickSemesterStart() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择开学第一周的周一")
            .setSelection(prefs.semesterStart.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            val monday = picked.with(DayOfWeek.MONDAY)
            prefs.semesterStart = monday
            if (monday != picked) toast("已对齐到该周周一：$monday")
            refreshValues()
            ReminderScheduler.reschedule(this)
            TodayWidgetProvider.updateAll(this)
        }
        picker.show(supportFragmentManager, "semester_start")
    }

    private fun pickTotalWeeks() {
        val options = (8..30).toList()
        val labels = options.map { "$it 周" }.toTypedArray()
        val checked = options.indexOf(prefs.totalWeeks).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_total_weeks)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.totalWeeks = options[which]
                if (prefs.manualWeek > prefs.totalWeeks) prefs.manualWeek = 0
                refreshValues()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickCurrentWeek() {
        val labels = ArrayList<String>()
        labels.add(getString(R.string.settings_current_week_auto))
        for (i in 1..prefs.totalWeeks) labels.add("第 $i 周")
        val checked = if (prefs.manualWeek == 0) 0 else prefs.manualWeek
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_current_week)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                prefs.manualWeek = if (which == 0) 0 else which
                prefs.lastViewWeek = prefs.currentWeek()
                refreshValues()
                ReminderScheduler.reschedule(this)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickTheme() {
        val labels = arrayOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, prefs.themeMode) { dialog, which ->
                prefs.themeMode = which
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                refreshValues()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickReminderAhead() {
        val options = listOf(0, 5, 10, 15, 20, 30, 45, 60)
        val labels = options.map { if (it == 0) "上课时提醒" else "$it 分钟" }.toTypedArray()
        val checked = options.indexOf(prefs.reminderMinutes).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reminder_ahead)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.reminderMinutes = options[which]
                refreshValues()
                ReminderScheduler.reschedule(this)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun editPeriod(period: Period) {
        val startMinutes = Period.parseMinutes(period.start)
        val endMinutes = Period.parseMinutes(period.end)
        val startPicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(startMinutes / 60)
            .setMinute(startMinutes % 60)
            .setTitleText("第 ${period.index} 节 · 开始时间")
            .build()
        startPicker.addOnPositiveButtonClickListener {
            val endPicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(endMinutes / 60)
                .setMinute(endMinutes % 60)
                .setTitleText("第 ${period.index} 节 · 结束时间")
                .build()
            endPicker.addOnPositiveButtonClickListener {
                db.savePeriod(
                    Period(
                        period.index,
                        String.format("%02d:%02d", startPicker.hour, startPicker.minute),
                        String.format("%02d:%02d", endPicker.hour, endPicker.minute)
                    )
                )
                loadPeriods()
                ReminderScheduler.reschedule(this)
                TodayWidgetProvider.updateAll(this)
            }
            endPicker.show(supportFragmentManager, "period_end")
        }
        startPicker.show(supportFragmentManager, "period_start")
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear)
            .setMessage("将删除本机保存的全部课程，此操作不可撤销。建议先导出备份。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                db.clearCourses()
                ReminderScheduler.reschedule(this)
                TodayWidgetProvider.updateAll(this)
                toast("已清空课程")
            }
            .show()
    }

    private fun doImport(text: String, replace: Boolean) {
        runCatching { Backup.importJson(this, text, replace) }
            .onSuccess { count ->
                ReminderScheduler.reschedule(this)
                TodayWidgetProvider.updateAll(this)
                refreshValues()
                loadPeriods()
                toast("已导入 $count 门课程")
            }
            .onFailure { toast("导入失败：${it.message}") }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) refreshValues()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
