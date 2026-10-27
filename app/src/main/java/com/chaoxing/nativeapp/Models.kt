package com.chaoxing.nativeapp

/**
 * 与原 Python 版字典结构一一对应。
 */
data class Course(
    val id: String,
    val info: String,
    val roleid: String,
    val clazzId: String,
    val courseId: String,
    val cpi: String,
    val title: String,
    val desc: String,
    val teacher: String,
)

data class ChapterPoint(
    val id: String,
    val title: String,
    val jobCount: String,
    val hasFinished: Boolean,
    val needUnlock: Boolean,
)

data class CoursePoint(
    val hasLocked: Boolean,
    val points: List<ChapterPoint>,
)

data class Job(
    val type: String,
    val jobid: String,
    val name: String,
    val otherinfo: String,
    val mid: String,
    val objectid: String,
    val aid: String,
    val playTime: Long,
    val rt: String,
    val attDuration: String,
    val attDurationEnc: String,
    val videoFaceCaptureEnc: String,
)

data class JobInfo(
    val ktoken: String = "",
    val mtEnc: String = "",
    val reportTimeInterval: Int = 60,
    val defenc: String = "",
    val cardid: String = "",
    val cpi: String = "",
    val qnenc: String = "",
    val knowledgeid: String = "",
    val notOpen: Boolean = false,
)

data class LoginResult(val success: Boolean, val message: String)

data class StudyConfig(
    val speed: Double,
    val jobs: Int,
    val notOpenAction: String, // retry / continue
)

enum class StudyResult {
    SUCCESS, FORBIDDEN, ERROR, TIMEOUT;

    val isFailure: Boolean get() = this != SUCCESS
}

object ChapterResult {
    const val SUCCESS = 0
    const val ERROR = 1
    const val NOT_OPEN = 2
}
