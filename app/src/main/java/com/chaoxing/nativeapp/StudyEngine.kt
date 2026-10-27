package com.chaoxing.nativeapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对应原 main.py 的 process_course / process_chapter / process_job / JobProcessor。
 * 使用协程 + Channel 替代 Python 的 PriorityQueue 多线程，行为保持一致：
 * - 章节并发数 = config.jobs
 * - 每个章节内任务点并发数 = 5
 * - 失败最多重试 5 次，重试前等待 1 秒
 * - 遇到未开放章节时按 notOpenAction 处理（retry / continue）
 */
class StudyEngine(
    private val api: ChaoxingApi,
    private val config: StudyConfig,
) {
    private class Task(val point: ChapterPoint) {
        var tries: Int = 0
    }

    suspend fun run(courses: List<Course>) {
        for (course in courses) {
            Logx.info("开始学习课程: ${course.title}")
            val points = try {
                api.getCoursePoint(course.courseId, course.clazzId, course.cpi).points
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logx.error("读取课程章节失败 -> [${course.title}] ${e.message}")
                continue
            }
            processCourse(course, points)
        }
        Logx.info("所有课程视频任务已完成")
    }

    private suspend fun processCourse(course: Course, points: List<ChapterPoint>) = coroutineScope {
        if (points.isEmpty()) return@coroutineScope

        val pending = AtomicInteger(points.size)
        val queue = Channel<Task>(Channel.UNLIMITED)
        val retryQueue = Channel<Task>(Channel.UNLIMITED)

        points.forEach { queue.trySend(Task(it)) }

        val workers = List(config.jobs.coerceAtLeast(1)) {
            launch(Dispatchers.IO) {
                for (task in queue) {
                    val result = try {
                        processChapter(course, task.point)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logx.error("处理章节异常: ${e.message}")
                        ChapterResult.ERROR
                    }

                    when (result) {
                        ChapterResult.SUCCESS -> {
                            Logx.debug("Task success: ${task.point.title}")
                            finish(pending, queue, retryQueue)
                        }

                        ChapterResult.NOT_OPEN -> {
                            if (config.notOpenAction == "continue") {
                                Logx.warn("章节未开启: ${task.point.title}, 正在跳过")
                                finish(pending, queue, retryQueue)
                            } else if (task.tries >= MAX_TRIES) {
                                Logx.error(
                                    "章节未开启: ${task.point.title}, " +
                                        "可能因时效关闭或需先完成前置任务，请手动检查"
                                )
                                finish(pending, queue, retryQueue)
                            } else {
                                retryQueue.send(task)
                            }
                        }

                        ChapterResult.ERROR -> {
                            task.tries += 1
                            Logx.warn(
                                "Retrying task ${task.point.title} " +
                                    "(${task.tries}/$MAX_TRIES attempts)"
                            )
                            if (task.tries >= MAX_TRIES) {
                                Logx.error("Max retries reached for task: ${task.point.title}")
                                finish(pending, queue, retryQueue)
                            } else {
                                retryQueue.send(task)
                            }
                        }

                        else -> {
                            Logx.error("Invalid task state $result for task ${task.point.title}")
                            finish(pending, queue, retryQueue)
                        }
                    }
                }
            }
        }

        val retryPump = launch(Dispatchers.IO) {
            for (task in retryQueue) {
                delay(1000)
                queue.send(task)
            }
        }

        workers.joinAll()
        retryPump.cancel()
    }

    private fun finish(
        pending: AtomicInteger,
        queue: Channel<Task>,
        retryQueue: Channel<Task>,
    ) {
        if (pending.decrementAndGet() == 0) {
            queue.close()
            retryQueue.close()
        }
    }

    /** 对应 process_chapter */
    private suspend fun processChapter(course: Course, point: ChapterPoint): Int {
        Logx.info("当前章节: ${point.title}")
        if (point.hasFinished) {
            Logx.info("章节：${point.title} 已完成所有任务点")
            return ChapterResult.SUCCESS
        }

        api.rateLimiter.limit(random = true, randomMaxMs = 200)

        val (jobs, info) = api.getJobList(course, point)
        if (info.notOpen) return ChapterResult.NOT_OPEN

        val results = coroutineScope {
            val semaphore = Semaphore(5)
            jobs.map { job ->
                async(Dispatchers.IO) { semaphore.withPermit { processJob(course, job) } }
            }.awaitAll()
        }

        for (result in results) {
            if (result.isFailure) return ChapterResult.ERROR
        }
        return ChapterResult.SUCCESS
    }

    /** 对应 process_job：只处理视频任务，视频失败后尝试音频。 */
    private suspend fun processJob(course: Course, job: Job): StudyResult {
        if (job.type != "video") {
            Logx.debug("跳过非视频任务点: ${job.name.ifEmpty { job.jobid }}")
            return StudyResult.SUCCESS
        }

        Logx.trace("识别到视频任务, 任务章节: ${course.title} 任务ID: ${job.jobid}")

        var result = api.studyVideo(course, job, config.speed, "Video")
        if (result.isFailure) {
            Logx.warn("当前任务非视频任务, 正在尝试音频任务解码")
            result = api.studyVideo(course, job, config.speed, "Audio")
        }
        if (result.isFailure) {
            Logx.warn("出现异常任务 -> 任务章节: ${course.title} 任务ID: ${job.jobid}, 已跳过")
        }
        return result
    }

    companion object {
        private const val MAX_TRIES = 5
    }
}
