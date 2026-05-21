package com.example.gujengkeyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EmojiAdapter(
    private var emojiList: List<String>,
    private val onEmojiClick: (String) -> Unit
) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {


    // ... Upar ka code same rahega ...

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
        // 🔥 FIX: Apna naya 'item_emoji' layout use karein
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_emoji, parent, false)
        return EmojiViewHolder(view)
    }

    class EmojiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // ID ab 'txt_emoji' hai
        val textView: TextView = view.findViewById(R.id.txt_emoji)
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        val emoji = emojiList[position]
        holder.textView.text = emoji

        // Click listener
        holder.itemView.setOnClickListener {
            onEmojiClick(emoji)
        }
    }

    // ... Baaki code same ...

    override fun getItemCount(): Int = emojiList.size

    fun updateData(newList: List<String>) {
        emojiList = newList
        notifyDataSetChanged()
    }
}