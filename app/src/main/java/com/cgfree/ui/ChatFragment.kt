package com.cgfree.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cgfree.data.ChatMsg
import com.cgfree.data.ConversationRequest
import com.cgfree.data.ModelConst
import com.cgfree.data.Prefs
import com.cgfree.data.TokenStore
import com.cgfree.databinding.FragmentChatBinding
import com.cgfree.net.ChatGPTClient
import com.cgfree.util.LogBuffer
import com.cgfree.util.TextAccumulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatFragment : Fragment() {

    private var _b: FragmentChatBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: ChatAdapter
    private val history = ArrayList<ChatMsg>()
    private var streaming = false
    private var assistantIndex = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentChatBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChatAdapter(requireContext())
        b.chatList.layoutManager = LinearLayoutManager(requireContext())
        b.chatList.adapter = adapter

        // 模型下拉
        val preset = ArrayList(ModelConst.PRESET)
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, preset)
        b.modelSpinner.adapter = spinnerAdapter
        val saved = Prefs.model(requireContext())
        val idx = preset.indexOf(saved).takeIf { it >= 0 } ?: 0
        b.modelSpinner.setSelection(idx)
        b.modelSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setModel(requireContext(), preset[pos])
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })

        // 尝试拉取账号真实可用模型（失败则保留预设）
        refreshModels()

        b.sendBtn.setOnClickListener { send() }
        b.loginTip.setOnClickListener {
            Toast.makeText(requireContext(), "请到「账号」页登录 ChatGPT", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val logged = TokenStore.isLoggedIn(requireContext())
        b.loginTip.visibility = if (logged) View.GONE else View.VISIBLE
        if (!logged) b.loginTip.text = "未登录 · 点击到账号页"
    }

    private fun refreshModels() {
        val token = TokenStore.getAccessToken(requireContext()) ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = runCatching { ChatGPTClient.fetchModels(token) }.getOrNull()
            if (list.isNullOrEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val cur = Prefs.model(requireContext())
                val preset = ArrayList(ModelConst.PRESET)
                for (m in list) if (m !in preset) preset.add(m)
                val sa = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, preset)
                b.modelSpinner.adapter = sa
                val i = preset.indexOf(cur).takeIf { it >= 0 } ?: 0
                b.modelSpinner.setSelection(i)
            }
        }
    }

    private fun send() {
        val text = b.input.text.toString().trim()
        if (text.isEmpty()) return
        val token = TokenStore.getAccessToken(requireContext())
        if (token.isNullOrBlank()) {
            Toast.makeText(requireContext(), "请先到「账号」页登录 ChatGPT", Toast.LENGTH_SHORT).show()
            return
        }
        if (streaming) return

        b.input.setText("")
        history.add(ChatMsg("user", text))
        adapter.addUser(text)

        val model = b.modelSpinner.selectedItem?.toString() ?: ModelConst.DEFAULT
        val request = ConversationRequest(
            model = model,
            messages = history.toList(),
            historyAndTrainingDisabled = true
        )
        startStream(token, request)
    }

    private fun startStream(token: String, request: ConversationRequest) {
        streaming = true
        b.sendBtn.isEnabled = false
        assistantIndex = adapter.addAssistantPlaceholder()
        scrollToBottom()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val acc = TextAccumulator()
            var full = ""
            var convId: String? = null
            var errorMsg: String? = null

            ChatGPTClient.streamConversation(
                token,
                TokenStore.getSessionToken(requireContext()),
                request,
                onRefreshed = { newTok ->
                    TokenStore.saveAccessToken(requireContext(), newTok)
                    LogBuffer.log("accessToken 已自动刷新")
                },
                onEvent = { ev ->
                    when (ev) {
                        is ChatGPTClient.Event.Delta -> acc.push(ev.text) { d -> full += d }
                        is ChatGPTClient.Event.Final -> acc.push(ev.text) { d -> full += d }
                        is ChatGPTClient.Event.ConvId -> convId = ev.id
                        is ChatGPTClient.Event.Error -> errorMsg = ev.message
                        ChatGPTClient.Event.Done -> { /* ignore */ }
                    }
                }
            )

            withContext(Dispatchers.Main) {
                if (errorMsg != null) {
                    adapter.markError(assistantIndex, errorMsg!!)
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                } else {
                    adapter.updateAssistant(assistantIndex, full)
                }
                // 多轮续接
                if (full.isNotBlank()) history.add(ChatMsg("assistant", full))
                streaming = false
                b.sendBtn.isEnabled = true
                scrollToBottom()
            }
        }
    }

    private fun scrollToBottom() {
        b.chatList.post {
            if (adapter.itemCount > 0) b.chatList.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}