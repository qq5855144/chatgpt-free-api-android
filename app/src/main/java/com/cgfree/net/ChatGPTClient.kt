package com.cgfree.net

import com.cgfree.data.ConversationRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 设备标识：模拟网页版 oai-device-id（进程内稳定，配合 cookie 通过设备风控检查） */
    @Volatile
    private var deviceId: String? = null
    private fun deviceId(): String {
        if (deviceId == null) deviceId = UUID.randomUUID().toString()
        return deviceId!!
    }

    /** 组装模拟网页版的浏览器特征头（Cookie 优先用 WebView 登录的完整串，回退 session-token 拼装 + sec-ch/sec-fetch 系列） */
    private fun Request.Builder.browserHeaders(sessionToken: String?, cookie: String?): Request.Builder {
        header("User-Agent", UA)
        header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
        header("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
        header("sec-ch-ua-mobile", "?0")
        header("sec-ch-ua-platform", "\"Windows\"")
        header("sec-fetch-dest", "empty")
        header("sec-fetch-mode", "cors")
        header("sec-fetch-site", "same-origin")
        header("priority", "u=1, i")
        header("oai-device-id", deviceId())
        val ck = cookie?.takeIf { it.isNotBlank() }
            ?: sessionToken?.let { "__Secure-next-auth.session-token=$it; oai-did=${deviceId()}" }
        if (ck != null) header("Cookie", ck)
        return this
    }

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
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    /** HTTP/1.1 专用客户端（同超时）：部分中间网络对 HTTP/2 SSE 长连接处理异常（黑洞/掐断），降级 1.1 可绕开 */
    fun newHttp11Client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /** 拉取当前账号可用模型列表 */
    fun fetchModels(
        accessToken: String,
        client: OkHttpClient = newClient(),
        cookie: String? = null,
        sessionToken: String? = null
    ): List<String> {
        val req = Request.Builder()
            .url("$WEB_BASE/backend-api/models")
            .header("Authorization", "Bearer $accessToken")
            .browserHeaders(sessionToken, cookie)
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
     * @param cookie 网页登录的完整 Cookie（WebView 登录时自动保存；缺失则回退 session-token 拼装）
     * @param onRefreshed 401 后成功用 sessionToken 刷新出新 accessToken 时回调（调用方负责持久化）
     */
    fun streamConversation(
        accessToken: String,
        sessionToken: String?,
        cookie: String?,
        request: ConversationRequest,
        onRefreshed: (String) -> Unit,
        onEvent: (Event) -> Unit,
        client: OkHttpClient = newClient()
    ) {
        var token = accessToken
        // 已向上游发出过任何内容事件则不再重试（避免重复输出）
        var emitted = false
        val guard = { ev: Event ->
            if (ev is Event.Delta || ev is Event.Final) emitted = true
            onEvent(ev)
        }
        try {
            runOnce(token, sessionToken, cookie, request, guard, client)
        } catch (e: ApiException) {
            // 401/403：尝试用 session 刷新
            if ((e.status == 401 || e.status == 403) && !sessionToken.isNullOrBlank()) {
                val refreshed = refreshAccessToken(sessionToken, cookie, client)
                if (!refreshed.isNullOrBlank()) {
                    onRefreshed(refreshed)
                    token = refreshed
                    try {
                        runOnce(token, sessionToken, cookie, request, guard, client)
                        return
                    } catch (e2: Exception) {
                        guard(Event.Error(describe(e2), (e2 as? ApiException)?.status))
                        return
                    }
                }
            }
            guard(Event.Error(e.message ?: "请求失败", e.status))
        } catch (e: Exception) {
            // 瞬时网络异常（上游偶发挂起/断流）且尚未输出任何内容：静默重试一次
            // 第二次尝试强制降级 HTTP/1.1——部分中间网络对 HTTP/2 SSE 长连接处理异常（黑洞），1.1 可绕开
            val retryable = !emitted && (e is java.net.SocketTimeoutException || e is java.io.IOException)
            if (retryable) {
                com.cgfree.util.LogBuffer.log("上游无响应（${e.message}），1.5s 后降级 HTTP/1.1 重试…")
                try {
                    Thread.sleep(1500)
                    val retryClient = if (client.protocols.contains(Protocol.HTTP_2)) newHttp11Client() else client
                    runOnce(token, sessionToken, cookie, request, guard, retryClient)
                    return
                } catch (e2: Exception) {
                    guard(Event.Error(describe(e2), (e2 as? ApiException)?.status))
                    return
                }
            }
            guard(Event.Error(describe(e), null))
        }
    }

    private fun runOnce(
        token: String,
        sessionToken: String?,
        cookie: String?,
        request: ConversationRequest,
        onEvent: (Event) -> Unit,
        client: OkHttpClient
    ) {
        val payload = buildBody(request)
        val http = Request.Builder()
            .url("$WEB_BASE/backend-api/conversation")
            .header("Authorization", "Bearer $token")
            .browserHeaders(sessionToken, cookie)
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
    private fun refreshAccessToken(sessionToken: String, cookie: String?, client: OkHttpClient): String? {
        return runCatching {
            val req = Request.Builder()
                .url("$WEB_BASE/api/auth/session")
                .header("Accept", "application/json")
                .browserHeaders(sessionToken, cookie)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                JSONObject(body).optString("accessToken").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 组装 backend-api/conversation 请求体（逆向自网页版协议）；internal 供 WebView 指纹通道复用 */
    internal fun buildBody(request: ConversationRequest): String {
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
        val detail = body.take(200).replace('\n', ' ')
        val unusual = detail.contains("Unusual activity", ignoreCase = true)
        return when {
            code == 401 -> "登录失效（401）：Access Token 无效或已过期，请重新登录/刷新令牌。$detail"
            code == 403 && unusual ->
                "触发设备/IP 风控（403 Unusual activity）：OpenAI 检测到当前设备或网络异常。建议：① 更换网络（切换 Wi-Fi/热点/VPN 节点）后重试；② 用浏览器打开 chatgpt.com 确认能否正常对话；③ 等 10-30 分钟冷却。$detail"
            code == 403 -> "访问被拒绝（403）：令牌权限不足或触发风控，请重新获取令牌后重试。$detail"
            code == 429 -> "请求过于频繁（429）：免费额度限流，请稍后再试。$detail"
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