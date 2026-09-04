package com.cgfree.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 进程内环形日志缓冲，供代理服务运行日志展示 */
object LogBuffer {
    private const val MAX = 600
    private val items = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(msg: String) {
        items.addLast("[" + fmt.format(Date()) + "] " + msg)
        while (items.size > MAX) items.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = items.joinToString("\n")

    @Synchronized
    fun clear() = items.clear()
}