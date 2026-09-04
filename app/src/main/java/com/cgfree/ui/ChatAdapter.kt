package com.cgfree.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cgfree.R
import com.cgfree.databinding.ItemMessageBinding

data class MessageItem(val role: String, var text: String, val error: Boolean = false)

class ChatAdapter(private val ctx: Context) : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = ArrayList<MessageItem>()

    class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding

        b.msgText.text = item.text
        val lp = b.card.layoutParams as? LinearLayout.LayoutParams
        if (item.role == "user") {
            b.itemRoot.gravity = Gravity.END
            lp?.marginStart = dp(72)
            lp?.marginEnd = dp(4)
            b.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.user_bubble))
            b.msgText.setTextColor(Color.WHITE)
        } else {
            b.itemRoot.gravity = Gravity.START
            lp?.marginStart = dp(4)
            lp?.marginEnd = dp(72)
            b.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.assistant_bubble))
            b.msgText.setTextColor(ContextCompat.getColor(ctx, R.color.assistant_text))
        }
        if (item.error) {
            b.msgText.setTextColor(ContextCompat.getColor(ctx, R.color.assistant_text))
            b.card.setCardBackgroundColor(0xFFFFEBEE.toInt())
            b.msgText.text = "⚠ " + item.text
        }
        b.card.layoutParams = lp
    }

    fun addUser(text: String) {
        items.add(MessageItem("user", text))
        notifyItemInserted(items.size - 1)
    }

    fun addAssistantPlaceholder(): Int {
        items.add(MessageItem("assistant", ""))
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    fun updateAssistant(index: Int, text: String) {
        if (index in items.indices) {
            items[index].text = text
            notifyItemChanged(index)
        }
    }

    fun markError(index: Int, text: String) {
        if (index in items.indices) {
            items[index].text = text
            items[index] = items[index].copy(error = true)
            notifyItemChanged(index)
        }
    }

    fun addSystem(text: String) {
        items.add(MessageItem("system", text))
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}