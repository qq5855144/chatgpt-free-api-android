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
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sb = StringBuilder()

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

    /** ① 网络连通性：探测 chatgpt.com（到达 CDN 即视为网络可达，403 属风控挡页而非断网） */
    private fun testNet() {
        try {
            val req = Request.Builder().url("https://chatgpt.com/cdn-cgi/trace").get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty().take(200)
                log("✓ 已连通 chatgpt.com，HTTP ${resp.code}")
                if (body.isNotBlank()) log("响应片段：${body.replace('\n', ' ')}")
            }
        } catch (e: Exception) {
            log("✗ 无法访问 chatgpt.com：${e.message}")
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
            log("✓ accessToken 有效，账号可用模型 ${models.size} 个：")
            log(models.take(20).joinToString("、"))
            if (models.isEmpty()) log("（模型列表为空，可能为受限账号）")
        } catch (e: Exception) {
            log("✗ 令牌校验失败：${e.message}")
            log("  提示：可尝试到「账号」页重新登录，或粘贴新令牌")
        }
    }

    // ---------- 本地代理测试 ----------

    private fun proxyBase(): String {
        val running = ProxyService.isRunning()
        val port = Prefs.port(requireContext())
        if (!running) {
            log("✗ 代理服务未运行，请先点击「启动代理」")
            throw IllegalStateException("proxy not running")
        }
        return "http://127.0.0.1:$port"
    }

    private fun getJson(url: String): String {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(200)}")
            return body
        }
    }

    /** ③ 代理健康检查 GET /health */
    private fun testHealth() {
        val body = getJson("${proxyBase()}/health")
        log("✓ GET /health → $body")
    }

    /** ④ 代理模型列表 GET /v1/models */
    private fun testModels() {
        val body = getJson("${proxyBase()}/v1/models")
        val j = JSONObject(body)
        val data = j.optJSONArray("data") ?: JSONArray()
        log("✓ GET /v1/models → ${data.length()} 个模型：")
        val ids = (0 until data.length()).map { data.optJSONObject(it)?.optString("id") ?: "" }
        log(ids.joinToString("、"))
    }

    /** ⑤⑥ OpenAI 兼容对话（走本地代理全链路），stream 可选 */
    private fun testChat(stream: Boolean) {
        val base = proxyBase()
        val model = Prefs.model(requireContext())
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("stream", stream)
        payload.put("messages", org.json.JSONArray().put(
            JSONObject().put("role", "user").put("content", "这是一条连通性自测消息，请只回复：PONG")
        ))
        val body = RequestBody.create("application/json; charset=utf-8".toMediaType(), payload.toString())
        val req = Request.Builder()
            .url("$base/v1/chat/completions")
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

    /** 一键全链路自测：网络 → 令牌 → （自动拉起代理）→ 健康 → 模型 → 非流式 → 流式 */
    private fun fullTest() {
        val ctx = requireContext()
        if (!TokenStore.isLoggedIn(ctx)) {
            toast("请先到「账号」页登录 ChatGPT，再进行全链路自测")
            return
        }
        if (!ProxyService.isRunning()) {
            queue("0 启动代理") {
                log("[全链路] 代理未运行，自动启动…")
                ProxyService.start(ctx)
                // 等待前台服务完成 NanoHTTPD 启动（异步，留足时间）
                var waited = 0
                while (!ProxyService.isRunning() && waited < 8000) {
                    Thread.sleep(300)
                    waited += 300
                }
                if (!ProxyService.isRunning()) log("✗ 代理启动超时，请查看「API 服务」页日志")
                else log("✓ 代理已就绪 (127.0.0.1:${Prefs.port(ctx)})")
            }
        }
        queue("1 网络连通性") { testNet() }
        queue("2 令牌校验") { testToken() }
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