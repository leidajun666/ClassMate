package com.classmate.timetable.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.classmate.timetable.R
import com.classmate.timetable.data.Course
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.databinding.ActivityFileImportBinding
import com.classmate.timetable.databinding.DialogParsedEditBinding
import com.classmate.timetable.importer.ParsedCourse
import com.classmate.timetable.importer.ScheduleFileImporter
import com.classmate.timetable.notify.ReminderScheduler
import com.classmate.timetable.util.CourseColors
import com.classmate.timetable.widget.TodayWidgetProvider
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 教务导入（文件）。
 *
 * 直接读教务系统导出的课表文件：.ics / .csv / .xls(网页表格) / .xlsx / .json，
 * 解析成课程后预览、纠错、勾选导入。学校没提供导出入口时，可跳到 [JwWebActivity] 登录抓取。
 */
class FileImportActivity : BaseActivity() {

    private lateinit var b: ActivityFileImportBinding
    private lateinit var db: Db
    private lateinit var prefs: Prefs

    private var parsed: MutableList<ParsedCourse> = mutableListOf()
    private var adapter: ParsedCourseAdapter? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }

    private val webImport = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val payload = result.data?.getStringExtra(JwWebActivity.EXTRA_CAPTURE)
            if (!payload.isNullOrBlank()) parseCapture(payload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (prefs.dynamicColor) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivityFileImportBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.rvResults.layoutManager = LinearLayoutManager(this)

        b.btnPickFile.setOnClickListener { openFilePicker() }
        b.btnOpenWeb.setOnClickListener { webImport.launch(Intent(this, JwWebActivity::class.java)) }
        b.btnSelectAll.setOnClickListener {
            val current = adapter ?: return@setOnClickListener
            val allSelected = current.selectedCount() == current.itemCount
            current.setAll(!allSelected)
            b.btnSelectAll.text = if (allSelected) getString(R.string.ai_select_all) else getString(R.string.ai_select_none)
            updateImportButton()
        }
        b.btnImport.setOnClickListener { importSelected() }
    }

    // ------------------------------------------------------------------ 文件

    private fun openFilePicker() {
        val types = arrayOf(
            "application/json",
            "text/calendar",
            "text/csv",
            "text/comma-separated-values",
            "text/plain",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "*/*"
        )
        runCatching { filePicker.launch(types) }
            .onFailure { toast("没有可用的文件选择器") }
    }

    private fun loadFile(uri: Uri) {
        val name = displayName(uri)
        b.tvFileInfo.text = name
        b.tvFileInfo.visibility = View.VISIBLE
        startLoading()

        Thread {
            val outcome = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法打开文件")
                if (bytes.size > 20 * 1024 * 1024) {
                    throw IllegalStateException(getString(R.string.jw_file_too_big))
                }
                ScheduleFileImporter.import(
                    fileName = name,
                    bytes = bytes,
                    periods = db.periods(),
                    totalWeeks = prefs.totalWeeks,
                    termStart = prefs.semesterStart
                )
            }
            runOnUiThread {
                b.progress.visibility = View.GONE
                outcome.onSuccess { showResult(it) }.onFailure { showError(it) }
            }
        }.start()
    }

    private fun parseCapture(payload: String) {
        startLoading()
        Thread {
            val outcome = runCatching {
                ScheduleFileImporter.fromCapture(payload, db.periods(), prefs.totalWeeks)
            }
            runOnUiThread {
                b.progress.visibility = View.GONE
                outcome.onSuccess { showResult(it) }.onFailure { showError(it) }
            }
        }.start()
    }

    private fun startLoading() {
        b.progress.visibility = View.VISIBLE
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
        b.tvStatus.text = getString(R.string.jw_parsing)
        b.resultHeader.visibility = View.GONE
        b.rvResults.visibility = View.GONE
        b.btnImport.visibility = View.GONE
        parsed = mutableListOf()
        adapter = null
    }

    private fun showError(e: Throwable) {
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.md_error))
        b.tvStatus.text = getString(R.string.jw_read_failed, e.message ?: e.javaClass.simpleName)
    }

    private fun showResult(result: ScheduleFileImporter.Result) {
        if (result.courses.isEmpty()) {
            b.tvStatus.visibility = View.VISIBLE
            b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.md_error))
            b.tvStatus.text = result.message.ifBlank { getString(R.string.jw_web_no_table) }
            b.resultHeader.visibility = View.GONE
            b.rvResults.visibility = View.GONE
            b.btnImport.visibility = View.GONE
            return
        }

        parsed = result.courses.toMutableList()
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
        b.tvStatus.text = getString(R.string.jw_parse_ok, result.label.ifBlank { result.format }, parsed.size)

        val list = parsed
        b.tvResultTitle.text = getString(R.string.ai_result_title, list.size)
        b.btnSelectAll.text = getString(R.string.ai_select_none)
        b.resultHeader.visibility = View.VISIBLE
        adapter = ParsedCourseAdapter(list) { position -> editParsed(position) }
            .also { b.rvResults.adapter = it }
        b.rvResults.visibility = View.VISIBLE
        b.btnImport.visibility = View.VISIBLE
        updateImportButton()

        b.scroll.post { b.scroll.smoothScrollTo(0, (b.resultHeader.top - 24).coerceAtLeast(0)) }
    }

    // ------------------------------------------------------------------ 修正 / 导入

    private fun editParsed(position: Int) {
        val item = parsed.getOrNull(position) ?: return
        val periods = db.periods()
        val view = DialogParsedEditBinding.inflate(layoutInflater)

        view.etName.setText(item.name)

        var day = item.day
        val dayLabels = Course.DAY_NAMES.drop(1).toTypedArray()
        view.etDay.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, dayLabels))
        view.etDay.setText(Course.DAY_NAMES[item.day.coerceIn(1, 7)], false)
        view.etDay.setOnItemClickListener { _, _, which, _ -> day = which + 1 }

        var start = item.startPeriod.coerceIn(1, periods.size.coerceAtLeast(1))
        var end = item.endPeriod.coerceIn(start, periods.size.coerceAtLeast(1))
        val periodLabels = periods.map { "第 ${it.index} 节  ${it.start}" }.toTypedArray()
        view.etStart.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, periodLabels))
        view.etEnd.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, periodLabels))
        view.etStart.setText(periodLabels.getOrElse(start - 1) { "第 1 节" }, false)
        view.etEnd.setText(periodLabels.getOrElse(end - 1) { "第 1 节" }, false)
        view.etStart.setOnItemClickListener { _, _, which, _ -> start = which + 1 }
        view.etEnd.setOnItemClickListener { _, _, which, _ -> end = which + 1 }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_edit_title)
            .setView(view.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = view.etName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast(getString(R.string.error_name_required))
                    return@setPositiveButton
                }
                val updated = item.copy(
                    name = name,
                    day = day.coerceIn(1, 7),
                    startPeriod = start,
                    endPeriod = maxOf(start, end)
                )
                parsed[position] = updated
                adapter?.update(position, updated)
                updateImportButton()
            }
            .show()
    }

    private fun updateImportButton() {
        val count = adapter?.selectedCount() ?: 0
        b.btnImport.isEnabled = count > 0
        b.btnImport.text = if (count > 0) getString(R.string.ai_import_count, count) else getString(R.string.ai_import_none)
    }

    private fun importSelected() {
        val current = adapter ?: return
        val selected = current.selectedItems()
        if (selected.isEmpty()) return
        val courses = selected.map { it.toCourse(CourseColors.suggestIndex(it.name)) }
        val inserted = db.insertAll(courses)
        ReminderScheduler.reschedule(this)
        TodayWidgetProvider.updateAll(this)
        toast(getString(R.string.ai_imported, inserted))
        finish()
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment ?: "course"
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
