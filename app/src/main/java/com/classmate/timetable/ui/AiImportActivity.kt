package com.classmate.timetable.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.classmate.timetable.R
import com.classmate.timetable.aiimport.AiImportParser
import com.classmate.timetable.importer.ParsedCourse
import com.classmate.timetable.data.Course
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.databinding.ActivityAiImportBinding
import com.classmate.timetable.databinding.DialogParsedEditBinding
import com.classmate.timetable.notify.ReminderScheduler
import com.classmate.timetable.util.CourseColors
import com.classmate.timetable.widget.TodayWidgetProvider
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * AI 识图导入。
 *
 * 不在本地做图像识别，而是把「识别」这一步交给用户手上任意一个能看图的 AI：
 * 复制提示词 → 连同课表截图发给 AI → AI 返回 JSON → 粘贴回来 → 预览勾选 → 入库。
 * 这样识别准确率由大模型保证，App 只负责解析、纠错和落库，也省掉了 OCR 模型与原生库。
 */
class AiImportActivity : BaseActivity() {

    private lateinit var b: ActivityAiImportBinding
    private lateinit var db: Db
    private lateinit var prefs: Prefs

    private var parsed: MutableList<ParsedCourse> = mutableListOf()
    private var adapter: ParsedCourseAdapter? = null

    private val pickJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readJsonFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (prefs.dynamicColor) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivityAiImportBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        b.tvPrompt.text = AiImportParser.PROMPT
        b.toolbar.setNavigationOnClickListener { finish() }
        b.rvResults.layoutManager = LinearLayoutManager(this)

        b.btnCopyPrompt.setOnClickListener { copyPrompt() }
        b.btnShowPrompt.setOnClickListener { togglePrompt() }
        b.btnPaste.setOnClickListener { pasteFromClipboard() }
        b.btnPickFile.setOnClickListener {
            runCatching { pickJson.launch(arrayOf("application/json", "text/plain", "*/*")) }
                .onFailure { toast("没有可用的文件选择器") }
        }
        b.btnParse.setOnClickListener { parseNow() }

        b.btnSelectAll.setOnClickListener {
            val current = adapter ?: return@setOnClickListener
            val allSelected = current.selectedCount() == current.itemCount
            current.setAll(!allSelected)
            b.btnSelectAll.text = if (allSelected) getString(R.string.ai_select_all) else getString(R.string.ai_select_none)
            updateImportButton()
        }
        b.btnImport.setOnClickListener { importSelected() }

        prefillFromIntent()
    }

    // ------------------------------------------------------------------ 提示词

    private fun copyPrompt() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast("剪贴板不可用")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("课表识别提示词", AiImportParser.PROMPT))
        toast("提示词已复制，去 AI 里连同课表截图一起发送")
    }

    private fun togglePrompt() {
        val showing = b.tvPrompt.visibility == View.VISIBLE
        b.tvPrompt.visibility = if (showing) View.GONE else View.VISIBLE
        b.btnShowPrompt.text = getString(if (showing) R.string.ai_show_prompt else R.string.ai_hide_prompt)
        if (!showing) b.scroll.post { b.scroll.smoothScrollTo(0, b.tvPrompt.top) }
    }

    // ------------------------------------------------------------------ 输入

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板里没有文字")
            return
        }
        b.etJson.setText(text.trim())
        b.etJson.setSelection(b.etJson.text?.length ?: 0)
        parseNow()
    }

    private fun readJsonFile(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.onSuccess { text ->
            if (text.isNullOrBlank()) {
                toast("文件是空的")
            } else {
                b.etJson.setText(text.trimStart('\uFEFF').trim())
                parseNow()
            }
        }.onFailure { toast("读取文件失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 从别的 App「分享到课表」进来时，直接带上内容并尝试解析。 */
    private fun prefillFromIntent() {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT)
        if (text.isNullOrBlank()) return
        b.etJson.setText(text.trim())
        parseNow()
    }

    // ------------------------------------------------------------------ 解析

    private fun parseNow() {
        val text = b.etJson.text?.toString().orEmpty()
        if (text.isBlank()) {
            b.tilJson.error = getString(R.string.ai_empty_input)
            return
        }
        b.tilJson.error = null
        hideKeyboard()

        val periodCount = db.periods().size.coerceAtLeast(1)
        val result = runCatching { AiImportParser.parse(text, periodCount, prefs.totalWeeks) }
            .getOrElse {
                AiImportParser.Result(message = "解析出错：${it.message ?: it.javaClass.simpleName}")
            }

        parsed = result.courses.toMutableList()
        adapter = null

        b.tvParseStatus.visibility = View.VISIBLE
        if (parsed.isEmpty()) {
            b.tvParseStatus.text = result.message.ifBlank { getString(R.string.ai_no_result) }
            b.tvParseStatus.setTextColor(ContextCompat.getColor(this, R.color.md_error))
            b.resultHeader.visibility = View.GONE
            b.rvResults.visibility = View.GONE
            b.btnImport.visibility = View.GONE
            return
        }

        b.tvParseStatus.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
        b.tvParseStatus.text = if (result.fromJson) {
            getString(R.string.ai_parsed_ok, parsed.size)
        } else {
            result.message.ifBlank { getString(R.string.ai_parsed_loose, parsed.size) }
        }

        val list = parsed
        b.tvResultTitle.text = getString(R.string.ai_result_title, list.size)
        b.btnSelectAll.text = getString(R.string.ai_select_none)
        b.resultHeader.visibility = View.VISIBLE
        adapter = ParsedCourseAdapter(list) { position -> editParsed(position) }
            .also { b.rvResults.adapter = it }
        b.rvResults.visibility = View.VISIBLE
        b.btnImport.visibility = View.VISIBLE
        updateImportButton()

        b.scroll.post {
            b.scroll.smoothScrollTo(0, (b.resultHeader.top - 24).coerceAtLeast(0))
        }
    }

    /** 允许用户修正 AI 的结果：课程名、星期、起止节次。 */
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

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.etJson.windowToken, 0)
        b.etJson.clearFocus()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
