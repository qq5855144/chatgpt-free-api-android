package com.cgfree.proxy

import android.content.Context
import com.cgfree.data.ChatMsg
import com.cgfree.data.ConversationRequest
import com.cgfree.data.ModelConst
import com.cgfree.data.Prefs
import com.cgfree.data.TokenStore
import com.cgfree.net.ChatGPTClient
import com.cgfree.util.LogBuffer
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 本地 OpenAI 兼容反向代理服务器。
 *
 * 把 ChatGPT 网页免费额度包装为 OpenAI 风格接口：
 *   GET  /v1/models            模型列表
 *   POST /v1/chat/completions  对话补全（支持 stream 流式）
 *
 * 架构参考 deepseek-free-api（网页凭证 → OpenAI 兼容 API），在 Android 端以 NanoHTTPD 实现。
 */
class ProxyServer(
    private val appContext: Context,
    port: Int,
    lan: Boolean,
    private val client: OkHttpClient = ChatGPTClient.newClient()
) : NanoHTTPD(if (lan) null else "127.0.0.1", port) {

    companion object {
        private var modelsCache: Pair<Long, List<String>>? = null
        private const val MODELS_TTL_MS = 10 * 60 * 1000L
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            LogBuffer.log("server error [${e.javaClass.simpleName}]: ${e.message}")
            jsonError(500, "内部错误[${e.javaClass.simpleName}]: ${e.message}")
        }
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri
        val method = session.method.name

        // CORS 预检
        if (method == "OPTIONS") {
            return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        }

        // 可选访问密钥校验（防止局域网被滥用）
        val expectKey = Prefs.apiKey(appContext)
        if (!expectKey.isNullOrBlank()) {
            val auth = session.headers["authorization"] ?: ""
            val xkey = session.headers["x-api-key"] ?: ""
            if (auth != "Bearer $expectKey" && xkey != expectKey) {
                return jsonError(401, "无效的访问密钥（请在请求头携带 x-api-key 或 Authorization: Bearer <key>）")
            }
        }

        return when {
            path == "/" || path == "/health" -> info()
            path == "/v1/models" -> models()
            path == "/v1/chat/completions" && method == "POST" -> completions(session)
            path == "/v1/chat/completions" -> jsonError(405, "method not allowed")
            else -> jsonError(404, "not found: $path")
        }
    }

    // ---------- 基础端点 ----------

    private fun info(): Response {
        val j = JSONObject()
        j.put("service", "chatgpt-free-api-android")
        j.put("status", "ok")
        j.put("notice", "仅限个人学习自用，请遵守 ChatGPT 服务条款")
        return json(200, j)
    }

    private fun models(): Response {
        val token = TokenStore.getAccessToken(appContext)
        var list: List<String>? = null
        val cached = modelsCache
        if (cached != null && System.currentTimeMillis() - cached.first < MODELS_TTL_MS) {
            list = cached.second
        } else if (token != null) {
            list = runCatching {
                ChatGPTClient.fetchModels(
                    token, client,
                    cookie = TokenStore.getCookie(appContext),
                    sessionToken = TokenStore.getSessionToken(appContext)
                )
            }
                .onFailure { LogBuffer.log("拉取模型列表失败: ${it.message}（使用预设列表）") }
                .getOrNull()
            if (list != null) modelsCache = System.currentTimeMillis() to list
        }
        val models = list ?: ModelConst.PRESET
        val data = JSONArray()
        for (m in models) {
            data.put(JSONObject()
                .put("id", m)
                .put("object", "model")
                .put("created", 0)
                .put("owned_by", "chatgpt-free"))
        }
        val j = JSONObject().put("object", "list").put("data", data)
        return json(200, j)
    }

    // ---------- 对话补全 ----------

    private fun completions(session: IHTTPSession): Response {
        val token = TokenStore.getAccessToken(appContext)
        if (token.isNullOrBlank()) {
            return jsonError(401, "账号未登录：请先在 App「账号」页登录 ChatGPT 或粘贴令牌")
        }

        val raw = session.inputStream.readBytes().toString(Charsets.UTF_8)
        val body = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse {
            return jsonError(400, "请求体不是合法 JSON")
        }

        // 解析 OpenAI 参数
        var model = body.optString("model", "").trim().lowercase()
        if (model.isBlank() || model == "gpt-3.5-turbo" || model.startsWith("gpt-3.5")) {
            model = Prefs.model(appContext)
        }
        val stream = body.optBoolean("stream", false)
        val convId = body.optString("conversation_id", "").takeIf { it.isNotBlank() }
        val historyDisabled = body.optBoolean("history_and_training_disabled", true)

        val msgsArr = body.optJSONArray("messages")
            ?: return jsonError(400, "缺少 messages 字段")
        if (msgsArr.length() == 0) return jsonError(400, "messages 不能为空")

        val chatMsgs = ArrayList<ChatMsg>()
        for (i in 0 until msgsArr.length()) {
            val m = msgsArr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = extractText(m.opt("content")) ?: continue
            chatMsgs.add(ChatMsg(role, content))
        }
        if (chatMsgs.isEmpty()) return jsonError(400, "messages 中没有可用的文本内容")

        val request = ConversationRequest(
            model = model,
            messages = chatMsgs,
            conversationId = convId,
            historyAndTrainingDisabled = historyDisabled
        )

        LogBuffer.log("POST /v1/chat/completions model=$model stream=$stream msgs=${chatMsgs.size} roles=${chatMsgs.groupingBy { it.role }.eachCount()}")

        return if (stream) streamResponse(token, request) else syncResponse(token, request)
    }

    /** OpenAI content 可能是字符串或分段数组（忽略图片等多模态内容） */
    private fun extractText(content: Any?): String? = when (content) {
        is String -> if (content.isBlank()) null else content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.opt(i)
                if (part is String) sb.append(part)
                else if (part is JSONObject && part.optString("type") == "text") sb.append(part.optString("text"))
            }
            sb.toString().takeIf { it.isNotBlank() }
        }
        else -> null
    }

    // ---------- SSE 流式响应 ----------

    private fun streamResponse(token: String, request: ConversationRequest): Response {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        val created = System.currentTimeMillis() / 1000
        val acc = com.cgfree.util.TextAccumulator()
        val t0 = System.currentTimeMillis()

        // Piped 管道：生产者线程写 OpenAI SSE 块，NanoHTTPD newChunkedResponse 流式发送
        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos, 64 * 1024)

        fun writeSse(text: String) {
            pos.write(text.toByteArray(Charsets.UTF_8))
            pos.flush()
        }

        val producer = Thread({
            try {
                var first = true
                ChatGPTClient.streamConversation(
                    token,
                    TokenStore.getSessionToken(appContext),
                    TokenStore.getCookie(appContext),
                    request,
                    onRefreshed = { newTok -> TokenStore.saveAccessToken(appContext, newTok) },
                    onEvent = { ev ->
                        when (ev) {
                            is ChatGPTClient.Event.Delta -> {
                                if (first) {
                                    writeSse(sseChunk(id, created, request.model, role = "assistant", content = ""))
                                    first = false
                                }
                                acc.push(ev.text) { delta ->
                                    writeSse(sseChunk(id, created, request.model, content = delta))
                                }
                            }
                            is ChatGPTClient.Event.Final -> {
                                if (first) {
                                    writeSse(sseChunk(id, created, request.model, role = "assistant", content = ""))
                                    first = false
                                }
                                acc.push(ev.text) { delta ->
                                    writeSse(sseChunk(id, created, request.model, content = delta))
                                }
                            }
                            is ChatGPTClient.Event.ConvId -> { /* 忽略 */ }
                            is ChatGPTClient.Event.Error -> {
                                LogBuffer.log("stream 上游错误: ${ev.message}")
                                writeSse(sseErrorChunk(id, created, request.model, ev.message))
                            }
                            ChatGPTClient.Event.Done -> { /* 由流结束处理 */ }
                        }
                    }
                )
                writeSse(sseFinishChunk(id, created, request.model))
                writeSse("data: [DONE]\n\n")
                LogBuffer.log("stream 完成 model=${request.model} 耗时 ${(System.currentTimeMillis() - t0) / 1000}s")
            } catch (e: Exception) {
                LogBuffer.log("stream 异常: ${e.message}")
                // 客户端断开或上游异常：尽力发送错误块后结束
                try {
                    writeSse(sseErrorChunk(id, created, request.model, e.message ?: "stream error"))
                    writeSse("data: [DONE]\n\n")
                } catch (ignored: Exception) {
                }
            } finally {
                try {
                    pos.close()
                } catch (ignored: Exception) {
                }
            }
        }, "cgfree-producer")
        producer.isDaemon = true
        producer.start()

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pis)
        return cors(resp)
    }

    // ---------- 非流式（聚合后一次性返回） ----------

    private fun syncResponse(token: String, request: ConversationRequest): Response {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        val created = System.currentTimeMillis() / 1000
        val sb = StringBuilder()
        val acc = com.cgfree.util.TextAccumulator()
        var error: String? = null
        val t0 = System.currentTimeMillis()

        try {
            ChatGPTClient.streamConversation(
            token,
            TokenStore.getSessionToken(appContext),
            TokenStore.getCookie(appContext),
            request,
            onRefreshed = { newTok -> TokenStore.saveAccessToken(appContext, newTok) },
            onEvent = { ev ->
                when (ev) {
                    is ChatGPTClient.Event.Delta -> acc.push(ev.text) { sb.append(it) }
                    is ChatGPTClient.Event.Final -> acc.push(ev.text) { sb.append(it) }
                    is ChatGPTClient.Event.Error -> error = ev.message
                    else -> { /* ignore */ }
                }
            }
        )
        } catch (e: Exception) {
            LogBuffer.log("sync 异常: ${e.message} model=${request.model}")
            val j = JSONObject().put("error", JSONObject()
                .put("message", "上游请求失败：${e.message ?: "未知错误"}")
                .put("type", "upstream_error"))
            return json(502, j)
        }

        if (error != null) {
            LogBuffer.log("sync 上游错误: $error model=${request.model}")
            val j = JSONObject().put("error", JSONObject()
                .put("message", error)
                .put("type", "upstream_error"))
            return json(502, j)
        }

        val text = sb.toString()
        LogBuffer.log("sync 完成 model=${request.model} 字符=${text.length} 耗时 ${(System.currentTimeMillis() - t0) / 1000}s")
        val pTokens = estimateTokens(chatChars(request.messages))
        val cTokens = estimateTokens(text.length)
        val j = JSONObject()
        j.put("id", id)
        j.put("object", "chat.completion")
        j.put("created", created)
        j.put("model", request.model)
        j.put("choices", JSONArray().put(JSONObject()
            .put("index", 0)
            .put("message", JSONObject()
                .put("role", "assistant")
                .put("content", text))
            .put("finish_reason", "stop")))
        j.put("usage", JSONObject()
            .put("prompt_tokens", pTokens)
            .put("completion_tokens", cTokens)
            .put("total_tokens", pTokens + cTokens))
        return json(200, j)
    }

    private fun chatChars(msgs: List<ChatMsg>): Int = msgs.sumOf { it.content.length }

    private fun estimateTokens(chars: Int): Int = (chars / 4).coerceAtLeast(1)

    // ---------- SSE 组装 ----------

    private fun sseChunk(id: String, created: Long, model: String, role: String? = null, content: String): String {
        val delta = JSONObject()
        role?.let { delta.put("role", it) }
        delta.put("content", content)
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", JSONObject.NULL)
        val j = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return "data: $j\n\n"
    }

    private fun sseErrorChunk(id: String, created: Long, model: String, message: String): String {
        val j = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("delta", JSONObject())
                .put("finish_reason", "stop")))
            .put("error", JSONObject().put("message", message))
        return "data: $j\n\n"
    }

    private fun sseFinishChunk(id: String, created: Long, model: String): String {
        val j = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("delta", JSONObject())
                .put("finish_reason", "stop")))
        return "data: $j\n\n"
    }

    // ---------- 工具 ----------

    private fun json(code: Response.Status, obj: JSONObject): Response {
        val r = newFixedLengthResponse(code, "application/json; charset=utf-8", obj.toString())
        return cors(r)
    }

    private fun json(code: Int, obj: JSONObject): Response =
        json(Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR, obj)

    private fun jsonError(code: Int, message: String): Response {
        val j = JSONObject().put("error", JSONObject()
            .put("message", message)
            .put("type", "invalid_request_error"))
        LogBuffer.log("http error $code: $message")
        return json(code, j)
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, x-api-key")
        r.addHeader("Cache-Control", "no-cache")
        return r
    }
}