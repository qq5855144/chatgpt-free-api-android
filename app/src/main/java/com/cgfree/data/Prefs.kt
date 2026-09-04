package com.cgfree.data

import android.content.Context

/** 普通偏好（非敏感配置）：端口 / 局域网开关 / API 访问密钥 / 当前模型 */
object Prefs {
    private const val NAME = "cgfree_prefs"

    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun port(c: Context): Int = p(c).getInt("port", 8787)
    fun setPort(c: Context, v: Int) = p(c).edit().putInt("port", v).apply()

    fun lanEnabled(c: Context): Boolean = p(c).getBoolean("lan", false)
    fun setLan(c: Context, v: Boolean) = p(c).edit().putBoolean("lan", v).apply()

    fun apiKey(c: Context): String? = p(c).getString("api_key", null)?.takeIf { it.isNotEmpty() }
    fun setApiKey(c: Context, v: String) = p(c).edit().putString("api_key", v.trim()).apply()

    fun model(c: Context): String = p(c).getString("model", ModelConst.DEFAULT) ?: ModelConst.DEFAULT
    fun setModel(c: Context, v: String) = p(c).edit().putString("model", v).apply()
}