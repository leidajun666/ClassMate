package com.classmate.timetable.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.classmate.timetable.data.Course
import com.classmate.timetable.databinding.ItemParsedCourseBinding
import com.classmate.timetable.importer.ParsedCourse
import com.classmate.timetable.util.CourseColors

class ParsedCourseAdapter(
    private val items: MutableList<ParsedCourse>,
    private val onEdit: (Int) -> Unit
) : RecyclerView.Adapter<ParsedCourseAdapter.VH>() {

    val checked = BooleanArray(items.size) { true }

    class VH(val b: ItemParsedCourseBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemParsedCourseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.tvName.text = item.name
        val meta = buildString {
            append(Course.DAY_NAMES[item.day])
            append(" ").append(if (item.startPeriod == item.endPeriod) "第${item.startPeriod}节" else "第${item.startPeriod}-${item.endPeriod}节")
            append(" · ").append(
                if (item.startWeek == item.endWeek) "第${item.startWeek}周" else "${item.startWeek}-${item.endWeek}周"
            )
            when (item.weekType) {
                1 -> append("（单）")
                2 -> append("（双）")
            }
            if (item.location.isNotBlank()) append(" · ").append(item.location)
            if (item.teacher.isNotBlank()) append(" · ").append(item.teacher)
        }
        holder.b.tvMeta.text = meta
        holder.b.cbSelect.isChecked = checked.getOrElse(position) { false }

        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CourseColors.color(CourseColors.suggestIndex(item.name)))
        }
        holder.b.colorDot.background = dot

        holder.b.root.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnClickListener
            checked[index] = !checked.getOrElse(index) { false }
            notifyItemChanged(index)
        }
        holder.b.btnEdit.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnClickListener
            onEdit(index)
        }
    }

    fun update(position: Int, value: ParsedCourse) {
        if (position !in items.indices) return
        items[position] = value
        checked[position] = true
        notifyItemChanged(position)
    }

    fun selectedItems(): List<ParsedCourse> =
        items.filterIndexed { index, _ -> checked.getOrElse(index) { false } }

    fun selectedCount(): Int = checked.count { it }

    fun setAll(value: Boolean) {
        for (i in checked.indices) checked[i] = value
        notifyDataSetChanged()
    }
}
