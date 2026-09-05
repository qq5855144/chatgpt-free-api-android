package com.cgfree.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 令牌安全存储：使用 Android Keystore 派生的 AES 密钥做加密 SharedPreferences。
 * 保存 ChatGPT 网页登录凭证：AccessToken / SessionToken / Cookie / 账号邮箱。
 */
object TokenStore {

    private const val PREFS = "cgfree_secure"

    private fun securePrefs(context: Context): SharedPreferences? = runCatching {
        val mk = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    /** 若 Keystore 异常则退回普通存储，保证 App 可用 */
    private fun prefs(context: Context): SharedPreferences =
        securePrefs(context) ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveAccessToken(context: Context, value: String) =
        prefs(context).edit().putString("access_token", value.trim()).apply()

    fun getAccessToken(context: Context): String? =
        prefs(context).getString("access_token", null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveSessionToken(context: Context, value: String) =
        prefs(context).edit().putString("session_token", value.trim()).apply()

    fun getSessionToken(context: Context): String? =
        prefs(context).getString("session_token", null)?.trim()?.takeIf { it.isNotEmpty() }

    fun saveCookie(context: Context, value: String) =
        prefs(context).edit().putString("cookie", normalizeCookie(value)).apply()

    fun getCookie(context: Context): String? =
        prefs(context).getString("cookie", null)?.takeIf { it.isNotEmpty() }

    /**
     * 把加密存储中的 Cookie 恢复到 Chromium CookieManager。
     * WebView Cookie 通常会自行持久化，但部分系统会在进程被杀后丢失；手动粘贴的 Cookie
     * 更不会自动进入 WebView。对话引擎每次创建前调用本方法，避免账号页显示已登录而实际 401。
     * 必须在主线程调用。
     */
    fun restoreCookieToWebView(context: Context, force: Boolean = false): Boolean {
        val raw = getCookie(context)?.takeIf { it.isNotBlank() } ?: return false
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        if (!force && !manager.getCookie("https://chatgpt.com").isNullOrBlank()) return true
        val pairs = cookiePairs(raw)
        if (pairs.isEmpty()) return false
        for (pair in pairs) {
            val persistent = "$pair; Path=/; Secure; SameSite=None"
            manager.setCookie("https://chatgpt.com", persistent)
            manager.setCookie("https://chat.openai.com", persistent)
        }
        manager.flush()
        return true
    }

    /** 保存 WebView 当前完整 Cookie，并强制刷入磁盘。 */
    fun captureCookieFromWebView(context: Context): String? {
        val manager = CookieManager.getInstance()
        val cookie = manager.getCookie("https://chatgpt.com")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (cookie != null) saveCookie(context, cookie)
        manager.flush()
        return cookie
    }

    /** 同时清理加密凭证与 WebView 会话，防止界面状态不一致。 */
    fun clearWebViewCookie(onCleared: () -> Unit = {}) {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies {
            manager.flush()
            onCleared()
        }
    }

    private fun normalizeCookie(value: String): String = value.trim()
        .removePrefix("Cookie:")
        .removePrefix("cookie:")
        .trim()

    private fun cookiePairs(value: String): List<String> = normalizeCookie(value)
        .split(';')
        .map { it.trim() }
        .filter { part ->
            val equals = part.indexOf('=')
            equals > 0 && !part.substring(0, equals).equals("Cookie", ignoreCase = true)
        }

    fun saveEmail(context: Context, value: String) =
        prefs(context).edit().putString("email", value).apply()

    fun getEmail(context: Context): String? =
        prefs(context).getString("email", null)?.takeIf { it.isNotEmpty() }

    fun isLoggedIn(context: Context): Boolean = !getAccessToken(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
