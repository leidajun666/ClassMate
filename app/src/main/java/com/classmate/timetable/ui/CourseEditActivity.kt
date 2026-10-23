package com.classmate.timetable.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.classmate.timetable.R
import com.classmate.timetable.data.Course
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.databinding.ActivityCourseEditBinding
import com.classmate.timetable.notify.ReminderScheduler
import com.classmate.timetable.util.CourseColors
import com.classmate.timetable.widget.TodayWidgetProvider
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CourseEditActivity : BaseActivity() {

    private lateinit var b: ActivityCourseEditBinding
    private lateinit var db: Db
    private lateinit var prefs: Prefs

    private var courseId = 0L
    private var selectedDay = 1
    private var selectedPeriodStart = 1
    private var selectedPeriodEnd = 2
    private var selectedWeekStart = 1
    private var selectedWeekEnd = 20
    private var weekType = 0
    private var colorIndex = 0
    private var periodCount = 12

    private val swatches = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (prefs.dynamicColor) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivityCourseEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.menu_edit)
        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                save()
                true
            } else {
                false
            }
        }

        periodCount = db.periods().size.coerceAtLeast(1)
        selectedWeekEnd = prefs.totalWeeks

        setupDropdowns()
        setupChips()
        setupColors()
        loadExtras()
    }

    private fun setupDropdowns() {
        val periods = db.periods()
        val periodLabels = (1..periodCount).map { idx ->
            val p = periods.firstOrNull { it.index == idx }
            if (p != null) "第 $idx 节  ${p.start}" else "第 $idx 节"
        }
        val weekLabels = (1..prefs.totalWeeks).map { "第 $it 周" }

        fun bind(view: android.widget.AutoCompleteTextView, labels: List<String>, onPick: (Int) -> Unit) {
            view.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
            view.setOnItemClickListener { _, _, position, _ -> onPick(position) }
        }

        bind(b.etPeriodStart, periodLabels) { pos ->
            selectedPeriodStart = pos + 1
            if (selectedPeriodEnd < selectedPeriodStart) {
                selectedPeriodEnd = selectedPeriodStart
                b.etPeriodEnd.setText(periodLabels[pos], false)
            }
        }
        bind(b.etPeriodEnd, periodLabels) { pos ->
            selectedPeriodEnd = pos + 1
            if (selectedPeriodEnd < selectedPeriodStart) {
                selectedPeriodStart = selectedPeriodEnd
                b.etPeriodStart.setText(periodLabels[pos], false)
            }
        }
        bind(b.etWeekStart, weekLabels) { pos ->
            selectedWeekStart = pos + 1
            if (selectedWeekEnd < selectedWeekStart) {
                selectedWeekEnd = selectedWeekStart
                b.etWeekEnd.setText(weekLabels[pos], false)
            }
        }
        bind(b.etWeekEnd, weekLabels) { pos ->
            selectedWeekEnd = pos + 1
            if (selectedWeekEnd < selectedWeekStart) {
                selectedWeekStart = selectedWeekEnd
                b.etWeekStart.setText(weekLabels[pos], false)
            }
        }

        b.etPeriodStart.setText(periodLabels[selectedPeriodStart - 1], false)
        b.etPeriodEnd.setText(periodLabels[selectedPeriodEnd - 1], false)
        b.etWeekStart.setText(weekLabels[selectedWeekStart - 1], false)
        b.etWeekEnd.setText(weekLabels[selectedWeekEnd - 1], false)
    }

    private fun refreshDropdownTexts() {
        val periods = db.periods()
        fun label(idx: Int): String {
            val p = periods.firstOrNull { it.index == idx }
            return if (p != null) "第 $idx 节  ${p.start}" else "第 $idx 节"
        }
        b.etPeriodStart.setText(label(selectedPeriodStart), false)
        b.etPeriodEnd.setText(label(selectedPeriodEnd), false)
        b.etWeekStart.setText("第 $selectedWeekStart 周", false)
        b.etWeekEnd.setText("第 $selectedWeekEnd 周", false)
    }

    private fun setupChips() {
        b.groupDay.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val tag = group.findViewById<Chip>(id).tag?.toString() ?: return@setOnCheckedStateChangeListener
            selectedDay = tag.toIntOrNull() ?: selectedDay
        }
        b.groupWeekType.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val tag = group.findViewById<Chip>(id).tag?.toString() ?: return@setOnCheckedStateChangeListener
            weekType = tag.toIntOrNull() ?: weekType
        }
        checkDayChip(selectedDay)
        checkWeekTypeChip(weekType)
    }

    private fun checkDayChip(day: Int) {
        val id = when (day) {
            1 -> R.id.chipDay1
            2 -> R.id.chipDay2
            3 -> R.id.chipDay3
            4 -> R.id.chipDay4
            5 -> R.id.chipDay5
            6 -> R.id.chipDay6
            else -> R.id.chipDay7
        }
        b.groupDay.check(id)
    }

    private fun checkWeekTypeChip(type: Int) {
        b.groupWeekType.check(
            when (type) {
                1 -> R.id.chipWeekOdd
                2 -> R.id.chipWeekEven
                else -> R.id.chipWeekAll
            }
        )
    }

    private fun setupColors() {
        val size = (38 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        CourseColors.palette.forEachIndexed { index, _ ->
            val view = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            view.layoutParams = lp
            view.setOnClickListener {
                colorIndex = index
                renderSwatches()
            }
            b.colorRow.addView(view)
            swatches.add(view)
        }
        renderSwatches()
    }

    private fun renderSwatches() {
        val ring = ContextCompat.getColor(this, R.color.icon_primary)
        swatches.forEachIndexed { index, view ->
            val color = CourseColors.color(index)
            val selected = index == colorIndex
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke((3 * resources.displayMetrics.density).toInt(), ring)
            }
            view.scaleX = if (selected) 1.06f else 0.9f
            view.scaleY = if (selected) 1.06f else 0.9f
            view.alpha = if (selected) 1f else 0.85f
        }
    }

    private fun loadExtras() {
        courseId = intent.getLongExtra(EXTRA_ID, 0L)
        if (courseId > 0) {
            b.toolbar.title = getString(R.string.title_edit_existing)
            val course = db.courseById(courseId)
            if (course != null) {
                b.etName.setText(course.name)
                b.etTeacher.setText(course.teacher)
                b.etLocation.setText(course.location)
                b.etNote.setText(course.note)
                selectedDay = course.dayOfWeek
                selectedPeriodStart = course.startPeriod
                selectedPeriodEnd = course.endPeriod
                selectedWeekStart = course.startWeek
                selectedWeekEnd = course.endWeek
                weekType = course.weekType
                colorIndex = course.colorIndex
                checkDayChip(selectedDay)
                checkWeekTypeChip(weekType)
                refreshDropdownTexts()
                renderSwatches()
                b.btnDelete.visibility = View.VISIBLE
                b.btnDelete.setOnClickListener { confirmDelete() }
            }
        } else {
            b.toolbar.title = getString(R.string.title_edit_new)
            val day = intent.getIntExtra(EXTRA_DAY, LocalDay.today())
            val period = intent.getIntExtra(EXTRA_PERIOD, 1)
            selectedDay = day.coerceIn(1, 7)
            selectedPeriodStart = period.coerceIn(1, periodCount)
            selectedPeriodEnd = (selectedPeriodStart + 1).coerceAtMost(periodCount)
            checkDayChip(selectedDay)
            refreshDropdownTexts()
            b.etName.requestFocus()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除课程")
            .setMessage("删除后无法恢复，确定继续吗？")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                db.delete(courseId)
                afterChange()
                finish()
            }
            .show()
    }

    private fun save() {
        val name = b.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            b.tilName.error = getString(R.string.error_name_required)
            b.etName.requestFocus()
            return
        }
        b.tilName.error = null

        if (selectedPeriodEnd < selectedPeriodStart) {
            toast(getString(R.string.error_period_range))
            return
        }
        if (selectedWeekEnd < selectedWeekStart) {
            toast(getString(R.string.error_week_range))
            return
        }

        val course = Course(
            id = courseId,
            name = name,
            teacher = b.etTeacher.text?.toString()?.trim().orEmpty(),
            location = b.etLocation.text?.toString()?.trim().orEmpty(),
            dayOfWeek = selectedDay,
            startPeriod = selectedPeriodStart,
            endPeriod = selectedPeriodEnd,
            startWeek = selectedWeekStart,
            endWeek = selectedWeekEnd,
            weekType = weekType,
            colorIndex = colorIndex,
            note = b.etNote.text?.toString()?.trim().orEmpty()
        )

        val conflict = db.courses().firstOrNull { other ->
            other.id != courseId &&
                other.overlapsPeriod(course) &&
                other.startWeek <= course.endWeek && course.startWeek <= other.endWeek
        }

        if (conflict != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("时间冲突")
                .setMessage("与《${conflict.name}》（${Course.DAY_NAMES[conflict.dayOfWeek]} ${conflict.periodText()}）时间重叠，仍要保存吗？")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save) { _, _ -> persist(course) }
                .show()
        } else {
            persist(course)
        }
    }

    private fun persist(course: Course) {
        db.save(course)
        afterChange()
        toast(getString(R.string.saved))
        finish()
    }

    private fun afterChange() {
        ReminderScheduler.reschedule(this)
        TodayWidgetProvider.updateAll(this)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private object LocalDay {
        fun today(): Int = java.time.LocalDate.now().dayOfWeek.value
    }

    companion object {
        const val EXTRA_ID = "extra_course_id"
        const val EXTRA_DAY = "extra_day"
        const val EXTRA_PERIOD = "extra_period"
    }
}
