package com.cgfree.net

import com.cgfree.data.ConversationRequest
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ChatGPT 网页私有接口（chatgpt.com/backend-api）逆向调用客户端。
 *
 * - 使用网页登录得到的 accessToken 作为 Bearer 凭证；
 * - POST /backend-api/conversation 走 SSE 流式返回（事件流解析）；
 * - 遇到 401/403 且配置了 sessionToken 时，自动通过 /api/auth/session 换取新 accessToken 后重试一次。
 */
object ChatGPTClient {

    const val WEB_BASE = "https://chatgpt.com"
    private val JSON = MediaType.get("application/json; charset=utf-8")
    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 流式事件 */
    sealed class Event {
        /** 增量文本 */
        class Delta(val text: String) : Event()

        /** 最终完整文本（官方最后一次 end_turn 事件携带全文，用于校正拼接结果） */
        class Final(val text: String) : Event()

        /** 会话 id（多轮续接可选） */
        class ConvId(val id: String) : Event()

        /** 结束 */
        object Done : Event()

        /** 错误 */
        class Error(val message: String, val status: Int? = null) : Event()
    }

    class ApiException(message: String, val status: Int? = null) : Exception(message)

    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /** 拉取当前账号可用模型列表 */
    fun fetchModels(accessToken: String, client: OkHttpClient = newClient()): List<String> {
        val req = Request.Builder()
            .url("$WEB_BASE/backend-api/models")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(friendlyError(resp.code, body), resp.code)
            val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val slug = arr.optJSONObject(i)?.optString("slug") ?: continue
                if (slug.isNotBlank()) out.add(slug)
            }
            return out
        }
    }

    /**
     * 流式对话（阻塞调用，请放到后台线程）。
     * @param onRefreshed 401 后成功用 sessionToken 刷新出新 accessToken 时回调（调用方负责持久化）
     */
    fun streamConversation(
        accessToken: String,
        sessionToken: String?,
        request: ConversationRequest,
        onRefreshed: (String) -> Unit,
        onEvent: (Event) -> Unit,
        client: OkHttpClient = newClient()
    ) {
        var token = accessToken
        try {
            runOnce(token, request, onEvent, client)
        } catch (e: ApiException) {
            // 401/403：尝试用 session 刷新
            if ((e.status == 401 || e.status == 403) && !sessionToken.isNullOrBlank()) {
                val refreshed = refreshAccessToken(sessionToken, client)
                if (!refreshed.isNullOrBlank()) {
                    onRefreshed(refreshed)
                    token = refreshed
                    try {
                        runOnce(token, request, onEvent, client)
                        return
                    } catch (e2: Exception) {
                        onEvent(Event.Error(describe(e2), (e2 as? ApiException)?.status))
                        return
                    }
                }
            }
            onEvent(Event.Error(e.message ?: "请求失败", e.status))
        } catch (e: Exception) {
            onEvent(Event.Error(describe(e), null))
        }
    }

    private fun runOnce(
        token: String,
        request: ConversationRequest,
        onEvent: (Event) -> Unit,
        client: OkHttpClient
    ) {
        val payload = buildBody(request)
        val http = Request.Builder()
            .url("$WEB_BASE/backend-api/conversation")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", UA)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .header("Origin", WEB_BASE)
            .header("Referer", "$WEB_BASE/")
            .header("OpenAI-Build-ID", UUID.randomUUID().toString())
            .post(RequestBody.create(JSON, payload))
            .build()

        client.newCall(http).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty().take(400)
                throw ApiException(friendlyError(resp.code, errBody), resp.code)
            }
            val source = resp.body?.source() ?: throw ApiException("empty body")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val obj = try {
                    JSONObject(data)
                } catch (ignored: Exception) {
                    continue
                }
                // 错误
                val err = obj.optJSONObject("error")
                if (err != null) {
                    throw ApiException(err.optString("message", "接口返回错误"), resp.code)
                }
                // 会话 id
                obj.optString("conversation_id").takeIf { it.isNotBlank() }?.let {
                    onEvent(Event.ConvId(it))
                }
                val message = obj.optJSONObject("message") ?: continue
                val content = message.optJSONObject("content")
                val parts = content?.optJSONArray("parts") ?: continue
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.opt(i)
                    if (p is String) sb.append(p)
                }
                val endTurn = message.optBoolean("end_turn", false)
                val author = message.optJSONObject("author")?.optString("role") ?: "assistant"
                if (author != "assistant") continue
                if (endTurn) {
                    if (sb.isNotEmpty()) onEvent(Event.Final(sb.toString()))
                    onEvent(Event.Done)
                    return
                } else if (sb.isNotEmpty()) {
                    onEvent(Event.Delta(sb.toString()))
                }
            }
            onEvent(Event.Done)
        }
    }

    /** 用 session cookie 换取新 accessToken */
    private fun refreshAccessToken(sessionToken: String, client: OkHttpClient): String? {
        return runCatching {
            val req = Request.Builder()
                .url("$WEB_BASE/api/auth/session")
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Cookie", "__Secure-next-auth.session-token=$sessionToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optString("accessToken").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 组装 backend-api/conversation 请求体（逆向自网页版协议） */
    private fun buildBody(request: ConversationRequest): String {
        val obj = JSONObject()
        obj.put("action", "next")
        obj.put("parent_message_id", UUID.randomUUID().toString())
        obj.put("websocket_request_id", UUID.randomUUID().toString())
        obj.put("model", request.model)
        obj.put("timezone_offset_min", -480)
        obj.put("history_and_training_disabled", request.historyAndTrainingDisabled)
        obj.put("conversation_mode", JSONObject().put("kind", "primary_assistant"))
        obj.put("force_paragen", false)
        obj.put("force_rate_limit", false)
        if (request.model.startsWith("o")) {
            obj.put("reasoning", JSONObject().put("effort", "medium"))
        }
        request.conversationId?.let { obj.put("conversation_id", it) }

        // 合并 system 提示词到紧随其后的 user 消息（后端不直接支持 system 角色）
        val texts = ArrayList<Pair<String, String>>()
        var sys = StringBuilder()
        for (m in request.messages) {
            when (m.role) {
                "system" -> {
                    if (sys.isNotEmpty()) sys.append("\n")
                    sys.append(m.content)
                }
                else -> {
                    val content = if (m.role == "user" && sys.isNotEmpty()) {
                        val s = sys.toString()
                        sys = StringBuilder()
                        "[System Instructions]\n$s\n\n${m.content}"
                    } else m.content
                    texts.add(m.role to content)
                }
            }
        }
        if (sys.isNotEmpty() && texts.isNotEmpty()) {
            val (r, c) = texts[texts.size - 1]
            texts[texts.size - 1] = r to c + "\n\n[System Instructions]\n$sys"
        }

        val arr = JSONArray()
        for ((role, content) in texts) {
            val msg = JSONObject()
            msg.put("id", UUID.randomUUID().toString())
            msg.put("author", JSONObject().put("role", if (role == "user") "user" else "assistant"))
            msg.put("content", JSONObject()
                .put("content_type", "text")
                .put("parts", JSONArray().put(content)))
            msg.put("metadata", JSONObject())
            arr.put(msg)
        }
        obj.put("messages", arr)
        return obj.toString()
    }

    private fun friendlyError(code: Int, body: String): String {
        val detail = body.take(160).replace('\n', ' ')
        return when (code) {
            401 -> "登录失效（401）：Access Token 无效或已过期，请重新登录/刷新令牌。$detail"
            403 -> "访问被拒绝（403）：令牌权限不足或触发风控，请重新获取令牌后重试。$detail"
            429 -> "请求过于频繁（429）：免费额度限流，请稍后再试。$detail"
            else -> "请求失败（HTTP $code）：$detail"
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "网络超时，请检查网络后重试"
        is java.io.IOException -> "网络错误：${e.message}"
        is ApiException -> e.message ?: "请求失败"
        else -> "异常：${e.message}"
    }
}