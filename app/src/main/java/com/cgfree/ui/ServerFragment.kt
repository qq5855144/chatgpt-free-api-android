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
import com.cgfree.databinding.FragmentServerBinding
import com.cgfree.service.ProxyService
import com.cgfree.util.LogBuffer
import java.net.Inet4Address
import java.net.NetworkInterface

class ServerFragment : Fragment() {

    private var _b: FragmentServerBinding? = null
    private val b get() = _b!!
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLog = object : Runnable {
        override fun run() {
            refreshLogView()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentServerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 载入配置
        b.portInput.setText(Prefs.port(requireContext()).toString())
        b.lanSwitch.isChecked = Prefs.lanEnabled(requireContext())
        b.apiKeyInput.setText(Prefs.apiKey(requireContext()) ?: "")
        refreshUrls()

        attachSwitchListener()

        b.lanSwitch.setOnCheckedChangeListener { _, _ -> refreshUrls() }

        b.copyLoopBtn.setOnClickListener { copy(b.loopUrl.text.toString()) }
        b.copyLanBtn.setOnClickListener { copy(b.lanUrl.text.toString()) }
        b.logView.setOnLongClickListener {
            LogBuffer.clear()
            refreshLogView()
            true
        }
    }

    private fun attachSwitchListener() {
        b.enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val port = b.portInput.text.toString().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    Toast.makeText(requireContext(), "端口不合法", Toast.LENGTH_SHORT).show()
                    b.enableSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (!TokenStore.isLoggedIn(requireContext())) {
                    Toast.makeText(requireContext(), "请先到「账号」页登录 ChatGPT 再启动服务", Toast.LENGTH_LONG).show()
                    b.enableSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                Prefs.setPort(requireContext(), port)
                Prefs.setLan(requireContext(), b.lanSwitch.isChecked)
                Prefs.setApiKey(requireContext(), b.apiKeyInput.text.toString())
                ProxyService.start(requireContext())
            } else {
                ProxyService.stop(requireContext())
            }
            refreshUrls()
        }
    }

    private fun syncSwitchState() {
        b.enableSwitch.setOnCheckedChangeListener(null)
        b.enableSwitch.isChecked = ProxyService.isRunning()
        attachSwitchListener()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshUrls()
        syncSwitchState()
        handler.post(refreshLog)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshLog)
    }

    private fun refreshStatus() {
        val running = ProxyService.isRunning()
        b.statusText.text = if (running) {
            "● 运行中（前台服务）"
        } else {
            "○ 已停止"
        }
        b.statusText.setTextColor(if (running) 0xFF2E7D32.toInt() else 0xFF757575.toInt())
    }

    private fun refreshUrls() {
        val port = (b.portInput.text.toString().toIntOrNull() ?: Prefs.port(requireContext())).toString()
        b.loopUrl.text = "http://127.0.0.1:$port/v1"
        val ips = lanIps()
        b.lanUrl.text = if (ips.isEmpty()) "（未连接 Wi-Fi/局域网）" else ips.joinToString("\n") { "http://$it:$port/v1" }
    }

    private fun lanIps(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun copy(text: String) {
        if (text.isBlank() || text.startsWith("（")) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", text))
        Toast.makeText(requireContext(), "已复制: $text", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLogView() {
        refreshStatus()
        val text = LogBuffer.snapshot()
        if (b.logView.text.toString() != text) {
            b.logView.text = text
            b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshLog)
        _b = null
    }
}