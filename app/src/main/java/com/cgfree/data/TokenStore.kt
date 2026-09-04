package com.cgfree.data

import android.content.Context
import android.content.SharedPreferences
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
        prefs(context).edit().putString("cookie", value).apply()

    fun getCookie(context: Context): String? =
        prefs(context).getString("cookie", null)?.takeIf { it.isNotEmpty() }

    fun saveEmail(context: Context, value: String) =
        prefs(context).edit().putString("email", value).apply()

    fun getEmail(context: Context): String? =
        prefs(context).getString("email", null)?.takeIf { it.isNotEmpty() }

    fun isLoggedIn(context: Context): Boolean = !getAccessToken(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}