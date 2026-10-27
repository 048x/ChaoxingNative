package com.chaoxing.nativeapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chaoxing.nativeapp.databinding.ItemCourseBinding

class CourseAdapter(
    private val items: List<Course>,
    private val selected: MutableSet<String>,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<CourseAdapter.VH>() {

    class VH(val binding: ItemCourseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCourseBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val course = items[position]
        holder.binding.tvCourseName.text = course.title

        holder.binding.cbCourse.setOnCheckedChangeListener(null)
        holder.binding.cbCourse.isChecked = selected.contains(course.courseId)
        holder.binding.cbCourse.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(course.courseId) else selected.remove(course.courseId)
            onSelectionChanged()
        }

        holder.binding.root.setOnClickListener {
            holder.binding.cbCourse.isChecked = !holder.binding.cbCourse.isChecked
        }
    }

    override fun getItemCount(): Int = items.size

    fun selectedCourses(): List<Course> = items.filter { selected.contains(it.courseId) }

    fun selectAll(checked: Boolean) {
        if (checked) {
            items.forEach { selected.add(it.courseId) }
        } else {
            selected.clear()
        }
        notifyDataSetChanged()
    }
}
