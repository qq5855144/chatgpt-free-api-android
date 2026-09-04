package com.cgfree.data

/** 一条对话消息（OpenAI 兼容三元组角色） */
data class ChatMsg(val role: String, val content: String)

/** 会话请求参数 */
data class ConversationRequest(
    val model: String,
    val messages: List<ChatMsg>,
    val conversationId: String? = null,
    val historyAndTrainingDisabled: Boolean = true
)

/** 常见模型常量（实际可用模型以官方 /backend-api/models 返回为准） */
object ModelConst {
    const val DEFAULT = "gpt-4o-mini"
    val PRESET = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "o3-mini")
}