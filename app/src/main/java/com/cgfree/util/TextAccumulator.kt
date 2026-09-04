package com.cgfree.util

/**
 * SSE 文本增量器。
 *
 * ChatGPT conversation 私有接口的 SSE 事件中，message.content.parts 是
 * “当前完整快照”（逐事件增长）；而 OpenAI /v1/chat/completions 协议需要
 * 纯增量 delta。本类把快照流转换为增量流，也兼容纯增量型协议。
 */
class TextAccumulator {
    private var sent = ""

    /** 输入一个文本快照，回调输出相对上一次的增量（可能为空字符串） */
    fun push(snapshot: String, emit: (String) -> Unit) {
        if (snapshot.isEmpty()) return
        if (snapshot.startsWith(sent) && sent.isNotEmpty()) {
            val delta = snapshot.removePrefix(sent)
            if (delta.isNotEmpty()) {
                sent = snapshot
                emit(delta)
            }
        } else {
            // 与已发送文本不连续：视为新快照/重置（纯增量协议时 snapshot 即新 token）
            sent = snapshot
            emit(snapshot)
        }
    }

    fun reset() {
        sent = ""
    }
}