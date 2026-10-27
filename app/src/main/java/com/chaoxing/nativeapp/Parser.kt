package com.chaoxing.nativeapp

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对应原 api/decode.py：课程 / 二级目录 / 章节 / 任务点解析。
 */
object Parser {

    private val CPI_RE = Regex("cpi=(.*?)&")
    private val POINT_ID_RE = Regex("^cur(\\d{1,20})$")
    private val MARG_RE = Regex("mArg=\\{(.*?)\\};")

    fun parseCourseList(html: String): List<Course> {
        Logx.trace("开始解码课程列表...")
        val doc = Jsoup.parse(html)
        val result = mutableListOf<Course>()

        for (el in doc.select("div.course")) {
            if (el.selectFirst("a.not-open-tip") != null || el.selectFirst("div.not-open-tip") != null) {
                continue
            }
            val clazzId = el.selectFirst("input.clazzId")?.attr("value") ?: continue
            val courseId = el.selectFirst("input.courseId")?.attr("value") ?: continue
            val href = el.selectFirst("a")?.attr("href") ?: continue
            val cpi = CPI_RE.find(href)?.groupValues?.get(1) ?: continue

            result.add(
                Course(
                    id = el.attr("id"),
                    info = el.attr("info"),
                    roleid = el.attr("roleid"),
                    clazzId = clazzId,
                    courseId = courseId,
                    cpi = cpi,
                    title = el.selectFirst("span.course-name")?.attr("title").orEmpty(),
                    desc = el.selectFirst("p.margint10")?.attr("title").orEmpty(),
                    teacher = el.selectFirst("p.color3")?.attr("title").orEmpty(),
                )
            )
        }
        return result
    }

    fun parseCourseFolder(html: String): List<Pair<String, String>> {
        Logx.trace("开始解码二级课程列表...")
        val doc = Jsoup.parse(html)
        return doc.select("ul.file-list > li").mapNotNull { li ->
            val fileId = li.attr("fileid")
            if (fileId.isEmpty()) return@mapNotNull null
            val rename = li.selectFirst("input.rename-input")?.attr("value").orEmpty()
            fileId to rename
        }
    }

    fun parseCoursePoint(html: String): CoursePoint {
        Logx.trace("开始解码章节列表...")
        val doc = Jsoup.parse(html)
        var hasLocked = false
        val points = mutableListOf<ChapterPoint>()

        for (unit in doc.select("div.chapter_unit")) {
            for (extracted in extractPoints(unit)) {
                if (extracted.needUnlock) hasLocked = true
                points.add(extracted)
            }
        }
        return CoursePoint(hasLocked, points)
    }

    private fun extractPoints(unit: Element): List<ChapterPoint> {
        val list = mutableListOf<ChapterPoint>()
        for (li in unit.select("li")) {
            val div = li.children().firstOrNull { it.tagName() == "div" }
                ?: li.selectFirst("div")
                ?: continue
            val idAttr = div.attr("id")
            if (idAttr.isEmpty()) continue
            val pointId = POINT_ID_RE.find(idAttr)?.groupValues?.get(1) ?: continue

            val title = div.selectFirst("a.clicktitle")?.text()?.replace("\n", "")?.trim().orEmpty()
            var jobCount = "1"
            var needUnlock = false

            val jobCountInput = div.selectFirst("input.knowledgeJobCount")
            if (jobCountInput != null) {
                jobCount = jobCountInput.attr("value")
            } else {
                val tips = div.selectFirst("span.bntHoverTips")
                if (tips != null && tips.text().contains("解锁")) needUnlock = true
            }

            val tips = div.selectFirst("span.bntHoverTips")
            val finished = tips != null && tips.text().contains("已完成")

            list.add(ChapterPoint(pointId, title, jobCount, finished, needUnlock))
        }
        return list
    }

    data class CardResult(val jobs: List<Job>, val info: JobInfo, val notOpen: Boolean)

    fun parseCourseCard(html: String): CardResult {
        Logx.trace("开始解码任务点列表...")
        if (html.contains("章节未开放")) {
            return CardResult(emptyList(), JobInfo(notOpen = true), true)
        }

        val compact = html.replace(" ", "")
        val match = MARG_RE.find(compact)
            ?: return CardResult(emptyList(), JobInfo(), false)

        val root = runCatching { JSONObject("{" + match.groupValues[1] + "}") }.getOrNull()
            ?: return CardResult(emptyList(), JobInfo(), false)
        if (root.length() == 0) return CardResult(emptyList(), JobInfo(), false)

        val info = extractJobInfo(root)
        val jobs = processAttachments(root.optJSONArray("attachments"))
        return CardResult(jobs, info, false)
    }

    private fun extractJobInfo(root: JSONObject): JobInfo {
        val defaults = root.optJSONObject("defaults") ?: return JobInfo()
        if (defaults.length() == 0) return JobInfo()
        return JobInfo(
            ktoken = defaults.optString("ktoken", ""),
            mtEnc = defaults.optString("mtEnc", ""),
            reportTimeInterval = defaults.optInt("reportTimeInterval", 60),
            defenc = defaults.optString("defenc", ""),
            cardid = defaults.optString("cardid", ""),
            cpi = defaults.optString("cpi", ""),
            qnenc = defaults.optString("qnenc", ""),
            knowledgeid = defaults.optString("knowledgeid", ""),
        )
    }

    private fun processAttachments(cards: JSONArray?): List<Job> {
        if (cards == null) return emptyList()
        val jobs = mutableListOf<Job>()

        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i) ?: continue
            if (card.optBoolean("isPassed", false)) continue
            if (card.isNull("job")) continue

            val type = card.optString("type", "").lowercase()
            if (type != "video") {
                Logx.debug("跳过非视频任务点: ${type.ifEmpty { "unknown" }}")
                continue
            }

            val otherInfo = card.optString("otherInfo", "").let {
                if (card.has("otherInfo")) it.split("&")[0] else it
            }

            val mid = card.optString("mid", "")
            if (mid.isEmpty()) {
                Logx.warn("出现转码失败视频，已跳过...")
                continue
            }

            val property = card.optJSONObject("property")
            jobs.add(
                Job(
                    type = "video",
                    jobid = card.optString("jobid", ""),
                    name = property?.optString("name", "") ?: "",
                    otherinfo = otherInfo,
                    mid = mid,
                    objectid = card.optString("objectId", ""),
                    aid = card.optString("aid", ""),
                    playTime = card.optLong("playTime", 0),
                    rt = property?.optString("rt", "") ?: "",
                    attDuration = card.optString("attDuration", ""),
                    attDurationEnc = card.optString("attDurationEnc", ""),
                    videoFaceCaptureEnc = card.optString("videoFaceCaptureEnc", ""),
                )
            )
        }
        return jobs
    }
}
