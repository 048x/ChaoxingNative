package com.chaoxing.nativeapp

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 学习任务运行状态（Worker 与界面同进程共享）。
 */
object StudyBus {
    @Volatile
    private var running = false

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    val isRunning: Boolean
        get() = running

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        listeners.forEach { runCatching { it(value) } }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
