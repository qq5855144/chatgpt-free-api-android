package com.cgfree.data

import android.content.Context

/** 普通偏好（非敏感配置）：端口 / 局域网开关 / API 访问密钥 / 当前模型 */
object Prefs {
    private const val NAME = "cgfree_prefs"
    const val DEFAULT_PORT = 5656
    private const val LEGACY_DEFAULT_PORT = 8787
    private const val PORT_MIGRATION_DONE = "port_5656_migrated"

    /** 默认可复制的访问密钥：配合反代地址填入任意 OpenAI 兼容客户端（清空则不校验） */
    const val DEFAULT_API_KEY = "sk-cgfree-local"

    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * 默认端口从 8787 迁移到 5656。升级用户仅自动迁移一次；迁移完成后仍可手动改回任意端口。
     */
    fun port(c: Context): Int {
        val prefs = p(c)
        if (!prefs.contains("port")) {
            prefs.edit().putBoolean(PORT_MIGRATION_DONE, true).apply()
            return DEFAULT_PORT
        }
        val current = prefs.getInt("port", DEFAULT_PORT)
        if (!prefs.getBoolean(PORT_MIGRATION_DONE, false)) {
            val migrated = if (current == LEGACY_DEFAULT_PORT) DEFAULT_PORT else current
            prefs.edit()
                .putInt("port", migrated)
                .putBoolean(PORT_MIGRATION_DONE, true)
                .apply()
            return migrated
        }
        return current
    }

    fun setPort(c: Context, v: Int) = p(c).edit()
        .putInt("port", v)
        .putBoolean(PORT_MIGRATION_DONE, true)
        .apply()

    fun lanEnabled(c: Context): Boolean = p(c).getBoolean("lan", false)
    fun setLan(c: Context, v: Boolean) = p(c).edit().putBoolean("lan", v).apply()

    fun apiKey(c: Context): String? = p(c).getString("api_key", null)?.takeIf { it.isNotEmpty() }
    fun setApiKey(c: Context, v: String) = p(c).edit().putString("api_key", v.trim()).apply()

    /** 当前生效密钥：已设置则用之，否则返回默认可复制密钥 */
    fun apiKeyOrDefault(c: Context): String = apiKey(c) ?: DEFAULT_API_KEY

    fun model(c: Context): String = p(c).getString("model", ModelConst.DEFAULT) ?: ModelConst.DEFAULT
    fun setModel(c: Context, v: String) = p(c).edit().putString("model", v).apply()
}
