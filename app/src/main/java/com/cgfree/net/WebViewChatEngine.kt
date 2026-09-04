package com.cgfree.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cgfree.BuildConfig
import com.cgfree.data.ConversationRequest
import com.cgfree.util.LogBuffer
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView 对话引擎：用真实 Chromium 网络栈（WebView）发起 ChatGPT 对话请求。
 *
 * 为什么需要它：
 * OpenAI 风控会交叉核对 UA 与 TLS 指纹（JA3/JA4）一致性。OkHttp（Conscrypt）的 TLS 指纹
 * 与 Chrome 不同，"伪造 Chrome UA + OkHttp TLS" 会被判定为非浏览器 → 403 Unusual activity，
 * 这也是 v1.0.5/v1.0.6 补全请求头与 Cookie 后仍被拦截的原因。
 *
 * WebView 使用完整 Chromium 网络栈：TLS/HTTP2/UA/Cookie 与真浏览器完全一致；每次请求前
 * 自动经 /api/auth/session 取最新 accessToken（与网页版行为一致），因此可绕过设备指纹风控。
 *
 * 用法：代理收到 /v1/chat/completions 时优先调用 [chatBlocking]（线程安全、内部排队串行）；
 * 引擎不可用（初始化失败/页面加载超时）返回 false，调用方回退 OkHttp 通道。
 */
object WebViewChatEngine {

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()

    @Volatile
    private var wv: WebView? = null

    @Volatile
    private var pageReady = false

    @Volatile
    private var initFailed = false

    @Volatile
    private var initStarted = false

    /** 待执行对话队列（仅主线程操作） */
    private val queue = ArrayDeque<Session>()

    /** 正在执行的对话（仅主线程读写） */
    @Volatile
    private var running: Session? = null

    /** diag() 同步等待句柄（JS 结果经 AndroidBridge.onDiag 回填） */
    @Volatile
    private var diagLatch: CountDownLatch? = null

    @Volatile
    private var diagResult: String? = null

    /** 单次对话会话：事件转发 + 阻塞等待 */
    private class Session(
        val request: ConversationRequest,
        val onEvent: (ChatGPTClient.Event) -> Unit,
        val onToken: (String) -> Unit,
        val useUi: Boolean = false
    ) {
        val done = CountDownLatch(1)
        private var finished = false
        /** 会话创建时间（state() 诊断用） */
        val createdAt = System.currentTimeMillis()
        /** pump 已调用 evaluateJavascript（JS 已注入） */
        @Volatile
        var injected = false
        /** 最近一次 JS 信号（onLog/onEvent/onDone/onError）时间戳，用于僵尸引擎检测 */
        @Volatile
        var lastSignal = System.currentTimeMillis()
        fun touch() { lastSignal = System.currentTimeMillis() }
        @Synchronized
        fun isFinished(): Boolean = finished
        /**
         * JS 回调：新的全文快照（SSE message.content.parts，逐事件增长）。
         * 事件语义与 OkHttp 通道一致：Delta 携带全文快照，由消费方用 TextAccumulator 差分出增量。
         */
        @Synchronized
        fun push(snapshot: String) {
            if (finished || snapshot.isEmpty()) return
            touch()
            // 回调异常必须隔离：会经 JS bridge 以 "Java exception" 抛回页面 JS 破坏流程
            runCatching { onEvent(ChatGPTClient.Event.Delta(snapshot)) }
                .onFailure { LogBuffer.log("[Session] push onEvent: ${it.message}") }
        }

        /** JS 回调：事件流结束 */
        @Synchronized
        fun finish() {
            if (finished) return
            finished = true
            touch()
            // 关键：清空 running，否则后续会话入队后 pump 因 running!=null 永不执行（首次成功后全卡死）
            if (running === this) running = null
            try {
                onEvent(ChatGPTClient.Event.Done)
            } catch (e: Throwable) {
                LogBuffer.log("[Session] finish onEvent: ${e.message}")
            } finally {
                done.countDown()
                scheduleNext()
            }
        }
        /** JS 回调：错误 */
        @Synchronized
        fun fail(status: Int?, message: String) {
            if (finished) return
            finished = true
            touch()
            // 同上：清理 running 以放行后续会话
            if (running === this) running = null
            try {
                onEvent(ChatGPTClient.Event.Error(WebViewChatEngine.friendlyError(status, message), status))
            } catch (e: Throwable) {
                LogBuffer.log("[Session] fail onEvent: ${e.message}")
            } finally {
                done.countDown()
                scheduleNext()
            }
        }

        private fun scheduleNext() {
            WebViewChatEngine.main.post { WebViewChatEngine.pump() }
        }
    }

    /**
     * 预热/确保引擎就绪（幂等，任意线程可调）。
     * 创建隐藏 WebView 并加载 chatgpt.com 同源页面（Cookie/会话与登录 WebView 共享进程级 CookieManager）。
     */
    fun ensure(context: Context) {
        if (initStarted || initFailed) return
        synchronized(lock) {
            if (initStarted || initFailed) return
            initStarted = true
        }
        main.post {
            try {
                val w = WebView(context.applicationContext)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.databaseEnabled = true
                w.settings.cacheMode = WebSettings.LOAD_DEFAULT
                // debug 构建开启远程调试（chrome://inspect），并把页面 JS console 转发到 logcat/调试日志
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                w.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        if (msg != null && msg.message().isNotBlank()) {
                            val line = "[JS:${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                            LogBuffer.log(line)
                            Log.i("CGFREE_JS", line)
                        }
                        return true
                    }
                }
                // 去掉 "; wv" 标识，使 UA 与普通 Chrome 完全一致（登录 WebView 同款处理）
                w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
                w.addJavascriptInterface(object {
                    // 所有桥方法必须异常隔离：任何 Java 异常都会以 "Java exception was raised
                    // during method invocation" 抛回页面 JS，破坏对话流程（⑥流式曾因此中断）。
                    private fun safe(tag: String, block: () -> Unit) {
                        try {
                            block()
                        } catch (e: Throwable) {
                            LogBuffer.log("[Bridge:$tag] ${e.message}")
                        }
                    }
                    @JavascriptInterface
                    fun onToken(token: String) = safe("onToken") {
                        val s = running ?: return@safe
                        if (token.isNotBlank()) s.onToken(token)
                    }
                    @JavascriptInterface
                    fun onEvent(snapshot: String) = safe("onEvent") {
                        running?.push(snapshot)
                    }
                    @JavascriptInterface
                    fun onDone() = safe("onDone") {
                        running?.finish()
                    }
                    @JavascriptInterface
                    fun onError(status: Int, message: String) = safe("onError") {
                        running?.fail(if (status == 0) null else status, message)
                    }
                    @JavascriptInterface
                    fun onLog(msg: String) = safe("onLog") {
                        running?.touch()
                        LogBuffer.log("[WV-UI] $msg")
                        Log.i("CGFREE_JS", msg)
                    }
                    @JavascriptInterface
                    fun onDiag(json: String) = safe("onDiag") {
                        diagResult = json
                        diagLatch?.countDown()
                    }
                }, "AndroidBridge")
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        synchronized(lock) {
                            pageReady = true
                            lock.notifyAll()
                        }
                        LogBuffer.log("WebView 引擎就绪: ${url ?: "?"}")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            LogBuffer.log("WebView 引擎首页加载错误 code=${error?.errorCode}（重置就绪标志，等待重试/超时兜底）")
                            Log.i("CGFREE_JS", "page-error code=${error?.errorCode}")
                            synchronized(lock) {
                                pageReady = false
                            }
                        }
                    }
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        LogBuffer.log("WebView 渲染进程已退出（crash=${detail?.didCrash() == true}），销毁引擎待自动重建")
                        Log.i("CGFREE_JS", "renderer-gone crash=${detail?.didCrash() == true}")
                        destroyEngine()
                        return true
                    }
                }
                wv = w
                w.loadUrl("https://chatgpt.com/")
            } catch (e: Throwable) {
                LogBuffer.log("WebView 引擎初始化失败: ${e.message}")
                synchronized(lock) {
                    initFailed = true
                    lock.notifyAll()
                }
            }
        }
    }

    /** 等待首页加载完成（onPageFinished），超时返回 false */
    private fun waitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!pageReady && !initFailed && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(200)
                } catch (e: InterruptedException) {
                    return false
                }
            }
            return pageReady
        }
    }

    /**
     * 页面诊断：查询隐藏 WebView 的真实状态（URL/输入框/assistant 节点/发送按钮/错误元素等），
     * 主线程 evaluateJavascript 执行，阻塞至多 8s。结果 JSON 字符串（失败返回 error 字段）。
     * 供代理 /__diag 端点调试用，定位"UI 自动化输入/发送无效"时页面到底处于什么状态。
     */
    fun diag(): String {
        val l = CountDownLatch(1)
        diagLatch = l
        diagResult = null
        main.post {
            val w = wv
            if (w == null) {
                diagResult = "{\"error\":\"wv is null (engine not initialized)\"}"
                l.countDown()
                return@post
            }
            val js = """(function(){
var r = {};
try { r.url = location.href; } catch(e) { r.url = '?'; }
try { r.title = document.title; } catch(e) {}
try {
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/api/auth/session', false);
  xhr.send();
  r.sessionStatus = xhr.status;
  r.session = (xhr.responseText || '').slice(0, 300);
} catch(e) { r.session = 'xhr-err ' + (e.message || e); }
try {
  var inp = document.querySelector('textarea#prompt-textarea') || document.querySelector('div#prompt-textarea') || document.querySelector('[contenteditable="true"]');
  r.input = inp ? (inp.tagName + '#' + (inp.id || '') + ' textLen=' + ((inp.innerText || inp.value || '').length)) : 'none';
} catch(e) { r.input = 'err'; }
try {
  var nodes = document.querySelectorAll('[data-message-author-role="assistant"]');
  r.assistantCount = nodes.length;
  r.lastAssistant = nodes.length ? String(nodes[nodes.length - 1].innerText || '').slice(0, 300) : '';
} catch(e) { r.assistantCount = -1; }
try {
  var sb = document.querySelector('button[data-testid="send-button"]');
  r.sendBtn = sb ? 'found' : 'missing';
  r.sendDisabled = sb ? sb.disabled : null;
  r.stopBtn = !!document.querySelector('button[data-testid="stop-button"]');
} catch(e) {}
try {
  var err = document.querySelector('[role="alert"], [class*="error" i], [class*="Error"]');
  r.errorEl = err ? String(err.innerText || err.textContent || '').slice(0, 200) : '';
} catch(e) {}
try { r.bodyHead = document.body ? document.body.innerText.slice(0, 400) : ''; } catch(e) {}
AndroidBridge.onDiag(JSON.stringify(r));
})()"""
            try {
                w.evaluateJavascript(js, null)
            } catch (e: Exception) {
                diagResult = "{\"error\":\"evaluateJavascript: ${e.message}\"}"
                l.countDown()
            }
        }
        try {
            l.await(8, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // ignore
        }
        val raw = diagResult ?: "{\"error\":\"diag timeout (8s)\"}"
        // 附加 chatgpt.com cookie 存在性（仅长度，不泄露内容），判断 WebView 会话是否真实落库
        val cookieLen = try {
            android.webkit.CookieManager.getInstance().getCookie("https://chatgpt.com")?.length ?: -1
        } catch (e: Exception) {
            -2
        }
        return try {
            JSONObject(raw).put("cookieLen", cookieLen).toString()
        } catch (e: Exception) {
            raw
        }
    }

    /** 引擎内部状态快照（running/队列/pageReady/心跳等），供 /__state 调试 */
    fun state(): String {
        val now = System.currentTimeMillis()
        val s = running
        return try {
            JSONObject()
                .put("wv", wv != null)
                .put("pageReady", pageReady)
                .put("initStarted", initStarted)
                .put("initFailed", initFailed)
                .put("queueSize", queue.size)
                .put("running", s != null)
                .put("runningInjected", s?.injected ?: false)
                .put("runningAgeMs", if (s != null) now - s.createdAt else -1)
                .put("lastSignalAgeMs", if (s != null) now - s.lastSignal else -1)
                .put("runningFinished", s?.isFinished() ?: false)
                .toString()
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
    }

    /**
     * 销毁引擎并重置状态（任意线程可调，销毁动作在主线程执行；随后 ensure() 会重建）。
     * 用于 WebView 渲染进程被系统回收（僵尸引擎，evaluateJavascript 静默失效）后的自愈。
     */
    private fun destroyEngine() {
        synchronized(lock) {
            initStarted = false
            initFailed = false
            pageReady = false
        }
        main.post {
            val old = wv
            wv = null
            try {
                old?.stopLoading()
                old?.destroy()
            } catch (e: Exception) {
                LogBuffer.log("WebView 销毁异常: ${e.message}")
            }
            // 清理排队中的会话（running 由调用方心跳检测负责 fail）
            while (queue.isNotEmpty()) {
                try {
                    queue.removeFirst().fail(null, "引擎已重建，请重试")
                } catch (ignored: Exception) {
                }
            }
        }
    }

    /**
     * 阻塞发起一次对话（调用线程等待完成/超时）。事件经 [ChatGPTClient.Event] 回调。
     * @return true=已通过 WebView 发起（错误也以 Event.Error 上报）；false=引擎不可用（调用方回退 OkHttp）
     */
    fun chatBlocking(
        context: Context,
        request: ConversationRequest,
        onEvent: (ChatGPTClient.Event) -> Unit,
        onToken: (String) -> Unit = {},
        readyTimeoutMs: Long = 30_000,
        chatTimeoutMs: Long = 150_000,
        useUi: Boolean = false
    ): Boolean {
        // 本方法会阻塞等待 WebView JS 回调；JS 回调依赖主线程消息循环，
        // 若在主线程调用将互相等待直到超时，故禁止。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("WebViewChatEngine.chatBlocking 禁止在主线程调用（会死锁）")
        }
        if (initFailed) return false
        ensure(context)
        if (!waitReady(readyTimeoutMs)) {
            LogBuffer.log("WebView 引擎未就绪（首页加载超时 ${readyTimeoutMs / 1000}s），回退 OkHttp 通道")
            return false
        }
        if (wv == null) return false
        val session = Session(request, onEvent, onToken, useUi)
        main.post {
            queue.addLast(session)
            pump()
        }
        val deadline = System.currentTimeMillis() + chatTimeoutMs
        var retries = 0
        while (!session.isFinished()) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) {
                session.fail(null, "WebView 对话超时（${chatTimeoutMs / 1000}s），请重试")
                break
            }
            val doneNow = try {
                session.done.await(minOf(500L, remain), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                true
            }
            if (doneNow || session.isFinished()) break
            // 僵尸引擎检测：JS 已注入但 25s 无任何信号 → 渲染进程被系统回收（evaluateJavascript 静默失效）
            if (session.injected && System.currentTimeMillis() - session.lastSignal > 25_000) {
                retries++
                if (retries > 2) {
                    session.fail(null, "WebView 引擎连续无响应，请重试")
                    break
                }
                LogBuffer.log("WebView 引擎无响应（渲染进程可能已被系统回收），销毁重建自动重试（第 $retries 轮）")
                Log.i("CGFREE_JS", "engine-zombie retry=$retries")
                destroyEngine()
                ensure(context)
                if (!waitReady(90_000)) {
                    session.fail(null, "WebView 引擎重建后仍未就绪（90s）")
                    break
                }
                retrySession(session)
            }
        }
        return true
    }

    /** 主线程：将同一会话重置后重新入队执行（引擎重建后重试，对调用方透明） */
    private fun retrySession(s: Session) {
        main.post {
            if (running === s) running = null
            s.injected = false
            s.touch()
            queue.addLast(s)
            pump()
        }
    }

    /** 主线程：串行取出队列中的会话并执行 */
    private fun pump() {
        if (running != null) return
        val s = queue.removeFirstOrNull() ?: return
        running = s
        val w = wv
        if (w == null) {
            running = null
            s.fail(null, "WebView 不可用")
            return
        }
        try {
            s.injected = true
            w.evaluateJavascript(buildJs(s.request, s.useUi), null)
        } catch (e: Exception) {
            running = null
            s.fail(null, "WebView 执行失败: ${e.message}")
        }
    }
    /** 组装在 chatgpt.com 同源页面内执行的对话 JS（fetch → SSE 解析 → 回调） */
    private fun buildJs(request: ConversationRequest, useUi: Boolean): String {
        if (useUi) {
            val text = promptText(request)
            return JS_TEMPLATE_UI.replace("__MSG__", jsStr(text))
        }
        val payload = ChatGPTClient.buildBody(request)
        // 注入到 JS 单引号字符串：转义反斜杠与单引号（JSON 本身无换行）
        val body = payload.replace("\\", "\\\\").replace("'", "\\'")
        val buildId = UUID.randomUUID().toString()
        return JS_TEMPLATE
            .replace("__BUILD_ID__", buildId)
            .replace("__BODY__", body)
    }
    /**
     * UI 通道本身没有调用方的对话上下文，因此把 OpenAI messages 转成一段明确的提示词。
     * 单条 user 消息保持原样；多轮/含 system 时保留角色与顺序，避免此前只发送最后一句而丢上下文。
     */
    private fun promptText(request: ConversationRequest): String {
        val usable = request.messages.filter { it.content.isNotBlank() }
        if (usable.size == 1 && usable[0].role == "user") return usable[0].content
        if (usable.isEmpty()) return "hi"
        return buildString {
            append("请根据下面的完整对话继续回复最后一条用户消息。\n\n")
            usable.forEach { message ->
                val role = when (message.role) {
                    "system" -> "系统"
                    "assistant" -> "助手"
                    else -> "用户"
                }
                append(role).append("：").append(message.content).append("\n\n")
            }
        }.trimEnd()
    }
    /** JS 单引号字符串转义（含换行 → \\n） */
    private fun jsStr(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    /** JS→Kotlin 桥说明：addJavascriptInterface 使用匿名 object 实现（方法自动在主线程回调） */

    private fun friendlyError(status: Int?, message: String): String {
        val detail = message.take(220).replace('\n', ' ')
        if (status == 403 && detail.contains("Unusual activity", ignoreCase = true)) {
            return "浏览器指纹通道仍触发设备/IP 风控（403 Unusual activity）：说明当前 IP/账号已被 OpenAI 标记，与请求指纹无关。建议：① 更换网络（切换 Wi-Fi/热点/VPN 节点）后重试；② 在手机浏览器打开 chatgpt.com 确认可正常对话；③ 等 10-30 分钟冷却。$detail"
        }
        if (status == 401 || detail.contains("session expired", ignoreCase = true) || detail.contains("invalid session", ignoreCase = true)) {
            return "登录会话失效（HTTP ${status ?: "?"}）：请重新到「账号」页用内置浏览器登录 ChatGPT（自动保存完整 Cookie），或手动粘贴新令牌。$detail"
        }
        return "上游错误（HTTP ${status ?: "网络"}）：$detail"
    }

    /**
     * 对话执行 JS：先取最新 session accessToken → POST /backend-api/conversation（同源、自动带
     * 完整 Cookie）→ ReadableStream 逐块解析 SSE → 回调全文快照 / 结束 / 错误。
     * 注意：本模板不允许出现 $ 字符（Kotlin raw string 插值冲突）。
     */
    private val JS_TEMPLATE = """
        (async function(){
          try {
            var at = '';
            try {
              var s = await fetch('/api/auth/session', {credentials:'include', cache:'no-store'})
                .then(function(r){ return r.json(); });
              at = (s && s.accessToken) || '';
              if (at) { AndroidBridge.onToken(at); }
            } catch(e) {}
            var resp = await fetch('/backend-api/conversation', {
              method: 'POST',
              credentials: 'include',
              headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream',
                'Authorization': at ? ('Bearer ' + at) : '',
                'OpenAI-Build-ID': '__BUILD_ID__'
              },
              body: '__BODY__'
            });
            if (!resp.ok) {
              var t = '';
              try { t = await resp.text(); } catch(e) {}
              AndroidBridge.onError(resp.status, String(t).slice(0, 600));
              return;
            }
            var reader = resp.body.getReader();
            var dec = new TextDecoder();
            var buf = '';
            for (;;) {
              var r = await reader.read();
              if (r.done) break;
              buf += dec.decode(r.value, {stream:true});
              var i;
              while ((i = buf.indexOf('\n\n')) >= 0) {
                var block = buf.slice(0, i);
                buf = buf.slice(i + 2);
                var lines = block.split('\n');
                for (var k = 0; k < lines.length; k++) {
                  var line = lines[k];
                  if (line.indexOf('data:') !== 0) continue;
                  var data = line.slice(5).trim();
                  if (!data || data === '[DONE]') continue;
                  var ev;
                  try { ev = JSON.parse(data); } catch(e) { continue; }
                  if (ev && ev.message && ev.message.author &&
                      ev.message.author.role === 'assistant' &&
                      ev.message.content && ev.message.content.parts) {
                    var parts = ev.message.content.parts;
                    var txt = '';
                    for (var j = 0; j < parts.length; j++) {
                      if (typeof parts[j] === 'string') txt += parts[j];
                    }
                    if (txt) AndroidBridge.onEvent(txt);
                    if (ev.message.end_turn) { AndroidBridge.onDone(); return; }
                  }
                }
              }
            }
            AndroidBridge.onDone();
          } catch(e) {
            AndroidBridge.onError(0, String((e && e.message) || e));
}
        })();
    """.trimIndent()
    /**
     * UI 自动化对话 JS：直接操作 chatgpt.com 页面原生输入框与发送按钮，
     * 由 OpenAI 页面自身 JS 完成全部风控流程（proof-of-work/sentinel/Arkose/内部 header），
     * 再用轮询读取最后一条 assistant 消息 DOM 文本，逐帧回调全文快照。
     * 这是与"用户手动在浏览器对话" 100% 等价的路径，用于绕过裸 fetch 被识破的 403。
     * 注意：本模板不允许出现 $ 字符（Kotlin raw string 插值冲突）。
     */
    private val JS_TEMPLATE_UI = """
        (async function(){
          var log = function(m){ try { AndroidBridge.onLog(m); } catch(e){} };
          try {
            // 先验证真实 WebView 会话，避免在登录页/验证页上空等数分钟。
            var auth = null;
            try {
              var authResp = await fetch('/api/auth/session', {credentials:'include', cache:'no-store'});
              auth = authResp.ok ? await authResp.json() : null;
            } catch(e) {}
            if (!auth || !auth.accessToken) {
              AndroidBridge.onError(401, 'ChatGPT 网页登录已失效，请到账号页重新登录');
              return;
            }
            AndroidBridge.onToken(auth.accessToken);
            function setNativeValue(el, value) {
              var proto = Object.getPrototypeOf(el);
              var desc = Object.getOwnPropertyDescriptor(proto, 'value');
              if (desc && desc.set) desc.set.call(el, value);
              el.dispatchEvent(new Event('input', { bubbles: true }));
            }
            function findInput() {
              var q = document.querySelector('#prompt-textarea');
              if (q) return q;
              q = document.querySelector('main textarea');
              if (q) return q;
              q = document.querySelector('main [contenteditable="true"]');
              return q;
            }
            function findSendBtn() {
              var b = document.querySelector('button[data-testid="send-button"]');
              if (b) return b;
              b = document.querySelector('button[aria-label="Send prompt"]');
              if (b) return b;
              b = document.querySelector('button[aria-label*="发送"]');
              return b;
            }
            function findStopBtn() {
              return document.querySelector('button[data-testid="stop-button"]') ||
                document.querySelector('button[aria-label*="Stop"]') ||
                document.querySelector('button[aria-label*="停止"]');
            }
            function pageError() {
              var el = document.querySelector('[role="alert"]');
              var text = el ? String(el.innerText || el.textContent || '').trim() : '';
              if (text) return text.slice(0, 300);
              var body = document.body ? String(document.body.innerText || '') : '';
              var marks = ['Something went wrong', 'Our systems are a bit busy', 'Unusual activity', '出错了', '系统繁忙'];
              for (var mi = 0; mi < marks.length; mi++) if (body.indexOf(marks[mi]) >= 0) return marks[mi];
              return '';
            }
            function assistantText() {
              var nodes = document.querySelectorAll('[data-message-author-role="assistant"]');
              if (!nodes || !nodes.length) return '';
              var last = nodes[nodes.length - 1];
              if (!last) return '';
              var t = last.innerText || last.textContent || '';
              return t.replace(/\u200b/g, '');
            }
            function assistantCount() {
              var nodes = document.querySelectorAll('[data-message-author-role="assistant"]');
              return nodes ? nodes.length : 0;
            }
            log('ui-start, msg-len=' + '__MSG__'.length);
            var pre = assistantText();
            var preCount = assistantCount();
            log('pre-text-len=' + pre.length + ' pre-assistant-count=' + preCount);
            // ---------- 输入函数化（typeText 同步无 await，可在重试中复用） ----------
            function typeText(inp) {
              if (!inp) return false;
              inp.focus();
              var ce = (inp.isContentEditable === true) || (inp.tagName === 'DIV');
              var ok = false;
              if (ce) {
                try {
                  var sel = window.getSelection();
                  sel.removeAllRanges();
                  var range = document.createRange();
                  range.selectNodeContents(inp);
                  sel.addRange(range);
                  ok = document.execCommand('insertText', false, '__MSG__');
                  log('execCommand-insertText=' + ok);
                } catch(e) { log('execCommand-ex=' + (e.message || e)); }
                if (!ok) {
                  inp.innerHTML = '';
                  inp.innerText = '__MSG__';
                  inp.dispatchEvent(new Event('input', { bubbles: true }));
                  inp.dispatchEvent(new Event('change', { bubbles: true }));
                  ok = true;
                  log('fallback-innerText');
                }
              } else {
                try {
                  var proto = Object.getPrototypeOf(inp);
                  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                  if (desc && desc.set) desc.set.call(inp, '__MSG__');
                  inp.dispatchEvent(new Event('input', { bubbles: true }));
                } catch(e) { log('nativeValue-ex=' + (e.message || e)); inp.value = '__MSG__'; }
                ok = true;
              }
              return ok;
            }
            // 等待输入框出现（SPA 渲染延迟/页面重载场景），最多 20s
            var input = null;
            for (var wi2 = 0; wi2 < 40; wi2++) {
              input = findInput();
              if (input) break;
              var earlyError = pageError();
              if (earlyError) { AndroidBridge.onError(0, earlyError); return; }
              if (wi2 % 6 === 5) log('wait-input ' + ((wi2 + 1) * 500) + 'ms');
              await new Promise(function(res){ setTimeout(res, 500); });
            }
            if (!input) {
              AndroidBridge.onError(0, '页面未找到输入框（等待20s仍无输入框，页面可能加载失败/未完成渲染或登录态失效）');
              return;
            }
            log('input-found: ' + input.tagName + '#' + (input.id || ''));
            var typed = typeText(input);
            log('typed=' + typed + ' ce=' + ((input.isContentEditable === true) || (input.tagName === 'DIV')));
            await new Promise(function(res){ setTimeout(res, 1200); });
            var contentNow = ((input.isContentEditable === true) || (input.tagName === 'DIV')) ? (input.innerText || '') : (input.value || '');
            log('content-len=' + contentNow.length);
            if (typed && contentNow.length === 0) {
              log('retry-type');
              typeText(input);
              await new Promise(function(res){ setTimeout(res, 1000); });
              contentNow = ((input.isContentEditable === true) || (input.tagName === 'DIV')) ? (input.innerText || '') : (input.value || '');
              log('retry-content-len=' + contentNow.length);
            }
            // 等待发送按钮可用（React 异步更新），最多 5s
            var sendBtn = null;
            for (var wi3 = 0; wi3 < 10; wi3++) {
              sendBtn = findSendBtn();
              if (sendBtn && !sendBtn.disabled) break;
              await new Promise(function(res){ setTimeout(res, 500); });
            }
            sendBtn = findSendBtn();
            if (sendBtn && !sendBtn.disabled) {
              sendBtn.click();
              log('send-by-click');
            } else {
              AndroidBridge.onError(0, '发送按钮不可用，请检查页面验证或输入状态');
              return;
            }
            var t0 = Date.now();
            // 点击后必须在 12 秒内出现“输入已清空 / 正在生成 / 新回复”之一。
            // 失败时快速返回，禁止再次发送，避免旧逻辑最多重复提交三次相同消息。
            var accepted = false;
            for (var wa = 0; wa < 24; wa++) {
              var inputNow = findInput();
              var inputNowText = inputNow ? (((inputNow.isContentEditable === true) || inputNow.tagName === 'DIV') ? (inputNow.innerText || '') : (inputNow.value || '')) : '';
              if (findStopBtn() || assistantCount() > preCount || inputNowText.length === 0) { accepted = true; break; }
              var acceptError = pageError();
              if (acceptError) { AndroidBridge.onError(0, acceptError); return; }
              await new Promise(function(res){ setTimeout(res, 500); });
            }
            if (!accepted) {
              AndroidBridge.onError(0, '点击发送后 12 秒页面仍无响应，请打开账号页检查验证状态');
              return;
            }
            var lastText = pre;
            var lastChange = t0;
            var stableCnt = 0;
            var timeoutMs = 120000;
            var loopCnt = 0;
            function finish(cur) {
              AndroidBridge.onEvent(cur);
              log('ui-done, len=' + cur.length + ', cost=' + ((Date.now() - t0) / 1000).toFixed(1) + 's');
              AndroidBridge.onDone();
            }
            for (;;) {
              await new Promise(function(res){ setTimeout(res, 700); });
              loopCnt++;
              var cur = assistantText();
              var nodeCount = assistantCount();
              var stopNow = findStopBtn();
              // 新回复信号：assistant 节点数增长为主（回复文本与历史相同时长度不变，
              // 仅靠文本长度无法识别新回复）；文本长度增长兜底（页面复用节点编辑场景）
              var newReply = (nodeCount > preCount) || (cur.length > pre.length);
              if (loopCnt % 10 === 0 || (newReply && stableCnt === 0)) {
                var dbgBtn = findSendBtn();
                log('loop#' + loopCnt + ' textLen=' + cur.length + ' nodes=' + nodeCount + '/' + preCount + ' stop=' + (stopNow ? 'Y' : 'N') + ' sendBtn=' + (dbgBtn ? (dbgBtn.disabled ? 'disabled' : 'ok') : 'missing') + ' stable=' + stableCnt + ' new=' + (newReply ? 'Y' : 'N'));
              }
              if (cur !== lastText) {
                lastText = cur;
                lastChange = Date.now();
                stableCnt = 0;
                if (newReply) AndroidBridge.onEvent(cur);
              } else if (newReply) {
                if (!stopNow) stableCnt++; else stableCnt = 0;
              }
              var sendBtn2 = findSendBtn();
              var done = !stopNow && newReply && sendBtn2 && !sendBtn2.disabled;
              if (done) { finish(cur); return; }
              if (!stopNow && newReply && stableCnt >= 5) { finish(cur); return; }
              var runtimeError = pageError();
              if (!stopNow && runtimeError && !newReply) { AndroidBridge.onError(0, runtimeError); return; }
              if (Date.now() - t0 > timeoutMs) {
                AndroidBridge.onError(0, 'UI 对话等待超时 120s（最后文本长度=' + cur.length + ' 节点=' + nodeCount + '/' + preCount + '，可能页面出现错误提示/验证）');
                return;
              }
              if (Date.now() - lastChange > 30000 && newReply && !stopNow) {
                AndroidBridge.onError(0, 'UI 对话输出停滞 30s（最后文本长度=' + cur.length + ' 节点=' + nodeCount + '/' + preCount + '）');
                return;
              }
            }
          } catch(e) {
            AndroidBridge.onError(0, String((e && e.message) || e));
          }
        })();
    """.trimIndent()
}
