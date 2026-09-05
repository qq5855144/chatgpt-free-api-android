package com.cgfree.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cgfree.data.TokenStore
import com.cgfree.databinding.ActivityLoginBinding
import com.cgfree.net.WebViewChatEngine

/**
 * WebView 登录页：用户手动登录 chatgpt.com，
 * 登录成功后通过页面内 JS（localStorage / fetch session）提取 accessToken，
 * 并读取 HttpOnly Cookie 中的 __Secure-next-auth.session-token 一并保存。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    private val jsExtract = """
        (function(){
          try {
            var at = localStorage.getItem('accessToken');
            if (at) { AndroidBridge.onTokens(at, ''); return; }
            fetch('/api/auth/session', {credentials:'include'})
              .then(function(r){ return r.json(); })
              .then(function(s){
                AndroidBridge.onTokens((s && s.accessToken) || '', (s && s.user && s.user.email) || '');
              })
              .catch(function(){ AndroidBridge.onTokens('', ''); });
          } catch(e) { try { AndroidBridge.onTokens('', ''); } catch(_) {} }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.webView, true)

        b.webView.settings.javaScriptEnabled = true
        b.webView.settings.domStorageEnabled = true
        b.webView.settings.databaseEnabled = true
        b.webView.settings.mediaPlaybackRequiresUserGesture = false
        b.webView.settings.userAgentString =
            b.webView.settings.userAgentString.replace("; wv", "")

        b.webView.webChromeClient = WebChromeClient()
        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith("https://chatgpt.com") == true ||
                    url?.startsWith("https://chat.openai.com") == true
                ) {
                    b.tipText.text = "已进入 ChatGPT 页面。若已完成登录，请点击下方「我已登录，提取令牌」。"
                }
            }
        }

        b.webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onTokens(accessToken: String, email: String) {
                runOnUiThread {
                    val cookie = TokenStore.captureCookieFromWebView(this@LoginActivity) ?: ""
                    val session = extractSessionToken(cookie)
                    if (accessToken.isNotBlank()) {
                        TokenStore.saveAccessToken(this@LoginActivity, accessToken)
                        if (session != null) TokenStore.saveSessionToken(this@LoginActivity, session)
                        if (email.isNotBlank()) TokenStore.saveEmail(this@LoginActivity, email)
                        WebViewChatEngine.reloadSession(this@LoginActivity)
                        Toast.makeText(this@LoginActivity, "令牌提取成功：accessToken ${if (accessToken.isNotBlank()) "✓" else "✗"}，sessionToken ${if (session != null) "✓" else "✗"}", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "提取失败：未检测到 accessToken，请确认已在页面内完成登录；失败可改用「手动粘贴令牌」", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "AndroidBridge")

        b.webView.loadUrl("https://chatgpt.com/auth/login")

        b.extractBtn.setOnClickListener {
            b.webView.evaluateJavascript(jsExtract, null)
        }
    }

    private fun extractSessionToken(cookie: String): String? {
        val values = cookie.split(';').mapNotNull { part ->
            val p = part.trim()
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
        }.toMap()
        val names = listOf("__Secure-next-auth.session-token", "__Secure-authjs.session-token")
        for (name in names) {
            values[name]?.takeIf { it.isNotBlank() }?.let { return it }
            val chunks = values.entries
                .mapNotNull { (key, value) ->
                    val index = key.removePrefix("$name.").toIntOrNull()
                    if (key.startsWith("$name.") && index != null) index to value else null
                }
                .sortedBy { it.first }
            if (chunks.isNotEmpty()) return chunks.joinToString("") { it.second }
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (b.webView.canGoBack()) b.webView.goBack() else super.onBackPressed()
    }
}
