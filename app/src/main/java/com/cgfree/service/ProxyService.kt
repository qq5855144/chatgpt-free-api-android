package com.cgfree.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cgfree.MainActivity
import com.cgfree.R
import com.cgfree.data.Prefs
import com.cgfree.proxy.ProxyServer
import com.cgfree.util.LogBuffer

/**
 * 前台服务：承载本地 OpenAI 兼容反向代理，保证后台运行。
 */
class ProxyService : Service() {

    companion object {
        private const val CHANNEL_ID = "cgfree_proxy"
        private const val NOTIFY_ID = 8787

        @Volatile
        var instance: ProxyServer? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notify = buildNotification("正在启动…")
        startForeground(NOTIFY_ID, notify)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等保护：服务已在运行（instance 非空）时不重复创建，
        // 避免重复 bind 端口抛异常进入 catch 分支误 stopSelf 杀掉正常服务
        if (instance != null) {
            return START_STICKY
        }

        val port = Prefs.port(this)
        val lan = Prefs.lanEnabled(this)
        val apiKey = Prefs.apiKey(this)

        try {
            val server = ProxyServer(applicationContext, port, lan)
            server.start(NanoHttpdTimeoutMs)
            instance = server
            val hostDesc = if (lan) "0.0.0.0" else "127.0.0.1"
            LogBuffer.log("proxy 已启动: http://$hostDesc:$port/v1  apiKey=${if (apiKey.isNullOrBlank()) "无" else "已设置"}")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, buildNotification("运行中 · http://127.0.0.1:$port/v1"))
        } catch (e: Exception) {
            LogBuffer.log("proxy 启动失败: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { instance?.stop() }
        instance = null
        LogBuffer.log("proxy 已停止（手动停止或系统回收）")
        super.onDestroy()
    }

    private val NanoHttpdTimeoutMs = 60_000

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "ChatGPT Free API 代理",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("ChatGPT Free API")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}