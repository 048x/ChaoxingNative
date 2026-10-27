package com.chaoxing.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

private data class HttpResult(val code: Int, val text: String)

/**
 * 对应原 api/base.py 中的 Chaoxing 类：登录、课程/章节/任务点、视频进度上报。
 * 所有网络调用均为挂起函数，内部切换到 IO 线程，不阻塞主线程。
 */
class ChaoxingApi {

    /** 对应 self.rate_limiter = RateLimiter(0.5) */
    val rateLimiter = RateLimiter(500)

    /** 对应 self.video_log_limiter = RateLimiter(2) */
    private val videoLogLimiter = RateLimiter(2000)

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------
    suspend fun login(username: String, password: String): LoginResult {
        Net.clearSession()

        val form = linkedMapOf(
            "fid" to "-1",
            "uname" to Aes.encrypt(username),
            "password" to Aes.encrypt(password),
            "refer" to "https%3A%2F%2Fi.chaoxing.com",
            "t" to "True",
            "forbidotherlogin" to "0",
            "validate" to "",
            "doubleFactorLogin" to "0",
            "independentId" to "0",
        )

        Logx.trace("正在尝试登录...")
        val result = try {
            httpPost("https://passport2.chaoxing.com/fanyalogin", form)
        } catch (e: Exception) {
            Logx.error("登录请求失败: ${e.message}")
            return LoginResult(false, "网络错误: ${e.message}")
        }

        val json = runCatching { JSONObject(result.text) }.getOrNull()
        if (json == null) {
            Logx.error("登录响应解析失败: ${result.text.take(200)}")
            return LoginResult(false, "登录响应解析失败")
        }

        return if (json.optBoolean("status", false)) {
            Logx.info("登录成功...")
            LoginResult(true, "登录成功")
        } else {
            LoginResult(false, json.optString("msg2", "未知错误"))
        }
    }

    // ------------------------------------------------------------------
    // 课程 / 章节
    // ------------------------------------------------------------------
    suspend fun getCourseList(): List<Course> {
        val url = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata"
        val referer = mapOf(
            "Referer" to
                "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction" +
                "?moocDomain=https://mooc1-1.chaoxing.com/mooc-ans"
        )
        val form = linkedMapOf(
            "courseType" to "1",
            "courseFolderId" to "0",
            "query" to "",
            "superstarClass" to "0",
        )

        Logx.trace("正在读取所有的课程列表...")
        val first = httpPost(url, form, referer)
        Logx.info("课程列表读取完毕...")

        val courseList = withContext(Dispatchers.IO) {
            Parser.parseCourseList(first.text).toMutableList()
        }

        val interaction = httpGet("https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction")
        val folders = withContext(Dispatchers.IO) { Parser.parseCourseFolder(interaction.text) }
        for ((folderId, _) in folders) {
            val folderForm = linkedMapOf(
                "courseType" to "1",
                "courseFolderId" to folderId,
                "query" to "",
                "superstarClass" to "0",
            )
            val resp = runCatching { httpPost(url, folderForm) }.getOrNull() ?: continue
            courseList += withContext(Dispatchers.IO) { Parser.parseCourseList(resp.text) }
        }
        return courseList
    }

    suspend fun getCoursePoint(courseId: String, clazzId: String, cpi: String): CoursePoint {
        val params = mapOf(
            "courseid" to courseId,
            "clazzid" to clazzId,
            "cpi" to cpi,
            "ut" to "s",
        )
        Logx.trace("开始读取课程所有章节...")
        val resp = httpGet("https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse", params)
        Logx.info("课程章节读取成功...")
        return withContext(Dispatchers.IO) { Parser.parseCoursePoint(resp.text) }
    }

    suspend fun getJobList(course: Course, point: ChapterPoint): Pair<List<Job>, JobInfo> {
        rateLimiter.limit()

        val jobs = mutableListOf<Job>()
        var info = JobInfo()
        val baseParams = linkedMapOf(
            "clazzid" to course.clazzId,
            "courseid" to course.courseId,
            "knowledgeid" to point.id,
            "ut" to "s",
            "cpi" to course.cpi,
            "v" to "2025-0424-1038-3",
            "mooc2" to "1",
        )

        for (num in "0123456") {
            Logx.trace("开始读取章节所有任务点...")
            val params = LinkedHashMap(baseParams)
            params["num"] = num.toString()
            val resp = httpGet("https://mooc1.chaoxing.com/mooc-ans/knowledge/cards", params)
            if (resp.code != 200) {
                Logx.error("未知错误: ${resp.code} 正在跳过")
                Logx.error(resp.text.take(500))
                return emptyList<Job>() to JobInfo()
            }

            val card = withContext(Dispatchers.IO) { Parser.parseCourseCard(resp.text) }
            if (card.notOpen) {
                Logx.info("该章节未开放")
                return emptyList<Job>() to card.info
            }
            jobs += card.jobs
            info = mergeInfo(info, card.info)
        }

        if (jobs.isEmpty()) {
            studyEmptyPage(course, point)
        }
        Logx.info("章节任务点读取成功...")
        return jobs to info
    }

    private fun mergeInfo(a: JobInfo, b: JobInfo) = JobInfo(
        ktoken = b.ktoken.ifEmpty { a.ktoken },
        mtEnc = b.mtEnc.ifEmpty { a.mtEnc },
        reportTimeInterval = if (b.reportTimeInterval != 60) b.reportTimeInterval else a.reportTimeInterval,
        defenc = b.defenc.ifEmpty { a.defenc },
        cardid = b.cardid.ifEmpty { a.cardid },
        cpi = b.cpi.ifEmpty { a.cpi },
        qnenc = b.qnenc.ifEmpty { a.qnenc },
        knowledgeid = b.knowledgeid.ifEmpty { a.knowledgeid },
        notOpen = a.notOpen || b.notOpen,
    )

    // ------------------------------------------------------------------
    // 视频进度
    // ------------------------------------------------------------------
    fun getEnc(
        clazzId: String,
        jobid: String,
        objectId: String,
        playingTime: Long,
        duration: Long,
        userid: String,
    ): String {
        val raw = "[$clazzId][$userid][$jobid][$objectId][${playingTime * 1000}][d_yHJ!\$pdA~5]" +
            "[${duration * 1000}][0_$duration]"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getUid(): String =
        Net.cookie("_uid") ?: Net.cookie("UID") ?: throw IllegalStateException("Cannot get uid !")

    /**
     * 对应 video_progress_log：上报一次播放进度。
     * @return Pair(isPassed, statusCode)
     */
    suspend fun videoProgressLog(
        course: Course,
        job: Job,
        dtoken: String,
        duration: Int,
        playingTime: Double,
        type: String,
        headers: Map<String, String>,
    ): Pair<Boolean, Int> {
        videoLogLimiter.limit(random = true, randomMaxMs = 2000)

        if (job.otherinfo.contains("courseId")) {
            Logx.error(job.otherinfo)
            throw IllegalStateException("this is not possible")
        }

        val uid = getUid()
        val seconds = playingTime.toLong()
        val enc = getEnc(course.clazzId, job.jobid, job.objectid, seconds, duration.toLong(), uid)
        val params = linkedMapOf(
            "clazzId" to course.clazzId,
            "playingTime" to seconds.toString(),
            "duration" to duration.toString(),
            "clipTime" to "0_$duration",
            "objectId" to job.objectid,
            "otherInfo" to job.otherinfo,
            "courseId" to course.courseId,
            "jobid" to job.jobid,
            "userid" to uid,
            "isdrag" to "3",
            "view" to "pc",
            "enc" to enc,
            "dtype" to type,
        )
        if (job.videoFaceCaptureEnc.isNotEmpty()) params["videoFaceCaptureEnc"] = job.videoFaceCaptureEnc
        if (job.attDuration.isNotEmpty()) params["attDuration"] = job.attDuration
        if (job.attDurationEnc.isNotEmpty()) params["attDurationEnc"] = job.attDurationEnc

        val url = "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/${course.cpi}/$dtoken"

        var rt = job.rt
        if (rt.isEmpty()) {
            val match = Regex("-rt_([1d])").find(job.otherinfo)
            if (match != null) {
                rt = if (match.groupValues[1] == "d") "0.9" else "1"
                Logx.trace("Got rt from otherinfo: $rt")
            }
        }

        if (rt.isNotEmpty()) {
            Logx.trace("Got rt: $rt")
            params["rt"] = rt
            params["_t"] = System.currentTimeMillis().toString()
            val resp = httpGet(url, params, headers)
            if (resp.code == 200) {
                Logx.trace(resp.text.take(500))
                return parsePassed(resp.text) to 200
            }
            if (resp.code == 403) {
                Logx.error("出现403报错, 尝试修复无效, 正在跳过当前任务点...")
                return false to 403
            }
            Logx.error("未知错误: ${resp.code}")
            return false to resp.code
        }

        Logx.warn("Failed to get rt")
        var lastCode = 0
        for (candidate in listOf("0.9", "1")) {
            params["rt"] = candidate
            params["_t"] = System.currentTimeMillis().toString()
            val resp = httpGet(url, params, headers)
            lastCode = resp.code
            if (resp.code == 200) {
                Logx.trace(resp.text.take(500))
                return parsePassed(resp.text) to 200
            } else if (resp.code == 403) {
                Logx.warn("出现403报错, 正常尝试切换rt")
            } else {
                Logx.warn("未知错误 jobid=${job.jobid}, status_code=${resp.code}, 摘要:\n${resp.text.take(200)}")
                break
            }
        }

        if (lastCode == 403) {
            Logx.error("出现403报错, 尝试修复无效, 正在跳过当前任务点...")
            return false to 403
        }
        Logx.error("未知错误: $lastCode")
        return false to lastCode
    }

    private fun parsePassed(text: String): Boolean {
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null) {
            Logx.warn("进度响应解析失败: ${text.take(200)}")
            return false
        }
        return json.optBoolean("isPassed", false)
    }

    private suspend fun refreshVideoStatus(job: Job, type: String): JSONObject? {
        rateLimiter.limit(random = true, randomMaxMs = 200)
        val headers = if (type == "Video") Cfg.VIDEO_HEADERS else Cfg.AUDIO_HEADERS
        val params = mapOf("k" to (Net.cookie("fid") ?: ""), "flag" to "normal")
        val resp = try {
            httpGet("https://mooc1.chaoxing.com/ananas/status/${job.objectid}", params, headers)
        } catch (e: Exception) {
            Logx.debug("刷新视频状态失败: ${e.message}")
            return null
        }
        if (resp.code != 200) {
            Logx.debug("刷新视频状态返回码异常: ${resp.code}")
            return null
        }
        val json = runCatching { JSONObject(resp.text) }.getOrNull()
        if (json == null) {
            Logx.debug("解析视频状态响应失败")
            return null
        }
        return if (json.optString("status") == "success") json else null
    }

    private suspend fun recoverAfterForbidden(job: Job, type: String): JSONObject? {
        // 原逻辑：SessionManager.update_cookies() 后刷新状态；Kotlin 版 Cookie 始终同一份
        return refreshVideoStatus(job, type)
    }

    /** 对应 study_video：按倍速推进播放进度并定时上报。 */
    suspend fun studyVideo(course: Course, job: Job, speed: Double, type: String): StudyResult {
        val headers = if (type == "Video") Cfg.VIDEO_HEADERS else Cfg.AUDIO_HEADERS
        val infoParams = mapOf("k" to (Net.cookie("fid") ?: ""), "flag" to "normal")

        val infoText = try {
            httpGet("https://mooc1.chaoxing.com/ananas/status/${job.objectid}", infoParams, headers).text
        } catch (e: Exception) {
            Logx.error("获取视频信息失败: ${e.message}")
            return StudyResult.ERROR
        }

        val videoInfo = runCatching { JSONObject(infoText) }.getOrNull()
        if (videoInfo == null) {
            Logx.error("获取视频信息失败: 响应解析失败")
            return StudyResult.ERROR
        }
        if (videoInfo.optString("status") != "success") {
            Logx.error("Unknown status: ${videoInfo.optString("status")}")
            return StudyResult.ERROR
        }

        var dtoken = videoInfo.optString("dtoken", "")
        if (dtoken.isEmpty()) {
            Logx.error("视频信息缺少 dtoken")
            return StudyResult.ERROR
        }

        var duration = videoInfo.optInt("duration", 0)
        var playTime = (job.playTime / 1000L).toDouble()
        var lastLogTime = 0.0
        var lastIter = System.currentTimeMillis()
        var waitTime = Random.nextInt(30, 91)

        Logx.info("开始任务: ${job.name}, 总时长: ${duration}s, 已进行: ${playTime.toInt()}s")

        var forbiddenRetry = 0
        val maxForbiddenRetry = 2
        var lastProgressLog = 0.0

        videoProgressLog(course, job, dtoken, duration, playTime, type, headers)
        var result = videoProgressLog(
            course, job, dtoken, duration, duration.toDouble(), type, headers
        )
        var passed = result.first
        var state = result.second

        if (passed) {
            Logx.info("任务瞬间完成: ${job.name}")
            return StudyResult.SUCCESS
        }

        while (!passed) {
            if (playTime - lastLogTime >= waitTime || playTime >= duration) {
                result = videoProgressLog(course, job, dtoken, duration, playTime, type, headers)
                passed = result.first
                state = result.second

                if (state == 403) {
                    if (forbiddenRetry >= maxForbiddenRetry) {
                        Logx.warn("403重试失败, 跳过当前任务")
                        return StudyResult.FORBIDDEN
                    }
                    forbiddenRetry += 1
                    Logx.warn("出现403报错, 正在尝试刷新会话状态 (第${forbiddenRetry}次)")
                    delay(Random.nextLong(2000, 4001))
                    val refreshed = recoverAfterForbidden(job, type)
                    if (refreshed != null) {
                        dtoken = refreshed.optString("dtoken", dtoken)
                        duration = refreshed.optInt("duration", duration)
                        playTime = refreshed.optDouble("playTime", playTime)
                        Logx.debug(
                            "Refreshed token: $dtoken, duration: $duration, play time: $playTime"
                        )
                        continue
                    }
                } else if (!passed && state != 200) {
                    return StudyResult.ERROR
                }

                waitTime = Random.nextInt(30, 91)
                lastLogTime = playTime
            }

            // 上报进度本身耗时，按倍速补算视频实际播放时间
            val now = System.currentTimeMillis()
            val dt = (now - lastIter) / 1000.0 * speed
            lastIter = now
            playTime = minOf(duration.toDouble(), playTime + dt)

            if (playTime - lastProgressLog >= 10 || playTime >= duration) {
                lastProgressLog = playTime
                Logx.info("进度: ${job.name} ${playTime.toInt()}/${duration}s")
            }
            delay(Cfg.THRESHOLD_MS)
        }

        Logx.info("任务完成: ${job.name}")
        return StudyResult.SUCCESS
    }

    /** 对应 study_emptypage：空章节也请求一次，保持章节访问状态。 */
    suspend fun studyEmptyPage(course: Course, point: ChapterPoint): StudyResult {
        val params = mapOf(
            "courseId" to course.courseId,
            "clazzid" to course.clazzId,
            "chapterId" to point.id,
            "cpi" to course.cpi,
            "verificationcode" to "",
            "mooc2" to "1",
            "microTopicId" to "0",
            "editorPreview" to "0",
        )
        val resp = try {
            httpGet("https://mooc1.chaoxing.com/mooc-ans/mycourse/studentstudyAjax", params)
        } catch (e: Exception) {
            Logx.error("空页面任务失败 -> ${e.message}")
            return StudyResult.ERROR
        }
        if (resp.code != 200) {
            Logx.error("空页面任务失败 -> [${resp.code}]${point.title}")
            return StudyResult.ERROR
        }
        Logx.info("空页面任务完成 -> ${point.title}")
        return StudyResult.SUCCESS
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------
    private suspend fun httpGet(
        url: String,
        params: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult = withContext(Dispatchers.IO) {
        val resp = Net.get(url, params, headers)
        resp.use { HttpResult(it.code, it.body?.string().orEmpty()) }
    }

    private suspend fun httpPost(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult = withContext(Dispatchers.IO) {
        val resp = Net.post(url, form, headers)
        resp.use { HttpResult(it.code, it.body?.string().orEmpty()) }
    }
}
