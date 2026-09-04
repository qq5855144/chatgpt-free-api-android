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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cgfree.BuildConfig
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
        val onToken: (String) -> Unit,
        val useUi: Boolean = false
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
                    @JavascriptInterface
                    fun onLog(msg: String) {
                        LogBuffer.log("[WV-UI] $msg")
                        Log.i("CGFREE_JS", msg)
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
        chatTimeoutMs: Long = 300_000,
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
            w.evaluateJavascript(buildJs(s.request, s.useUi), null)
        } catch (e: Exception) {
            running = null
            s.fail(null, "WebView 执行失败: ${e.message}")
        }
    }
    /** 组装在 chatgpt.com 同源页面内执行的对话 JS（fetch → SSE 解析 → 回调） */
    private fun buildJs(request: ConversationRequest, useUi: Boolean): String {
        if (useUi) {
            val text = lastUserText(request)
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
    /** 取最后一条 user 消息纯文本（UI 自动化需要打字进输入框） */
    private fun lastUserText(request: ConversationRequest): String {
        for (i in request.messages.indices.reversed()) {
            val m = request.messages[i]
            if (m.role == "user" && m.content.isNotBlank()) return m.content
        }
        return request.messages.lastOrNull()?.content ?: "hi"
    }
    /** JS 单引号字符串转义（含换行 → \\n） */
    private fun jsStr(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

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
            function setNativeValue(el, value) {
              var proto = Object.getPrototypeOf(el);
              var desc = Object.getOwnPropertyDescriptor(proto, 'value');
              if (desc && desc.set) desc.set.call(el, value);
              el.dispatchEvent(new Event('input', { bubbles: true }));
            }
            function findInput() {
              var q = document.querySelector('textarea#prompt-textarea');
              if (q) return q;
              q = document.querySelector('div#prompt-textarea');
              if (q) return q;
              q = document.querySelector('textarea[placeholder]');
              if (q) return q;
              q = document.querySelector('[contenteditable="true"]');
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
              return document.querySelector('button[data-testid="stop-button"]');
            }
            function assistantText() {
              var nodes = document.querySelectorAll('[data-message-author-role="assistant"]');
              if (!nodes || !nodes.length) return '';
              var last = nodes[nodes.length - 1];
              if (!last) return '';
              var t = last.innerText || last.textContent || '';
              return t.replace(/\u200b/g, '');
            }
            log('ui-start, msg-len=' + '__MSG__'.length);
            var pre = assistantText();
            var input = findInput();
            if (!input) {
              AndroidBridge.onError(0, '页面未找到输入框（chatgpt.com DOM 结构可能变化或未进入可对话状态）');
              return;
            }
            log('input-found: ' + input.tagName + '#' + (input.id || ''));
            input.focus();
            var isContentEditable = (input.isContentEditable === true) || (input.tagName === 'DIV');
            var typedOk = false;
            if (isContentEditable) {
              try {
                var sel = window.getSelection();
                sel.removeAllRanges();
                var range = document.createRange();
                range.selectNodeContents(input);
                sel.addRange(range);
                typedOk = document.execCommand('insertText', false, '__MSG__');
                log('execCommand-insertText=' + typedOk);
              } catch(e) { log('execCommand-ex=' + (e.message || e)); }
              if (!typedOk) {
                input.innerHTML = '';
                input.innerText = '__MSG__';
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                typedOk = true;
                log('fallback-innerText');
              }
            } else {
              setNativeValue(input, '__MSG__');
              typedOk = true;
            }
            await new Promise(function(res){ setTimeout(res, 1200); });
            var contentNow = isContentEditable ? (input.innerText || '') : (input.value || '');
            log('content-len=' + contentNow.length + ' ce=' + isContentEditable);
            // 若内容没进去，重试一次输入
            if (typedOk && contentNow.length === 0) {
              log('retry-input');
              if (isContentEditable) {
                try {
                  var sel2 = window.getSelection();
                  sel2.removeAllRanges();
                  var range2 = document.createRange();
                  range2.selectNodeContents(input);
                  sel2.addRange(range2);
                  typedOk = document.execCommand('insertText', false, '__MSG__');
                  log('retry-execCommand=' + typedOk);
                } catch(e2) { log('retry-ex=' + (e2.message || e2)); }
                if (!typedOk) {
                  input.innerText = '__MSG__';
                  input.dispatchEvent(new Event('input', { bubbles: true }));
                }
              } else {
                setNativeValue(input, '__MSG__');
              }
              await new Promise(function(res){ setTimeout(res, 1000); });
              contentNow = isContentEditable ? (input.innerText || '') : (input.value || '');
              log('retry-content-len=' + contentNow.length);
            }
            // 等待发送按钮可用（React 异步更新），最多 5s
            var btn = null;
            for (var wi = 0; wi < 10; wi++) {
              btn = findSendBtn();
              if (btn && !btn.disabled) break;
              await new Promise(function(res){ setTimeout(res, 500); });
            }
            btn = findSendBtn();
            if (btn && !btn.disabled) {
              btn.click();
              log('send-by-click');
            } else {
              log('send-btn=' + (btn ? 'disabled' : 'missing') + ', fallback-enter');
              input.focus();
              var ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true });
              input.dispatchEvent(ev);
              input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', bubbles: true }));
            }
            var t0 = Date.now();
            var lastText = pre;
            var lastChange = t0;
            var stableCnt = 0;
            var timeoutMs = 240000;
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
              if (loopCnt % 10 === 0) {
                var dbgBtn = findSendBtn();
                log('loop#' + loopCnt + ' textLen=' + cur.length + ' stop=' + (findStopBtn() ? 'Y' : 'N') + ' sendBtn=' + (dbgBtn ? (dbgBtn.disabled ? 'disabled' : 'ok') : 'missing') + ' stable=' + stableCnt);
              }
              if (cur !== lastText) {
                lastText = cur;
                lastChange = Date.now();
                stableCnt = 0;
                if (cur.length > pre.length) AndroidBridge.onEvent(cur);
              } else if (cur.length > pre.length) {
                stableCnt++;
              }
              var stop = findStopBtn();
              var sendBtn = findSendBtn();
              var textGrown = cur.length > pre.length;
              // 完成判定双路径：1) sendBtn 可用（TEXTAREA 形态按钮常驻仅 disabled 切换） 2) 无 stop 且文本连续稳定 5 轮（约 3.5s，DIV 形态发送后按钮被移出 DOM 导致 sendBtn=missing）
              if (!stop && textGrown && sendBtn && !sendBtn.disabled) { finish(cur); return; }
              if (!stop && textGrown && stableCnt >= 5) { finish(cur); return; }
              if (Date.now() - t0 > timeoutMs) {
                AndroidBridge.onError(0, 'UI 对话等待超时 240s（最后文本长度=' + cur.length + '，可能页面出现错误提示/验证）');
                return;
              }
              if (Date.now() - lastChange > 90000 && cur.length > pre.length) {
                AndroidBridge.onError(0, 'UI 对话输出停滞 90s（最后文本长度=' + cur.length + '）');
                return;
              }
            }
          } catch(e) {
            AndroidBridge.onError(0, String((e && e.message) || e));
          }
        })();
    """.trimIndent()
}
