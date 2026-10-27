package com.chaoxing.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkManager 任务参数持久化：进程被杀后 Worker 重建仍可继续。
 */
object StudyRepo {
    private const val PREFS = "cx_study"
    private const val KEY = "config"

    fun save(ctx: Context, speed: Double, jobs: Int, notOpenAction: String, courses: List<Course>) {
        val root = JSONObject()
        root.put("speed", speed)
        root.put("jobs", jobs)
        root.put("notOpenAction", notOpenAction)

        val arr = JSONArray()
        courses.forEach { c ->
            arr.put(
                JSONObject()
                    .put("courseId", c.courseId)
                    .put("clazzId", c.clazzId)
                    .put("cpi", c.cpi)
                    .put("title", c.title)
            )
        }
        root.put("courses", arr)

        ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, root.toString())
            .apply()
    }

    fun load(ctx: Context): StudyTask? {
        val raw = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return null

        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val arr = root.optJSONArray("courses") ?: JSONArray()
        if (arr.length() == 0) return null

        val courses = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val courseId = o.optString("courseId", "")
            if (courseId.isEmpty()) return@mapNotNull null
            Course(
                id = "",
                info = "",
                roleid = "",
                clazzId = o.optString("clazzId", ""),
                courseId = courseId,
                cpi = o.optString("cpi", ""),
                title = o.optString("title", ""),
                desc = "",
                teacher = "",
            )
        }
        if (courses.isEmpty()) return null

        return StudyTask(
            speed = root.optDouble("speed", 1.0),
            jobs = root.optInt("jobs", 4).coerceAtLeast(1),
            notOpenAction = root.optString("notOpenAction", "retry"),
            courses = courses,
        )
    }
}

data class StudyTask(
    val speed: Double,
    val jobs: Int,
    val notOpenAction: String,
    val courses: List<Course>,
)
