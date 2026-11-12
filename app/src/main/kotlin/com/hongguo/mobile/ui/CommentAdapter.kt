// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.R
import com.hongguo.mobile.data.CommentItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommentAdapter : RecyclerView.Adapter<CommentAdapter.Holder>() {

    private val items = mutableListOf<CommentItem>()

    fun addAll(rows: List<CommentItem>) {
        if (rows.isEmpty()) return
        val start = items.size
        items.addAll(rows)
        notifyItemRangeInserted(start, rows.size)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = items[position]
        holder.author.text = row.author.ifBlank { "红果用户" }
        holder.content.text = row.content
        holder.time.text = relativeTime(row.created)
        holder.likes.visibility = if (row.likes > 0) View.VISIBLE else View.GONE
        holder.likes.text = "赞 ${row.likes}"
    }

    private fun relativeTime(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val diff = System.currentTimeMillis() - timestamp
        val text = when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 30 * 86_400_000L -> "${diff / 86_400_000} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))
        }
        return text
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val author: TextView = view.findViewById(R.id.author)
        val content: TextView = view.findViewById(R.id.content)
        val time: TextView = view.findViewById(R.id.time)
        val likes: TextView = view.findViewById(R.id.likes)
    }
}
