package com.cgfree.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cgfree.data.Prefs
import com.cgfree.data.TokenStore
import com.cgfree.databinding.FragmentDebugBinding
import com.cgfree.net.ChatGPTClient
import com.cgfree.service.ProxyService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 调试页：安装后实时功能自测入口。
 *
 * 单项/全链路验证顺序：网络连通性 → 上游令牌(模型列表) → 本地代理健康/模型 →
 * OpenAI 兼容对话（非流式 + 流式 SSE），全部真实走 HTTP，便于定位问题环节。
 */
class DebugFragment : Fragment() {

    private var _b: FragmentDebugBinding? = null
    private val b get() = _b!!
    private val handler = Handler(Looper.getMainLooper())

    /** 串行执行队列：多次点击排队执行，避免并发请求互相干扰 */
    private val worker = Executors.newSingleThreadExecutor()

    /** 本地调试用短超时客户端（避免单项测试长时间挂起） */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sb = StringBuilder()

    /** 网络探测专用短超时客户端（避免单端点卡 45s） */
    private val netHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 账号真实可用模型缓存（fetchModels 一次成功即复用，供对话测试选真实模型） */
    private var cachedModels: List<String>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentDebugBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.btnFullTest.setOnClickListener { fullTest() }
        b.btnProxyToggle.setOnClickListener { toggleProxy() }
        b.btnNet.setOnClickListener { queue("网络连通性") { testNet() } }
        b.btnToken.setOnClickListener { queue("令牌校验") { testToken() } }
        b.btnHealth.setOnClickListener { queue("代理健康检查") { testHealth() } }
        b.btnModels.setOnClickListener { queue("代理模型列表") { testModels() } }
        b.btnChatSync.setOnClickListener { queue("对话·非流式") { testChat(stream = false) } }
        b.btnChatStream.setOnClickListener { queue("对话·流式") { testChat(stream = true) } }
        b.btnCopyLog.setOnClickListener { copyLog() }
        b.btnClearLog.setOnClickListener { clearLog() }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- UI 辅助 ----------

    private fun refreshStatus() {
        val ctx = requireContext()
        val acc = TokenStore.getAccessToken(ctx)
        val sess = TokenStore.getSessionToken(ctx)
        val email = TokenStore.getEmail(ctx)
        val running = ProxyService.isRunning()
        val port = Prefs.port(ctx)
        val loginState = if (!acc.isNullOrBlank()) {
            "已登录${if (!email.isNullOrBlank()) "（$email）" else ""}"
        } else "未登录（请先到「账号」页登录/粘贴令牌）"
        b.debugStatus.text = buildString {
            append("登录：").append(loginState).append('\n')
            append("accessToken：").append(mask(acc)).append('\n')
            append("sessionToken：").append(mask(sess)).append('\n')
            append("代理服务：").append(if (running) "运行中 (127.0.0.1:$port)" else "未运行").append('\n')
            append("API Key：").append(Prefs.apiKeyOrDefault(ctx)).append('\n')
            append("模型：").append(Prefs.model(ctx))
        }
        b.btnProxyToggle.text = if (running) "停止代理" else "启动代理"
    }

    private fun mask(t: String?): String {
        if (t.isNullOrBlank()) return "（无）"
        return if (t.length <= 10) "***（过短，可能无效）" else "${t.take(8)}…${t.takeLast(4)}（长度 ${t.length}）"
    }

    private fun log(line: String) {
        sb.append(line).append('\n')
        handler.post {
            if (_b == null) return@post
            b.logView.text = sb.toString()
            b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        sb.clear()
        b.logView.text = ""
    }

    private fun copyLog() {
        if (sb.isEmpty()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("debug", sb.toString()))
        Toast.makeText(requireContext(), "已复制测试输出", Toast.LENGTH_SHORT).show()
    }

    /** 排入串行队列执行一个测试（带标题与耗时统计） */
    private fun queue(title: String, block: () -> Unit) {
        worker.execute {
            val t0 = System.currentTimeMillis()
            log("========== [$title] ==========")
            try {
                block()
            } catch (e: Exception) {
                log("✗ 异常：${e.message ?: e.javaClass.simpleName}")
            } finally {
                log("---------- [$title] 完成，耗时 ${(System.currentTimeMillis() - t0) / 1000}s ----------")
                handler.post { refreshStatus() }
            }
        }
    }

    // ---------- 代理开关 ----------

    private fun toggleProxy() {
        val ctx = requireContext()
        if (ProxyService.isRunning()) {
            ProxyService.stop(ctx)
            log("[代理] 已发送停止指令")
        } else {
            if (!TokenStore.isLoggedIn(ctx)) {
                toast("请先到「账号」页登录 ChatGPT 再启动代理")
                return
            }
            ProxyService.start(ctx)
            log("[代理] 已发送启动指令 (127.0.0.1:${Prefs.port(ctx)})，等待前台服务就绪…")
        }
        handler.postDelayed({ refreshStatus() }, 800)
    }

    // ---------- 上游测试 ----------

    /** ① 网络连通性：优先探测真实业务端点 backend-api（无 token 时预期 401/403 = 网络通），
     *  cdn-cgi/trace 仅作兜底（该端点可能被部分网络环境限速导致误报） */
    private fun testNet() {
        // 探测 1：业务端点（与令牌校验同路径，最贴近真实使用）
        try {
            val req = Request.Builder().url("${ChatGPTClient.WEB_BASE}/backend-api/models").get().build()
            netHttp.newCall(req).execute().use { resp ->
                // 无 token 访问预期 401/403，只要 HTTP 响应到达即代表网络连通
                log("✓ 已连通 chatgpt.com（backend-api 可达，HTTP ${resp.code}）")
                return
            }
        } catch (e: Exception) {
            log("✗ backend-api 探测失败：${e.message}")
        }
        // 探测 2：兜底 cdn-cgi/trace
        try {
            val req = Request.Builder().url("${ChatGPTClient.WEB_BASE}/cdn-cgi/trace").get().build()
            netHttp.newCall(req).execute().use { resp ->
                log("✓ cdn-cgi/trace 可达（HTTP ${resp.code}）")
                return
            }
        } catch (e: Exception) {
            log("✗ chatgpt.com 无法访问：${e.message}")
            log("  提示：当前网络可能无法直连 ChatGPT，请检查网络/代理/VPN 后重试")
        }
    }

    /** ② 令牌有效性：拉取账号可用模型列表 */
    private fun testToken() {
        val ctx = requireContext()
        val token = TokenStore.getAccessToken(ctx)
        if (token.isNullOrBlank()) {
            log("✗ 未保存 accessToken，请先登录")
            return
        }
        try {
            val models = ChatGPTClient.fetchModels(token, http)
            cachedModels = models
            log("✓ accessToken 有效，账号可用模型 ${models.size} 个：")
            log(models.take(20).joinToString("、"))
            if (models.isEmpty()) log("（模型列表为空，可能为受限账号）")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("timeout", ignoreCase = true) || msg.contains("超时", ignoreCase = true)) {
                log("✗ 令牌校验超时（上游 /backend-api/models 偶发极慢）")
                log("  提示：令牌本身可能有效，可稍后重试本项；连续失败再到「账号」页重新登录/粘贴新令牌")
            } else {
                log("✗ 令牌校验失败：$msg")
                log("  提示：可尝试到「账号」页重新登录，或粘贴新令牌")
            }
        }
    }

    // ---------- 本地代理测试 ----------

    /** 真实探测本地端口是否可连（不依赖静态标志，静态状态可能与系统实际回收不一致） */
    private fun portOpen(port: Int): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 确保代理可用：端口不通则自动（重新）启动并等待就绪。
     * 处理「代理被系统省电/后台回收但 App 进程仍在」的场景——测试前自愈，无需手动去 API 服务页重启。
     */
    private fun ensureProxy(): String {
        val port = Prefs.port(requireContext())
        if (!portOpen(port)) {
            if (ProxyService.isRunning()) {
                log("⚠ 代理实例存在但端口 $port 无响应（可能已被系统回收），尝试重启…")
                ProxyService.stop(requireContext())
                Thread.sleep(500)
            }
            log("→ 自动启动代理 (127.0.0.1:$port)…")
            ProxyService.start(requireContext())
            var waited = 0
            while (!portOpen(port) && waited < 10_000) {
                Thread.sleep(300)
                waited += 300
            }
            if (!portOpen(port)) {
                log("✗ 代理端口 $port 启动超时，请到「API 服务」页查看日志")
                throw IllegalStateException("proxy unavailable")
            }
            log("✓ 代理已就绪 (127.0.0.1:$port)")
        }
        return "http://127.0.0.1:$port"
    }

    private fun getJson(url: String): String {
        val key = Prefs.apiKeyOrDefault(requireContext())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(200)}")
            return body
        }
    }

    /** ③ 代理健康检查 GET /health */
    private fun testHealth() {
        val body = getJson("${ensureProxy()}/health")
        log("✓ GET /health → $body")
    }

    /** ④ 代理模型列表 GET /v1/models */
    private fun testModels() {
        val body = getJson("${ensureProxy()}/v1/models")
        val j = JSONObject(body)
        val data = j.optJSONArray("data") ?: JSONArray()
        log("✓ GET /v1/models → ${data.length()} 个模型：")
        val ids = (0 until data.length()).map { data.optJSONObject(it)?.optString("id") ?: "" }
        log(ids.joinToString("、"))
    }

    /** 对话测试用模型：优先账号真实可用模型（去掉 research/wm 等非普通对话模型），失败回退偏好模型 */
    private fun pickChatModel(): String {
        val models = cachedModels
        if (!models.isNullOrEmpty()) {
            val m = models.firstOrNull {
                !it.contains("research", ignoreCase = true) && !it.endsWith("-wm", ignoreCase = true)
            }
            if (m != null) return m
        }
        val saved = Prefs.model(requireContext())
        log("（使用偏好模型 $saved；若上游提示模型不存在，请先运行「② 令牌·模型列表」拉取真实模型）")
        return saved
    }

    /** ⑤⑥ OpenAI 兼容对话（走本地代理全链路），stream 可选 */
    private fun testChat(stream: Boolean) {
        val base = ensureProxy()
        val model = pickChatModel()
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("stream", stream)
        payload.put("messages", org.json.JSONArray().put(
            JSONObject().put("role", "user").put("content", "这是一条连通性自测消息，请只回复：PONG")
        ))
        val body = RequestBody.create("application/json; charset=utf-8".toMediaType(), payload.toString())
        val key = Prefs.apiKeyOrDefault(requireContext())
        val req = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        val t0 = System.currentTimeMillis()
        log(if (stream) "→ POST /v1/chat/completions（stream=true, model=$model）…" else "→ POST /v1/chat/completions（stream=false, model=$model）…")

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                log("✗ HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
                return
            }
            if (!stream) {
                val text = resp.body?.string().orEmpty()
                val j = runCatching { JSONObject(text) }.getOrNull()
                val content = j?.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content") ?: text.take(300)
                log("✓ 非流式响应（${(System.currentTimeMillis() - t0) / 1000}s）：")
                log(content)
                val usage = j?.optJSONObject("usage")
                if (usage != null) log("usage: ${usage.toString()}")
            } else {
                // 流式：逐行读 SSE data:，拼接 delta
                val source = resp.body?.source() ?: throw RuntimeException("empty body")
                val collected = StringBuilder()
                var chunks = 0
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val j = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val delta = j.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content") ?: ""
                    if (delta.isNotEmpty()) {
                        collected.append(delta)
                        chunks++
                    }
                }
                log("✓ 流式响应（${(System.currentTimeMillis() - t0) / 1000}s，$chunks 个 SSE 块）：")
                log(collected.toString().ifBlank { "（空响应）" })
            }
        }
    }

    /** 一键全链路自测：令牌 → 网络 → 代理(自愈) → 健康 → 模型 → 非流式 → 流式 */
    private fun fullTest() {
        val ctx = requireContext()
        if (!TokenStore.isLoggedIn(ctx)) {
            toast("请先到「账号」页登录 ChatGPT，再进行全链路自测")
            return
        }
        // 后续每个代理测试前都会 ensureProxy() 自愈（端口不通自动重启），这里无需手动预启动
        queue("1 令牌校验") { testToken() }
        queue("2 网络连通性") { testNet() }
        queue("3 代理健康检查") { testHealth() }
        queue("4 代理模型列表") { testModels() }
        queue("5 对话·非流式") { testChat(stream = false) }
        queue("6 对话·流式") { testChat(stream = true) }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}