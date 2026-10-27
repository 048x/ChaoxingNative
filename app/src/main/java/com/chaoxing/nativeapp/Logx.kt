package com.chaoxing.nativeapp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 对应原 Python 版 loguru 日志输出：进程内收集，供界面实时展示。
 */
object Logx {
    private const val MAX_LINES = 4000

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    val lineCount: Int
        get() = synchronized(lines) { lines.size }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        notifyListeners()
    }

    fun trace(msg: String) = write(msg)

    fun debug(msg: String) = write(msg)

    fun info(msg: String) = write(msg)

    fun warn(msg: String) = write("警告: $msg")

    fun error(msg: String) = write("错误: $msg")

    private fun write(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
