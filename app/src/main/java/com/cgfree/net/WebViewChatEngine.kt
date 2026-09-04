package com.cgfree.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cgfree.data.ConversationRequest
import com.cgfree.util.LogBuffer
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

    /** 单次对话会话：事件转发 + 阻塞等待 */
    private class Session(
        val request: ConversationRequest,
        val onEvent: (ChatGPTClient.Event) -> Unit,
        val onToken: (String) -> Unit
    ) {
        val done = CountDownLatch(1)
        private var finished = false

        /**
         * JS 回调：新的全文快照（SSE message.content.parts，逐事件增长）。
         * 事件语义与 OkHttp 通道一致：Delta 携带全文快照，由消费方用 TextAccumulator 差分出增量。
         */
        @Synchronized
        fun push(snapshot: String) {
            if (finished || snapshot.isEmpty()) return
            onEvent(ChatGPTClient.Event.Delta(snapshot))
        }

        /** JS 回调：事件流结束 */
        @Synchronized
        fun finish() {
            if (finished) return
            finished = true
            onEvent(ChatGPTClient.Event.Done)
            done.countDown()
            scheduleNext()
        }

        /** JS 回调：错误 */
        @Synchronized
        fun fail(status: Int?, message: String) {
            if (finished) return
            finished = true
            onEvent(ChatGPTClient.Event.Error(WebViewChatEngine.friendlyError(status, message), status))
            done.countDown()
            scheduleNext()
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
                // 去掉 "; wv" 标识，使 UA 与普通 Chrome 完全一致（登录 WebView 同款处理）
                w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
                w.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onToken(token: String) {
                        val s = running ?: return
                        if (token.isNotBlank()) s.onToken(token)
                    }

                    @JavascriptInterface
                    fun onEvent(snapshot: String) {
                        running?.push(snapshot)
                    }

                    @JavascriptInterface
                    fun onDone() {
                        running?.finish()
                    }

                    @JavascriptInterface
                    fun onError(status: Int, message: String) {
                        running?.fail(if (status == 0) null else status, message)
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
                        if (request?.isForMainFrame == true && !pageReady) {
                            LogBuffer.log("WebView 引擎首页加载错误 code=${error?.errorCode}（等待自动重试/超时兜底）")
                        }
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
     * 阻塞发起一次对话（调用线程等待完成/超时）。事件经 [ChatGPTClient.Event] 回调。
     * @return true=已通过 WebView 发起（错误也以 Event.Error 上报）；false=引擎不可用（调用方回退 OkHttp）
     */
    fun chatBlocking(
        context: Context,
        request: ConversationRequest,
        onEvent: (ChatGPTClient.Event) -> Unit,
        onToken: (String) -> Unit = {},
        readyTimeoutMs: Long = 30_000,
        chatTimeoutMs: Long = 300_000
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

        val session = Session(request, onEvent, onToken)
        main.post {
            queue.addLast(session)
            pump()
        }

        val completed = try {
            session.done.await(chatTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!completed) {
            session.fail(null, "WebView 对话超时（${chatTimeoutMs / 1000}s），请重试")
        }
        return true
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
            w.evaluateJavascript(buildJs(s.request), null)
        } catch (e: Exception) {
            running = null
            s.fail(null, "WebView 执行失败: ${e.message}")
        }
    }

    /** 组装在 chatgpt.com 同源页面内执行的对话 JS（fetch → SSE 解析 → 回调） */
    private fun buildJs(request: ConversationRequest): String {
        val payload = ChatGPTClient.buildBody(request)
        // 注入到 JS 单引号字符串：转义反斜杠与单引号（JSON 本身无换行）
        val body = payload.replace("\\", "\\\\").replace("'", "\\'")
        val buildId = UUID.randomUUID().toString()
        return JS_TEMPLATE
            .replace("__BUILD_ID__", buildId)
            .replace("__BODY__", body)
    }

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
}
