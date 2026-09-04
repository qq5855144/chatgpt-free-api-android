package com.cgfree.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cgfree.data.TokenStore
import com.cgfree.databinding.FragmentAccountBinding

class AccountFragment : Fragment() {
    private var _b: FragmentAccountBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAccountBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.webLoginBtn.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }

        b.saveBtn.setOnClickListener {
            val at = b.accessTokenInput.text.toString().trim()
            val st = b.sessionInput.text.toString().trim()
            val ck = b.cookieInput.text.toString().trim()
            if (at.isEmpty() && st.isEmpty() && ck.isEmpty()) {
                Toast.makeText(requireContext(), "请至少填写 Access Token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (at.isNotEmpty()) TokenStore.saveAccessToken(requireContext(), at)
            if (st.isNotEmpty()) TokenStore.saveSessionToken(requireContext(), st)
            if (ck.isNotEmpty()) TokenStore.saveCookie(requireContext(), ck)
            refreshStatus()
            Toast.makeText(requireContext(), "令牌已加密保存", Toast.LENGTH_SHORT).show()
        }
        b.clearBtn.setOnClickListener {
            TokenStore.clear(requireContext())
            b.accessTokenInput.setText("")
            b.sessionInput.setText("")
            b.cookieInput.setText("")
            refreshStatus()
            Toast.makeText(requireContext(), "已清空本地令牌", Toast.LENGTH_SHORT).show()
        }

        // ---------- 复制功能 ----------
        b.copyAtBtn.setOnClickListener { copyToken("Access Token", TokenStore.getAccessToken(requireContext())) }
        b.copyStBtn.setOnClickListener { copyToken("Session Token", TokenStore.getSessionToken(requireContext())) }
        b.copyCookieBtn.setOnClickListener { copyToken("Cookie", TokenStore.getCookie(requireContext())) }
        b.copyAllBtn.setOnClickListener {
            val ctx = requireContext()
            val at = TokenStore.getAccessToken(ctx)
            val st = TokenStore.getSessionToken(ctx)
            val ck = TokenStore.getCookie(ctx)
            val email = TokenStore.getEmail(ctx)
            val sb = StringBuilder()
            if (!at.isNullOrBlank()) sb.append("AccessToken=").append(at).append('\n')
            if (!st.isNullOrBlank()) sb.append("SessionToken=").append(st).append('\n')
            if (!ck.isNullOrBlank()) sb.append("Cookie=").append(ck).append('\n')
            if (!email.isNullOrBlank()) sb.append("Email=").append(email).append('\n')
            if (sb.isEmpty()) {
                Toast.makeText(ctx, "暂无已保存的凭证，请先登录/粘贴", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard(ctx, "全部凭证（含 AccessToken/SessionToken/Cookie，请勿泄露！）", sb.toString())
            Toast.makeText(ctx, "已复制全部凭证（含 Cookie）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToken(label: String, value: String?) {
        if (value.isNullOrBlank()) {
            Toast.makeText(requireContext(), "暂无$label，请先登录/粘贴", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(requireContext(), label, value)
        Toast.makeText(requireContext(), "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val at = TokenStore.getAccessToken(requireContext())
        val email = TokenStore.getEmail(requireContext())
        val st = TokenStore.getSessionToken(requireContext())
        val ck = TokenStore.getCookie(requireContext())
        if (at.isNullOrBlank()) {
            b.statusText.text = "未登录"
            b.statusText.setTextColor(0xFFC62828.toInt())
        } else {
            val masked = if (at.length > 16) at.take(8) + "…" + at.takeLast(6) else "已设置"
            b.statusText.text = buildString {
                append("已登录")
                if (!email.isNullOrBlank()) append(" · $email")
                append("\nAccessToken: $masked")
                if (!st.isNullOrBlank()) append("\nSessionToken: 已保存（可自动刷新）")
                if (!ck.isNullOrBlank()) append("\nCookie: 已保存（会话完整，风控友好）")
            }
            b.statusText.setTextColor(0xFF2E7D32.toInt())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}