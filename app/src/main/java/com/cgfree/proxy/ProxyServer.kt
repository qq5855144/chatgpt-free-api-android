package com.cgfree.proxy

import android.content.Context
import com.cgfree.data.ChatMsg
import com.cgfree.data.ConversationRequest
import com.cgfree.data.ModelConst
import com.cgfree.data.Prefs
import com.cgfree.data.TokenStore
import com.cgfree.net.ChatGPTClient
import com.cgfree.net.WebViewChatEngine
import com.cgfree.util.LogBuffer
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val MAX_REQUEST_BODY_BYTES = 4 * 1024 * 1024
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
            path == "/__diag" -> diag()
            path == "/__state" -> engineState()
            path == "/__log" -> engineLog()
            path == "/v1/models" -> models()
            path == "/v1/chat/completions" && method == "POST" -> completions(session)
            path == "/v1/chat/completions" -> jsonError(405, "method not allowed")
            else -> jsonError(404, "not found: $path")
        }
    }
    // ---------- 基础端点 ----------
    /** 页面诊断：返回隐藏 WebView 的真实状态（URL/输入框/节点/错误元素），供调试定位 */
    private fun diag(): Response {
        val j = try {
            JSONObject(WebViewChatEngine.diag())
        } catch (e: Exception) {
            JSONObject().put("error", "diag failed: ${e.message}")
        }
        return json(200, j)
    }
    /** 引擎内部状态（running/队列/pageReady），供调试定位会话排队/卡死 */
    private fun engineState(): Response = json(200, runCatching { JSONObject(WebViewChatEngine.state()) }.getOrElse { JSONObject().put("error", it.message) })
    /** LogBuffer 尾部快照（引擎超时/回退/僵尸等 LogBuffer-only 日志），供调试定位 */
    private fun engineLog(): Response {
        val lines = com.cgfree.util.LogBuffer.snapshot().lines().takeLast(120)
        val j = JSONObject().put("lines", JSONArray().apply { lines.forEach { put(it) } })
        return json(200, j)
    }

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

        val raw = readBody(session)
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
            if ('\uFFFD' in content) {
                return jsonError(400, "请求消息包含乱码替换字符（�）：请确认聊天客户端以 UTF-8 发送 JSON")
            }
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

    /**
     * 严格按 HTTP 字节读取 JSON，再按声明/BOM/内容自动识别字符集。
     *
     * NanoHTTPD parseBody() 会依据 Content-Type 中声明的 charset 转成 String；部分 OpenAI
     * 客户端只发送 application/json 而不附 charset，中文可能先被错误解码成 �。这里按
     * Content-Length 精确读取（不会等待 keep-alive 连接 EOF），再兼容 UTF-8、UTF-16
     * 与 GB18030，从源头保留中文与 emoji。
     */
    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length != null) {
            if (length <= 0L) return ""
            if (length > MAX_REQUEST_BODY_BYTES) {
                throw IllegalArgumentException("请求体过大：$length bytes（上限 $MAX_REQUEST_BODY_BYTES）")
            }
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = session.inputStream.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                if (count == 0) continue
                offset += count
            }
            if (offset != bytes.size) {
                LogBuffer.log("请求体不完整：Content-Length=${bytes.size}，实际读取=$offset")
            }
            return decodeJsonBody(bytes.copyOf(offset), session.headers["content-type"])
        }

        // HTTP/1.1 chunked 请求没有 Content-Length；NanoHTTPD 已负责解除分块，按 EOF
        // 读取并设置大小上限。普通 keep-alive 请求不会走到这个分支。
        if (session.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = session.inputStream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (out.size() + count > MAX_REQUEST_BODY_BYTES) {
                    throw IllegalArgumentException("请求体过大（上限 $MAX_REQUEST_BODY_BYTES bytes）")
                }
                out.write(buffer, 0, count)
            }
            return decodeJsonBody(out.toByteArray(), session.headers["content-type"])
        }

        // 极少数非标准客户端既不发 Content-Length 也不使用 chunked，保留兼容回退。
        val files = java.util.HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            LogBuffer.log("请求体解析失败: ${e.message}")
            return ""
        }
        return files["postData"] ?: ""
    }

    /**
     * 按 JSON 有效性自动识别请求字符集。标准客户端会命中 UTF-8；部分 Android/本地
     * 客户端错误地用 GBK/GB18030 或 UTF-16 编码且没有声明 charset，也能在这里恢复。
     */
    private fun decodeJsonBody(bytes: ByteArray, contentType: String?): String {
        if (bytes.isEmpty()) return ""

        val declared = Regex("charset\\s*=\\s*[\\\"']?([^;\\\"'\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }

        val bomCharset = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> null
        }

        val candidates = linkedSetOf<Charset>()
        declared?.let { candidates.add(it) }
        bomCharset?.let { candidates.add(it) }
        candidates.add(Charsets.UTF_8)
        candidates.add(Charsets.UTF_16LE)
        candidates.add(Charsets.UTF_16BE)
        runCatching { Charset.forName("GB18030") }.getOrNull()?.let { candidates.add(it) }

        for (charset in candidates) {
            val decoded = decodeStrict(bytes, charset) ?: continue
            val normalized = decoded.removePrefix("\uFEFF").trim()
            if (runCatching { JSONObject(normalized) }.isSuccess) {
                LogBuffer.log("请求体字符集: ${charset.name()}${if (declared != null) "（声明 ${declared.name()}）" else "（自动识别）"}")
                return normalized
            }
        }

        // 保留可诊断结果：调用方会检测 U+FFFD 并返回明确的 400，而不是传给 AI。
        LogBuffer.log("请求体字符集识别失败 contentType=${contentType.orEmpty().take(80)} bytes=${bytes.size}")
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (ignored: CharacterCodingException) {
        null
    }

    /** OpenAI content 可能是字符串或分段数组（忽略图片等多模态内容） */
    private fun extractText(content: Any?): String? = when (content) {
        is String -> if (content.isBlank()) null else content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.opt(i)
                if (part is String) sb.append(part)
                else if (part is JSONObject) {
                    when (part.optString("type")) {
                        "text", "input_text", "output_text" -> sb.append(part.optString("text"))
                    }
                }
            }
            sb.toString().takeIf { it.isNotBlank() }
        }
        else -> null
    }

    // ---------- SSE 流式响应 ----------

    /**
     * 上游对话统一入口：优先走 WebView 指纹通道（真实 Chromium 网络栈，
     * UA/TLS/HTTP2/Cookie 与浏览器一致，可绕过 OpenAI 设备指纹风控）；
     * 引擎不可用（初始化失败/页面超时）时自动回退 OkHttp 通道。
     */
    private fun upstreamConversation(
        token: String,
        request: ConversationRequest,
        onEvent: (ChatGPTClient.Event) -> Unit
    ) {
        val engineOk = try {
            WebViewChatEngine.chatBlocking(
                appContext, request,
                onToken = { newTok ->
                    if (newTok.isNotBlank()) {
                        TokenStore.saveAccessToken(appContext, newTok)
                        LogBuffer.log("WebView 通道已自动刷新 accessToken")
                    }
                },
                onEvent = onEvent,
                // UI 自动化优先：真实操作页面输入框+发送按钮，由 OpenAI 页面 JS 完成全部
                // 风控（裸 fetch 已被证实 403）；引擎不可用/超时由 chatBlocking 返回 false 回退
                useUi = true
            )
        } catch (e: Exception) {
            LogBuffer.log("WebView 通道异常，回退 OkHttp: ${e.message}")
            false
        }
        if (!engineOk) {
            LogBuffer.log("使用 OkHttp 通道（WebView 引擎不可用）")
            ChatGPTClient.streamConversation(
                token,
                TokenStore.getSessionToken(appContext),
                TokenStore.getCookie(appContext),
                request,
                onRefreshed = { newTok -> TokenStore.saveAccessToken(appContext, newTok) },
                onEvent = onEvent
            )
        }
    }

    private fun streamResponse(token: String, request: ConversationRequest): Response {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        val created = System.currentTimeMillis() / 1000
        val acc = com.cgfree.util.TextAccumulator()
        val t0 = System.currentTimeMillis()

        // Piped 管道：生产者线程写 OpenAI SSE 块，NanoHTTPD newChunkedResponse 流式发送。
        // 256KB 缓冲：WebView 通道的事件在主线程回调写入管道，缓冲越大越不易阻塞主线程
        val pos = PipedOutputStream()
        val pis = PipedInputStream(pos, 256 * 1024)
        val producerDone = AtomicBoolean(false)

        fun writeSse(text: String) {
            synchronized(pos) {
                pos.write(text.toByteArray(Charsets.UTF_8))
                pos.flush()
            }
        }

        val producer = Thread({
            try {
                // 立即写出首个 SSE 块，让 NanoHTTPD 立刻发送响应头。此前只有上游返回
                // 第一段文字后才写管道，WebView 初始化/页面生成期间客户端会误以为连接卡死。
                writeSse(sseChunk(id, created, request.model, role = "assistant", content = ""))
                var terminalError = false
                val handle: (ChatGPTClient.Event) -> Unit = { ev ->
                    when (ev) {
                        is ChatGPTClient.Event.Delta -> {
                            acc.push(ev.text) { delta ->
                                writeSse(sseChunk(id, created, request.model, content = delta))
                            }
                        }
                        is ChatGPTClient.Event.Final -> {
                            acc.push(ev.text) { delta ->
                                writeSse(sseChunk(id, created, request.model, content = delta))
                            }
                        }
                        is ChatGPTClient.Event.ConvId -> { /* 忽略 */ }
                        is ChatGPTClient.Event.Error -> {
                            terminalError = true
                            LogBuffer.log("stream 上游错误: ${ev.message}")
                            writeSse(sseErrorChunk(id, created, request.model, ev.message))
                        }
                        ChatGPTClient.Event.Done -> { /* 由流结束处理 */ }
                    }
                }
                // WebView 指纹通道优先，引擎不可用时内部回退 OkHttp
                upstreamConversation(token, request, handle)
                if (!terminalError) writeSse(sseFinishChunk(id, created, request.model))
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
                producerDone.set(true)
                try {
                    pos.close()
                } catch (ignored: Exception) {
                }
            }
        }, "cgfree-producer")
        producer.isDaemon = true
        producer.start()

        // 上游首字可能需要较长时间；SSE 注释不会影响 OpenAI 客户端解析，
        // 但能阻止反向代理、HTTP 库或系统网络层把安静连接误判为超时。
        val heartbeat = Thread({
            try {
                while (!producerDone.get()) {
                    Thread.sleep(10_000)
                    if (!producerDone.get()) writeSse(": keep-alive\n\n")
                }
            } catch (ignored: Exception) {
                // 客户端断开或 producer 已关闭管道，心跳线程自然结束。
            }
        }, "cgfree-sse-heartbeat")
        heartbeat.isDaemon = true
        heartbeat.start()

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
            // WebView 指纹通道优先，引擎不可用时内部回退 OkHttp
            upstreamConversation(token, request) { ev ->
                when (ev) {
                    is ChatGPTClient.Event.Delta -> acc.push(ev.text) { sb.append(it) }
                    is ChatGPTClient.Event.Final -> acc.push(ev.text) { sb.append(it) }
                    is ChatGPTClient.Event.Error -> error = ev.message
                    else -> { /* ignore */ }
                }
            }
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
        if (text.isBlank()) {
            LogBuffer.log("sync 上游返回空内容 model=${request.model}")
            val j = JSONObject().put("error", JSONObject()
                .put("message", "上游未返回任何回复内容，请检查登录状态或页面验证后重试")
                .put("type", "upstream_empty_response"))
            return json(502, j)
        }
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
