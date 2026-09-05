package com.cgfree.data

/** 一条对话消息；name/toolCallId 用于把 MCP 工具结果完整传回模型。 */
data class ChatMsg(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null
)

/** OpenAI tools 中 type=function 的工具定义。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

/** 会话请求参数 */
data class ConversationRequest(
    val model: String,
    val messages: List<ChatMsg>,
    val conversationId: String? = null,
    val historyAndTrainingDisabled: Boolean = true,
    val tools: List<ToolSpec> = emptyList(),
    /** auto / required / none，或被强制调用的函数名。 */
    val toolChoice: String? = null
)

/** 常见模型常量（实际可用模型以官方 /backend-api/models 返回为准） */
object ModelConst {
    const val DEFAULT = "gpt-5-5"
    val PRESET = listOf(
        "gpt-5-6", "gpt-5-5", "gpt-5-6-instant", "gpt-5-5-instant",
        "gpt-5-6-mini", "gpt-5-5-mini", "gpt-5-4-t-mini", "gpt-5-6-t-mini",
        "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
        "o3-mini"
    )
}
