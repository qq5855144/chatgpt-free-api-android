package com.cgfree.ui

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
            if (at.isEmpty() && st.isEmpty()) {
                Toast.makeText(requireContext(), "请至少填写 Access Token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (at.isNotEmpty()) TokenStore.saveAccessToken(requireContext(), at)
            if (st.isNotEmpty()) TokenStore.saveSessionToken(requireContext(), st)
            refreshStatus()
            Toast.makeText(requireContext(), "令牌已加密保存", Toast.LENGTH_SHORT).show()
        }

        b.clearBtn.setOnClickListener {
            TokenStore.clear(requireContext())
            b.accessTokenInput.setText("")
            b.sessionInput.setText("")
            refreshStatus()
            Toast.makeText(requireContext(), "已清空本地令牌", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val at = TokenStore.getAccessToken(requireContext())
        val email = TokenStore.getEmail(requireContext())
        val st = TokenStore.getSessionToken(requireContext())
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
            }
            b.statusText.setTextColor(0xFF2E7D32.toInt())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}