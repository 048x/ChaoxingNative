package com.chaoxing.nativeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * 学习任务 Worker：任务在 WorkManager 的前台服务中运行，不占用主线程。
 */
class StudyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Net.init(applicationContext)
        StudyBus.setRunning(true)

        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            Logx.warn("前台通知启动失败: ${e.message}")
        }

        return try {
            val task = StudyRepo.load(applicationContext)
                ?: throw IllegalStateException("学习配置缺失，请重新登录并选择课程")

            Logx.info("学习任务已启动")
            val config = StudyConfig(task.speed, task.jobs, task.notOpenAction)
            StudyEngine(ChaoxingApi(), config).run(task.courses)
            Result.success()
        } catch (e: CancellationException) {
            Logx.warn("任务已停止")
            throw e
        } catch (e: Exception) {
            Logx.error("任务异常: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure()
        } finally {
            StudyBus.setRunning(false)
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        ensureChannel(ctx)

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(ctx.getString(R.string.notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "cx_study_work"
        const val CHANNEL_ID = "cx_study"
        private const val NOTIF_ID = 1001

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }
    }
}
