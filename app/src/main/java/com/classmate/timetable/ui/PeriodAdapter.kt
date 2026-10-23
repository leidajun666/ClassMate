package com.classmate.timetable.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.classmate.timetable.data.Period
import com.classmate.timetable.databinding.ItemPeriodBinding

class PeriodAdapter(
    private val periods: List<Period>,
    private val onClick: (Period) -> Unit
) : RecyclerView.Adapter<PeriodAdapter.VH>() {

    class VH(val b: ItemPeriodBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPeriodBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = periods.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = periods[position]
        holder.b.tvIndex.text = "第${p.index}节"
        holder.b.tvTime.text = "${p.start} - ${p.end}"
        holder.b.root.setOnClickListener { onClick(p) }
    }
}
